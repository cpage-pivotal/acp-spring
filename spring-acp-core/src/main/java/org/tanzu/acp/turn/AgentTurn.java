package org.tanzu.acp.turn;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.event.AgentEventMapper;
import org.tanzu.acp.session.AgentSession;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Runs one prompt turn and publishes it as a {@link Flux} of {@link AgentEvent}.
 *
 * <p>The contract this class exists to uphold: <strong>every turn emits exactly one terminal
 * event.</strong> Normal completion, an RPC error, a timeout, a dropped transport, or a consumer
 * that walks away — all five end with {@link AgentEvent.Completed} or {@link AgentEvent.Failed} and
 * nothing after it. Consumers switch over {@code AgentEvent} without a default branch, so a turn
 * that ended silently would hang them.
 *
 * <p>Streamed content and the turn's outcome arrive on two independent channels — the router and
 * the {@code prompt()} Mono respectively — and both feed the same {@link FluxSink}, which
 * serializes them. The sink is the join point.
 */
public final class AgentTurn {

	private static final Logger logger = LoggerFactory.getLogger(AgentTurn.class);

	private final AcpAsyncClient client;

	private final SessionUpdateRouter router;

	private AgentTurn(AcpAsyncClient client, SessionUpdateRouter router) {
		this.client = client;
		this.router = router;
	}

	public static AgentTurn on(AcpAsyncClient client, SessionUpdateRouter router) {
		return new AgentTurn(client, router);
	}

	/**
	 * Prompts {@code session} and streams the result.
	 *
	 * <p>Cold: nothing is sent until the returned Flux is subscribed, and each subscription would
	 * run its own turn. Callers hold the session's turn permit around a single subscription.
	 */
	public Flux<AgentEvent> prompt(AgentSession session, List<AcpSchema.ContentBlock> content, Duration timeout) {
		return Flux.create(sink -> start(sink, session, content, timeout), FluxSink.OverflowStrategy.BUFFER);
	}

	private void start(FluxSink<AgentEvent> sink, AgentSession session, List<AcpSchema.ContentBlock> content,
			Duration timeout) {
		String sessionId = session.sessionId();

		// Guards the terminal contract. Both channels race to finish the turn; the first one wins
		// and the other becomes a no-op.
		AtomicBoolean finished = new AtomicBoolean();
		// Distinguishes "the consumer walked away" from "the turn ended on its own", so we only
		// send session/cancel in the former case.
		AtomicBoolean cancelledByConsumer = new AtomicBoolean();

		try {
			router.register(sessionId, update -> AgentEventMapper.map(update).ifPresent(sink::next));
		}
		catch (RuntimeException ex) {
			sink.next(new AgentEvent.Failed(ex));
			sink.complete();
			return;
		}

		Disposable subscription = client.prompt(new AcpSchema.PromptRequest(sessionId, content)).timeout(timeout)
				.subscribe(response -> finish(sink, finished, new AgentEvent.Completed(response.stopReason())),
						error -> finish(sink, finished, new AgentEvent.Failed(error)));

		sink.onCancel(() -> cancelledByConsumer.set(true));

		// onDispose covers every exit: completion, error, and cancellation.
		sink.onDispose(() -> {
			router.unregister(sessionId);
			if (cancelledByConsumer.get() && !finished.get()) {
				cancelRemoteTurn(sessionId);
			}
			subscription.dispose();
		});
	}

	private void finish(FluxSink<AgentEvent> sink, AtomicBoolean finished, AgentEvent terminal) {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		if (terminal instanceof AgentEvent.Failed failed) {
			logger.debug("Turn failed", failed.cause());
		}
		sink.next(terminal);
		sink.complete();
	}

	/**
	 * Tells the agent to stop work the consumer is no longer listening to. Fire-and-forget by
	 * necessity — the subscriber is already gone, so there is nobody left to report an error to —
	 * but it must be sent before the turn is torn down, or the agent keeps burning tokens.
	 */
	private void cancelRemoteTurn(String sessionId) {
		client.cancel(new AcpSchema.CancelNotification(sessionId))
				.subscribe(null, ex -> logger.debug("Failed to cancel session {}", sessionId, ex));
	}
}
