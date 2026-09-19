package org.tanzu.acp.turn;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.session.AgentSession;
import org.tanzu.acp.session.SessionRegistry;

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

	private static AcpSchema.SessionUpdate chunk(String text) {
		return new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent(text));
	}
}
