package org.springaicommunity.acp.console;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.client.AgentClient;
import org.springaicommunity.acp.config.SessionConfiguration;
import org.springaicommunity.acp.event.AgentEvent;
import org.springaicommunity.acp.runtime.AgentNotice;
import org.springaicommunity.acp.session.AgentSession;
import org.springaicommunity.acp.session.AgentSessions;
import org.springaicommunity.acp.session.StoredSession;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The loop against a stubbed agent and a scripted terminal: no agent process, no real
 * tty.
 */
class ConsoleChatTests {

	@TempDir
	Path temp;

	private final AgentClient agent = mock(AgentClient.class);

	private final AgentClient.PromptSpec prompt = mock(AgentClient.PromptSpec.class, RETURNS_SELF);

	private final AgentClient.AgentStream stream = mock(AgentClient.AgentStream.class);

	private final AgentSessions sessions = mock(AgentSessions.class);

	private final AtomicBoolean exited = new AtomicBoolean();

	@BeforeEach
	void stubTheAgent() {
		AgentSession session = mock(AgentSession.class);
		when(session.configuration()).thenReturn(SessionConfiguration.empty());
		when(agent.runtimeId()).thenReturn("scripted");
		when(agent.openSession(anyString())).thenReturn(session);
		when(agent.sessions()).thenReturn(sessions);
		when(agent.notices()).thenReturn(List.of());
		when(agent.prompt()).thenReturn(prompt);
		when(prompt.stream()).thenReturn(stream);
		when(stream.events()).thenReturn(Flux.just(new AgentEvent.Completed(AcpSchema.StopReason.END_TURN)));
	}

	@Test
	void streamsATurnInTheConfiguredSessionAndLeavesOnExit() {
		when(stream.events())
			.thenReturn(Flux.just(new AgentEvent.ToolCallStarted("1", "Read the cost report", AcpSchema.ToolKind.READ),
					new AgentEvent.Text("Spend is "), new AgentEvent.Text("up 4%."),
					new AgentEvent.Completed(AcpSchema.StopReason.END_TURN)));
		TestTerminals.Scripted terminal = TestTerminals.typing("what changed?\n/exit\n");

		chat(terminal, "Meridian", "Ask about costs.").run();

		verify(agent).openSession("console");
		verify(prompt).session("console");
		verify(prompt).user("what changed?");
		assertThat(terminal.written()).contains("Meridian", "runtime: scripted", "Ask about costs.",
				"→ Read the cost report", "Spend is up 4%.", "bye");
	}

	@Test
	void endOfInputLeavesAsExitDoes() {
		TestTerminals.Scripted terminal = TestTerminals.typing("");

		chat(terminal).run();

		verify(agent, never()).prompt();
		assertThat(terminal.written()).contains("bye");
	}

	@Test
	void proseReachesTheAgentExactlyAsTyped() {
		TestTerminals.Scripted terminal = TestTerminals.typing("what's in C:\\temp? \"quoted\" too!\n");

		chat(terminal).run();

		verify(prompt).user("what's in C:\\temp? \"quoted\" too!");
	}

	@Test
	void aFailedToolCallIsReportedByItsTitle() {
		when(stream.events()).thenReturn(Flux.just(new AgentEvent.ToolCallStarted("t1", "Query FinOps", null),
				new AgentEvent.ToolCallUpdated("t1", AcpSchema.ToolCallStatus.FAILED, List.of()),
				new AgentEvent.Completed(AcpSchema.StopReason.END_TURN)));
		TestTerminals.Scripted terminal = TestTerminals.typing("go\n");

		chat(terminal).run();

		assertThat(terminal.written()).contains("✗ Query FinOps failed");
	}

	@Test
	void aTurnThatFailsIsReportedAndTheConversationCarriesOn() {
		when(stream.events()).thenReturn(Flux.just(new AgentEvent.Failed(new IllegalStateException("agent went away"))))
			.thenReturn(
					Flux.just(new AgentEvent.Text("back"), new AgentEvent.Completed(AcpSchema.StopReason.END_TURN)));
		TestTerminals.Scripted terminal = TestTerminals.typing("one\ntwo\n");

		chat(terminal).run();

		assertThat(terminal.written()).contains("! agent went away", "back");
	}

	@Test
	void anAgentThatCannotStartIsReportedAndThePromptStillAppears() {
		when(agent.openSession("console")).thenThrow(new IllegalStateException("goose is not installed"));
		TestTerminals.Scripted terminal = TestTerminals.typing("");

		chat(terminal).run();

		assertThat(terminal.written()).contains("! goose is not installed", "you ›");
	}

	@Test
	void cancellingDisposesTheTurnInFlight() throws Exception {
		CountDownLatch subscribed = new CountDownLatch(1);
		AtomicBoolean cancelled = new AtomicBoolean();
		when(stream.events()).thenReturn(Flux.<AgentEvent>never()
			.doOnSubscribe(s -> subscribed.countDown())
			.doOnCancel(() -> cancelled.set(true)));
		TestTerminals.Scripted terminal = TestTerminals.typing("take your time\n");
		ConsoleChat chat = chat(terminal);

		Thread loop = new Thread(chat::run);
		loop.start();
		assertThat(subscribed.await(30, TimeUnit.SECONDS)).isTrue();
		chat.cancel();
		loop.join(5000);

		assertThat(loop.isAlive()).isFalse();
		assertThat(cancelled).isTrue();
		assertThat(terminal.written()).contains("[cancelled]", "bye");
	}

	@Test
	void cancellingAsTheTurnStartsIsNotLost() throws Exception {
		// Cancels from inside subscribe(), before it has returned the subscription: the
		// narrowest window a Ctrl-C can land in, and one a slow machine widens.
		AtomicReference<ConsoleChat> chat = new AtomicReference<>();
		AtomicBoolean cancelled = new AtomicBoolean();
		when(stream.events()).thenReturn(
				Flux.<AgentEvent>never().doOnSubscribe(s -> chat.get().cancel()).doOnCancel(() -> cancelled.set(true)));
		TestTerminals.Scripted terminal = TestTerminals.typing("take your time\n");
		chat.set(chat(terminal));

		Thread loop = new Thread(chat.get()::run);
		loop.start();
		loop.join(5000);

		assertThat(loop.isAlive()).isFalse();
		assertThat(cancelled).isTrue();
		assertThat(terminal.written()).contains("[cancelled]", "bye");
	}

	@Test
	void newStartsAFreshNamedSessionAndClosesTheOldOneWhereTheAgentCan() {
		when(sessions.supports(AgentSessions.Operation.CLOSE)).thenReturn(true);
		TestTerminals.Scripted terminal = TestTerminals.typing("/new\nhello again\n");

		ConsoleChat chat = chat(terminal);
		chat.run();

		verify(agent).openSession("console-1");
		verify(sessions).close("console");
		verify(prompt).session("console-1");
		assertThat(chat.session()).isEqualTo("console-1");
	}

	@Test
	void sessionsSaysWhichMethodIsMissingWhenTheAgentCannotList() {
		TestTerminals.Scripted terminal = TestTerminals.typing("/sessions\n");

		chat(terminal).run();

		assertThat(terminal.written()).contains("scripted cannot list its conversations (no session/list)");
	}

	@Test
	void sessionsListsWhatTheAgentStored() {
		when(sessions.supports(AgentSessions.Operation.LIST)).thenReturn(true);
		when(sessions.list()).thenReturn(List.of(new StoredSession("sess-42", temp, "Q3 capacity review", null)));
		when(agent.session("console")).thenReturn(Optional.empty());
		TestTerminals.Scripted terminal = TestTerminals.typing("/sessions\n");

		chat(terminal).run();

		assertThat(terminal.written()).contains("sess-42", "Q3 capacity review");
	}

	@Test
	void resumeLoadsWhenTheAgentCannotResume() {
		when(sessions.supports(AgentSessions.Operation.LOAD)).thenReturn(true);
		TestTerminals.Scripted terminal = TestTerminals.typing("/resume sess-42\nwhere were we?\n");

		chat(terminal).run();

		verify(sessions).load("console-1", "sess-42");
		verify(prompt).session("console-1");
	}

	@Test
	void resumeWithoutAnIdSaysHow() {
		TestTerminals.Scripted terminal = TestTerminals.typing("/resume\n");

		chat(terminal).run();

		verify(sessions, never()).load(anyString(), anyString());
		assertThat(terminal.written()).contains("usage: /resume <id>");
	}

	@Test
	void anUnknownCommandIsNotSentToTheAgent() {
		TestTerminals.Scripted terminal = TestTerminals.typing("/frobnicate\n");

		chat(terminal).run();

		verify(agent, never()).prompt();
		assertThat(terminal.written()).contains("unknown command /frobnicate");
	}

	@Test
	void eachNoticeFromTheAgentIsShownOnce() {
		when(agent.notices()).thenReturn(List.of(AgentNotice.error("finops-mcp", "could not connect")));
		TestTerminals.Scripted terminal = TestTerminals.typing("one\ntwo\n");

		chat(terminal).run();

		String written = terminal.written();
		assertThat(written).contains("finops-mcp: could not connect");
		assertThat(written.indexOf("could not connect")).isEqualTo(written.lastIndexOf("could not connect"));
	}

	@Test
	void usageIsSummarisedOnceWhenTheTurnEnds() {
		when(stream.events()).thenReturn(Flux.just(new AgentEvent.UsageUpdated(1000, 200_000, null, null),
				new AgentEvent.UsageUpdated(12_345, 200_000, 0.0123, "USD"), new AgentEvent.Text("done"),
				new AgentEvent.Completed(AcpSchema.StopReason.END_TURN)));
		TestTerminals.Scripted terminal = TestTerminals.typing("go\n");

		chat(terminal).run();

		assertThat(terminal.written()).contains("context 12.3k / 200.0k (6%) tokens  ·  0.0123 USD")
			.doesNotContain("context 1.0k");
	}

	@Test
	void aStartedConsoleRunsOnItsOwnThreadAndClosesTheApplicationWhenTheUserLeaves() throws Exception {
		TestTerminals.Scripted terminal = TestTerminals.typing("/exit\n");
		ConsoleChat chat = chat(terminal);

		chat.onApplicationEvent(null);
		long deadline = System.currentTimeMillis() + 5000;
		while (!exited.get() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}

		assertThat(exited).isTrue();
	}

	private ConsoleChat chat(TestTerminals.Scripted terminal) {
		return chat(terminal, "Test agent", null);
	}

	private ConsoleChat chat(TestTerminals.Scripted terminal, String title, String greeting) {
		return new ConsoleChat(agent, terminal.terminal(), new ConsoleRenderer(terminal.terminal(), true), title,
				greeting, "console", temp.resolve("history"), () -> exited.set(true));
	}

}
