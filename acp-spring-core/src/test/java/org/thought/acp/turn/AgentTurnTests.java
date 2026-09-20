package org.thought.acp.turn;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thought.acp.event.AgentEvent;
import org.thought.acp.observation.AgentObservations;
import org.thought.acp.session.AgentSession;
import org.thought.acp.session.SessionRegistry;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The turn contract, exercised without a subprocess.
 *
 * <p>Every test here asserts the same invariant from a different angle: a turn emits exactly one
 * terminal event and nothing after it. Consumers switch over {@code AgentEvent} with no default
 * branch, so a turn that ended silently would hang them, and a turn that ended twice would emit
 * after completion.
 */
class AgentTurnTests {

	private static final List<AcpSchema.ContentBlock> PROMPT = List.of(new AcpSchema.TextContent("hi"));

	private AcpAsyncClient client;

	private SessionUpdateRouter router;

	private AgentSession session;

	@BeforeEach
	void setUp() {
		client = mock(AcpAsyncClient.class);
		router = new SessionUpdateRouter();
		session = new SessionRegistry().resolve("s", name -> "sid-1");
	}

	/** Lets a test drive the two channels independently, as a real agent does. */
	private void agentStreams(List<AcpSchema.SessionUpdate> updates, AcpSchema.StopReason stopReason) {
		when(client.prompt(any())).thenReturn(Mono.fromCallable(() -> {
			updates.forEach(u -> router.accept(new AcpSchema.SessionNotification("sid-1", u)));
			return new AcpSchema.PromptResponse(stopReason);
		}));
	}

	@Test
	void streamsContentThenExactlyOneTerminalEvent() {
		agentStreams(List.of(chunk("one"), chunk("two")), AcpSchema.StopReason.END_TURN);

		List<AgentEvent> events = AgentTurn.on(client, router).prompt(session, PROMPT, Duration.ofSeconds(5))
				.collectList().block(Duration.ofSeconds(5));

		assertThat(events).containsExactly(new AgentEvent.Text("one"), new AgentEvent.Text("two"),
				new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));
		assertThat(events.stream().filter(AgentEvent::terminal)).hasSize(1);
	}

	@Test
	void anRpcErrorBecomesFailedRatherThanAnEmptyStream() {
		when(client.prompt(any())).thenReturn(Mono.error(new IllegalStateException("agent exploded")));

		StepVerifier.create(AgentTurn.on(client, router).prompt(session, PROMPT, Duration.ofSeconds(5)))
				.assertNext(event -> assertThat(event).isInstanceOfSatisfying(AgentEvent.Failed.class,
						f -> assertThat(f.cause()).hasMessage("agent exploded")))
				.verifyComplete();
	}

	@Test
	void aTimeoutStillProducesATerminalEvent() {
		when(client.prompt(any())).thenReturn(Mono.never());

		StepVerifier.create(AgentTurn.on(client, router).prompt(session, PROMPT, Duration.ofMillis(100)))
				.assertNext(event -> assertThat(event).isInstanceOf(AgentEvent.Failed.class))
				.verifyComplete();
	}

	@Test
	void cancellingTheStreamCancelsTheTurnOnTheAgent() {
		AtomicBoolean cancelled = new AtomicBoolean();
		when(client.prompt(any())).thenReturn(Mono.never());
		when(client.cancel(any())).thenReturn(Mono.fromRunnable(() -> cancelled.set(true)));

		StepVerifier.create(AgentTurn.on(client, router).prompt(session, PROMPT, Duration.ofSeconds(30))).thenAwait()
				.thenCancel().verify(Duration.ofSeconds(5));

		assertThat(cancelled).isTrue();
	}

	@Test
	void aTurnThatEndedNormallyIsNotCancelledOnTheAgent() {
		AtomicBoolean cancelled = new AtomicBoolean();
		agentStreams(List.of(), AcpSchema.StopReason.END_TURN);
		when(client.cancel(any())).thenReturn(Mono.fromRunnable(() -> cancelled.set(true)));

		AgentTurn.on(client, router).prompt(session, PROMPT, Duration.ofSeconds(5)).blockLast(Duration.ofSeconds(5));

		assertThat(cancelled).isFalse();
	}

	@Test
	void theSessionIsReleasedForAFollowingTurn() {
		agentStreams(List.of(), AcpSchema.StopReason.END_TURN);
		AgentTurn turn = AgentTurn.on(client, router);

		turn.prompt(session, PROMPT, Duration.ofSeconds(5)).blockLast(Duration.ofSeconds(5));
		turn.prompt(session, PROMPT, Duration.ofSeconds(5)).blockLast(Duration.ofSeconds(5));

		assertThat(router.activeTurns()).isZero();
	}

	@Test
	void updatesForAnotherSessionAreNotDeliveredHere() {
		when(client.prompt(any())).thenReturn(Mono.fromCallable(() -> {
			router.accept(new AcpSchema.SessionNotification("someone-else", chunk("not yours")));
			router.accept(new AcpSchema.SessionNotification("sid-1", chunk("yours")));
			return new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN);
		}));

		List<AgentEvent> events = AgentTurn.on(client, router).prompt(session, PROMPT, Duration.ofSeconds(5))
				.collectList().block(Duration.ofSeconds(5));

		assertThat(events).containsExactly(new AgentEvent.Text("yours"),
				new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));
	}

	@Test
	void releasesTheSessionInTheRouterBeforeTheCallerCanStartTheNextTurn() {
		// FluxCreate runs the downstream's onComplete before the sink's onDispose, so a caller
		// blocking on a turn is released while that turn's router registration could still be in
		// place. The next turn then fails with "already has an active turn" — for a turn that had
		// finished. Found by a live WebSocket test failing about one run in ten.
		agentStreams(List.of(), AcpSchema.StopReason.END_TURN);
		AgentTurn turn = AgentTurn.on(client, router);
		AtomicBoolean registeredAfterFirstTurn = new AtomicBoolean(true);

		turn.prompt(session, PROMPT, Duration.ofSeconds(5))
				.doOnComplete(() -> registeredAfterFirstTurn.set(router.activeTurns() > 0))
				.blockLast(Duration.ofSeconds(5));

		assertThat(registeredAfterFirstTurn).isFalse();
	}

	@Test
	void reportsTheTurnAndItsToolCallsToTheObservations() {
		agentStreams(List.of(chunk("working"),
				new AcpSchema.ToolCall("tool_call", "t1", "Read the build file", AcpSchema.ToolKind.READ, null,
						null, null, null, null, null),
				new AcpSchema.ToolCallUpdateNotification("tool_call_update", "t1", null, null,
						AcpSchema.ToolCallStatus.COMPLETED, null, null, null, null, null),
				new AcpSchema.UsageUpdate("usage_update", 4441L, 1_050_000L, null, null)),
				AcpSchema.StopReason.END_TURN);
		RecordingObservations observations = new RecordingObservations();

		AgentTurn.on(client, router, observations)
				.prompt(session, PROMPT, Duration.ofSeconds(5),
						new AgentObservations.TurnContext("goose", "s", "gpt-5.4-mini", false))
				.blockLast(Duration.ofSeconds(5));

		assertThat(observations.events).containsExactly("started goose/s", "tool started t1 READ",
				"tool COMPLETED t1", "usage 4441/1050000", "completed END_TURN");
	}

	@Test
	void aTurnTheConsumerAbandonedIsStillReported() {
		// The subscriber is gone, so no terminal event reaches it — but something is counting, and a
		// turn that vanished from the metrics is exactly the one worth knowing about.
		when(client.prompt(any())).thenReturn(Mono.never());
		when(client.cancel(any())).thenReturn(Mono.empty());
		RecordingObservations observations = new RecordingObservations();

		AgentTurn.on(client, router, observations)
				.prompt(session, PROMPT, Duration.ofSeconds(5),
						new AgentObservations.TurnContext("goose", "s", null, true))
				.subscribe().dispose();

		assertThat(observations.events).containsExactly("started goose/s", "failed cancelled");
	}

	@Test
	void observationsAreOptionalAndTheirAbsenceChangesNothing() {
		agentStreams(List.of(chunk("one")), AcpSchema.StopReason.END_TURN);

		List<AgentEvent> events = AgentTurn.on(client, router, null).prompt(session, PROMPT, Duration.ofSeconds(5))
				.collectList().block(Duration.ofSeconds(5));

		assertThat(events).containsExactly(new AgentEvent.Text("one"),
				new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));
	}

	/** Records the calls rather than the meters; what Micrometer does with them is its own test. */
	private static final class RecordingObservations implements AgentObservations {

		private final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

		@Override
		public TurnRecording turnStarted(TurnContext context) {
			events.add("started " + context.runtimeId() + "/" + context.sessionName());
			return new TurnRecording() {

				@Override
				public void toolCallStarted(String id, String title, String kind) {
					events.add("tool started " + id + " " + kind);
				}

				@Override
				public void toolCallUpdated(String id, String status) {
					events.add("tool " + status + " " + id);
				}

				@Override
				public void usage(long used, long size, Double amount, String currency) {
					events.add("usage " + used + "/" + size);
				}

				@Override
				public void completed(String stopReason) {
					events.add("completed " + stopReason);
				}

				@Override
				public void failed(String reason, Throwable cause) {
					events.add("failed " + reason);
				}
			};
		}
	}

	private static AcpSchema.SessionUpdate chunk(String text) {
		return new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent(text));
	}
}
