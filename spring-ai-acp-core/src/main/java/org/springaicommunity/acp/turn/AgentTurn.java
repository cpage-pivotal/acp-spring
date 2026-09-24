package org.springaicommunity.acp.turn;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.event.AgentEvent;
import org.springaicommunity.acp.event.AgentEventMapper;
import org.springaicommunity.acp.observation.AgentObservations;
import org.springaicommunity.acp.session.AgentSession;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Runs one prompt turn and publishes it as a {@link Flux} of {@link AgentEvent}.
 *
 * <p>
 * The contract this class exists to uphold: <strong>every turn emits exactly one terminal
 * event.</strong> Normal completion, an RPC error, a timeout, a dropped transport, or a
 * consumer that walks away — all five end with {@link AgentEvent.Completed} or
 * {@link AgentEvent.Failed} and nothing after it. Consumers switch over
 * {@code AgentEvent} without a default branch, so a turn that ended silently would hang
 * them.
 *
 * <p>
 * Streamed content and the turn's outcome arrive on two independent channels — the router
 * and the {@code prompt()} Mono respectively — and both feed the same {@link FluxSink},
 * which serializes them. The sink is the join point.
 */
public final class AgentTurn {

	private static final Logger logger = LoggerFactory.getLogger(AgentTurn.class);

	private final AcpAsyncClient client;

	private final SessionUpdateRouter router;

	private final AgentObservations observations;

	private AgentTurn(AcpAsyncClient client, SessionUpdateRouter router, AgentObservations observations) {
		this.client = client;
		this.router = router;
		this.observations = observations == null ? AgentObservations.NONE : observations;
	}

	public static AgentTurn on(AcpAsyncClient client, SessionUpdateRouter router) {
		return new AgentTurn(client, router, AgentObservations.NONE);
	}

	/** Same, reporting what the turn does to {@code observations}. */
	public static AgentTurn on(AcpAsyncClient client, SessionUpdateRouter router, AgentObservations observations) {
		return new AgentTurn(client, router, observations);
	}

	/**
	 * Prompts {@code session} and streams the result.
	 *
	 * <p>
	 * Cold: nothing is sent until the returned Flux is subscribed, and each subscription
	 * would run its own turn. Callers hold the session's turn permit around a single
	 * subscription.
	 */
	public Flux<AgentEvent> prompt(AgentSession session, List<AcpSchema.ContentBlock> content, Duration timeout) {
		return prompt(session, content, timeout, null);
	}

	/**
	 * Same, with what an observation needs to say which turn this was.
	 *
	 * <p>
	 * The context is built by the caller rather than derived here because the only parts
	 * of it worth recording — the runtime's id, the model the negotiated tier settled on
	 * — are things a turn does not know and its client does.
	 */
	public Flux<AgentEvent> prompt(AgentSession session, List<AcpSchema.ContentBlock> content, Duration timeout,
			AgentObservations.TurnContext context) {
		return Flux.create(sink -> start(sink, session, content, timeout, context), FluxSink.OverflowStrategy.BUFFER);
	}

	private void start(FluxSink<AgentEvent> sink, AgentSession session, List<AcpSchema.ContentBlock> content,
			Duration timeout, AgentObservations.TurnContext context) {
		String sessionId = session.sessionId();
		AgentObservations.TurnRecording recording = observations.turnStarted(
				context == null ? new AgentObservations.TurnContext("unknown", session.name(), null, false) : context);

		// Guards the terminal contract. Both channels race to finish the turn; the first
		// one wins
		// and the other becomes a no-op.
		AtomicBoolean finished = new AtomicBoolean();
		// Distinguishes "the consumer walked away" from "the turn ended on its own", so
		// we only
		// send session/cancel in the former case.
		AtomicBoolean cancelledByConsumer = new AtomicBoolean();

		try {
			router.register(sessionId, update -> AgentEventMapper.map(update).ifPresent(event -> {
				record(recording, event);
				sink.next(event);
			}));
		}
		catch (RuntimeException ex) {
			// Not unregistered here: the registration that exists belongs to another
			// turn.
			recording.failed("registration", ex);
			sink.next(new AgentEvent.Failed(ex));
			sink.complete();
			return;
		}

		Disposable subscription = client.prompt(new AcpSchema.PromptRequest(sessionId, content))
			.timeout(timeout)
			.subscribe(
					response -> finish(sink, finished, recording, sessionId,
							new AgentEvent.Completed(response.stopReason())),
					error -> finish(sink, finished, recording, sessionId, new AgentEvent.Failed(error)));

		sink.onCancel(() -> cancelledByConsumer.set(true));

		// onDispose covers every exit: completion, error, and cancellation.
		sink.onDispose(() -> {
			router.unregister(sessionId);
			if (cancelledByConsumer.get() && !finished.get()) {
				cancelRemoteTurn(sessionId);
				// The consumer is gone, so no terminal event can be delivered to it — but
				// something
				// is still counting, and a turn that vanished from the metrics would be
				// the one
				// worth knowing about.
				if (finished.compareAndSet(false, true)) {
					recording.failed("cancelled", null);
				}
			}
			subscription.dispose();
		});
	}

	/**
	 * Emits the turn's one terminal event, after taking the session out of the router.
	 *
	 * <p>
	 * The order of those two is load-bearing, and getting it wrong is a race that only
	 * shows up under a fast agent. {@code FluxCreate} runs the downstream's
	 * {@code onComplete} before it runs the sink's {@code onDispose} — so a caller
	 * blocking on this turn is released, and can start the next one, while the
	 * registration for this turn is still in the router. The next turn's {@code register}
	 * then finds the session occupied and fails with "already has an active turn", for a
	 * turn that had finished. Unregistering before the sink completes closes that window;
	 * {@code onDispose} still unregisters, for the cancellation path, and the second call
	 * is a no-op.
	 *
	 * <p>
	 * Nothing is lost by unregistering first: an update arriving after the turn's stop
	 * reason does not belong to this turn, and was already being dropped a microsecond
	 * later.
	 */
	private void finish(FluxSink<AgentEvent> sink, AtomicBoolean finished, AgentObservations.TurnRecording recording,
			String sessionId, AgentEvent terminal) {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		router.unregister(sessionId);
		if (terminal instanceof AgentEvent.Failed failed) {
			logger.debug("Turn failed", failed.cause());
			recording.failed(reasonOf(failed.cause()), failed.cause());
		}
		else if (terminal instanceof AgentEvent.Completed completed) {
			recording.completed(completed.reason() == null ? null : completed.reason().name());
		}
		sink.next(terminal);
		sink.complete();
	}

	/**
	 * Feeds the recording everything it counts, leaving the events themselves untouched.
	 */
	private static void record(AgentObservations.TurnRecording recording, AgentEvent event) {
		switch (event) {
			case AgentEvent.ToolCallStarted started -> recording.toolCallStarted(started.id(), started.title(),
					started.kind() == null ? null : started.kind().name());
			case AgentEvent.ToolCallUpdated updated ->
				recording.toolCallUpdated(updated.id(), updated.status() == null ? null : updated.status().name());
			case AgentEvent.UsageUpdated usage ->
				recording.usage(usage.contextUsed(), usage.contextSize(), usage.costAmount(), usage.costCurrency());
			default -> {
			}
		}
	}

	/**
	 * A low-cardinality name for why a turn failed.
	 *
	 * <p>
	 * An exception message names a session id, a model or a path, and a tag that carries
	 * one turns a counter into a leak. The class name is the fact worth grouping by.
	 */
	private static String reasonOf(Throwable cause) {
		return cause == null ? "unknown" : cause.getClass().getSimpleName();
	}

	/**
	 * Tells the agent to stop work the consumer is no longer listening to.
	 * Fire-and-forget by necessity — the subscriber is already gone, so there is nobody
	 * left to report an error to — but it must be sent before the turn is torn down, or
	 * the agent keeps burning tokens.
	 */
	private void cancelRemoteTurn(String sessionId) {
		client.cancel(new AcpSchema.CancelNotification(sessionId))
			.subscribe(null, ex -> logger.debug("Failed to cancel session {}", sessionId, ex));
	}

}
