package org.tanzu.acp.test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentClientFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.McpServerSpec;
import org.tanzu.acp.config.OnUnsupported;
import org.tanzu.acp.config.OptionResolution.Mechanism;
import org.tanzu.acp.config.UnsupportedAgentOptionException;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.runtime.AgentRuntime.PortableOption;
import org.tanzu.acp.session.AgentSessions;
import org.tanzu.acp.session.StoredSession;
import org.tanzu.acp.session.UnsupportedAgentOperationException;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against a real JSON-RPC wire, with no subprocess.
 *
 * <p>What this covers that the core's unit tests cannot: those mock {@code AcpAsyncClient}, so they
 * prove the turn and resolution logic but never the format. Both of the acp-core gaps this library
 * works around are format gaps, invisible to a mock and reproducible here.
 */
class AgentClientWireTests {

	@TempDir
	Path workspace;

	private static final Duration LIMIT = Duration.ofSeconds(10);

	private AgentSettings.Builder settings() {
		return AgentSettings.builder("scripted", workspace).timeout(LIMIT);
	}

	private AgentClient connect(ScriptedAgent agent, AgentSettings settings) {
		return AgentClientFactory.connect(new ScriptedRuntime(), settings, agent.transport());
	}

	// --- the two SDK gaps -------------------------------------------------------------------

	@Test
	void recoversTheConfigOptionsThatNewSessionResponseDrops() {
		// The SDK's NewSessionResponse models sessionId, modes and models and ignores the rest, so
		// this field is dropped before a client could read it. Without it the resolver cannot tell a
		// model the agent has from one it does not.
		try (ScriptedAgent agent = ScriptedAgent.builder().select("model", "model", "a", "a", "b").build();
				AgentClient client = connect(agent, settings().model("b").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(client.session("s")).get()
					.satisfies(session -> assertThat(session.advertised().configOptions()).hasSize(1));
			assertThat(agent.configSets()).containsExactly(Map.of("configId", "model", "value", "b"));
		}
	}

	@Test
	void aSessionUpdateVariantThisSdkCannotModelIsSkippedRatherThanFatal() {
		// goose emits session_info_update several times a turn and acp-core has no record for it. The
		// SDK's own sessionUpdateConsumer would log an ERROR per occurrence; this client registers a
		// raw handler and decodes leniently, so the unknown one is dropped and the turn is unaffected.
		try (ScriptedAgent agent = ScriptedAgent.builder().emitsUnknownUpdate(true).reply("one", "two").build();
				AgentClient client = connect(agent, settings().build())) {

			List<AgentEvent> events = client.prompt("hi").stream().events().collectList().block(LIMIT);

			assertThat(events).containsExactly(new AgentEvent.Text("one"), new AgentEvent.Text("two"),
					new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));
		}
	}

	// --- the negotiated tier over the wire --------------------------------------------------

	@Test
	void anAgentThatAcceptsAnythingStillCannotGetAnUnknownModelPastTheResolver() {
		// The goose 1.51 behavior that makes "set it and see" unworkable.
		try (ScriptedAgent agent = ScriptedAgent.builder().select("model", "model", "a", "a")
				.acceptsUnknownValues(true).build();
				AgentClient client = connect(agent, settings().model("made-up").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.configSets()).isEmpty();
			assertThat(client.session("s")).get().satisfies(session -> assertThat(
					session.configuration().of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.UNSUPPORTED));
		}
	}

	@Test
	void onUnsupportedFailStopsTheTurnBeforeItStarts() {
		try (ScriptedAgent agent = ScriptedAgent.builder().select("model", "model", "a", "a").build();
				AgentClient client = connect(agent,
						settings().model("made-up").onUnsupported(OnUnsupported.FAIL).build())) {

			assertThatThrownBy(() -> client.prompt("hi").call())
					.isInstanceOf(UnsupportedAgentOptionException.class);
			assertThat(agent.prompts()).isZero();
		}
	}

	@Test
	void setModeIsUsedWhenTheAgentOffersModesButNoModeOption() {
		try (ScriptedAgent agent = ScriptedAgent.builder().modes("auto", "plan").build();
				AgentClient client = connect(agent, settings().mode("plan").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.methods()).contains("session/set_mode");
			assertThat(client.session("s")).get().satisfies(session -> assertThat(
					session.configuration().of(PortableOption.MODE).mechanism()).isEqualTo(Mechanism.SESSION_MODE));
		}
	}

	@Test
	void providersSetIsUsedWhenTheAgentAdvertisesTheCapability() {
		try (ScriptedAgent agent = ScriptedAgent.builder().providers("openai").build();
				AgentClient client = connect(agent, settings().provider("openai").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.providerSets()).hasSize(1);
			assertThat(client.session("s")).get().satisfies(session -> assertThat(
					session.configuration().of(PortableOption.PROVIDER).mechanism())
					.isEqualTo(Mechanism.PROVIDERS_SET));
		}
	}

	@Test
	void aProviderIsNotAttemptedOverTheWireWhenTheAgentDoesNotAdvertiseIt() {
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, settings().provider("openai").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.methods()).doesNotContain("providers/set").doesNotContain("providers/list");
		}
	}

	// --- the portable tier ------------------------------------------------------------------

	@Test
	void mcpServersReachSessionNewVerbatim() {
		McpServerSpec server = new McpServerSpec.Http("tools", java.net.URI.create("https://tools.example.com/mcp"),
				Map.of("Authorization", "Bearer secret"));

		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, settings().mcpServers(List.of(server)).build())) {

			client.prompt("hi").call();

			assertThat(agent.newSessions()).hasSize(1);
			assertThat(agent.newSessions().get(0).mcpServers()).hasSize(1)
					.first(org.assertj.core.api.InstanceOfAssertFactories.type(AcpSchema.McpServerHttp.class))
					.satisfies(http -> {
						assertThat(http.name()).isEqualTo("tools");
						assertThat(http.url()).isEqualTo("https://tools.example.com/mcp");
					});
			assertThat(agent.newSessions().get(0).cwd()).isEqualTo(workspace.toString());
		}
	}

	@Test
	void toolCallsAreReportedAsEventsWithTheirIdentifiers() {
		try (ScriptedAgent agent = ScriptedAgent.builder().toolCall("developer__shell").reply("done").build();
				AgentClient client = connect(agent, settings().build())) {

			List<AgentEvent> events = client.prompt("hi").stream().events().collectList().block(LIMIT);

			assertThat(events).anySatisfy(event -> assertThat(event)
					.isInstanceOfSatisfying(AgentEvent.ToolCallStarted.class,
							started -> assertThat(started.title()).isEqualTo("developer__shell")));
			assertThat(events).last().isInstanceOf(AgentEvent.Completed.class);
		}
	}

	@Test
	void cancellingTheStreamSendsSessionCancel() {
		try (ScriptedAgent agent = ScriptedAgent.builder().promptDelay(Duration.ofSeconds(30)).build();
				AgentClient client = connect(agent, settings().build())) {

			StepVerifier.create(client.prompt("hi").stream().events()).thenAwait(Duration.ofMillis(200)).thenCancel()
					.verify(LIMIT);

			// Fire-and-forget by necessity: the subscriber is already gone, so poll briefly.
			Awaits.until(() -> agent.cancellations() == 1, LIMIT);
		}
	}

	@Test
	void aNamedSessionIsOpenedOnceAndReusedAcrossTurns() {
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, settings().build())) {

			client.prompt().session("reused").user("one").call();
			client.prompt().session("reused").user("two").call();

			assertThat(agent.newSessions()).hasSize(1);
			assertThat(agent.prompts()).isEqualTo(2);
		}
	}

	@Test
	void anEphemeralSessionIsClosedWhenItsTurnEnds() {
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, settings().build())) {

			client.prompt("hi").call();

			Awaits.until(() -> agent.methods().contains("session/close"), LIMIT);
		}
	}

	@Test
	void anEphemeralTurnDoesNotWaitOnItsOwnCleanup() {
		// A turn's teardown runs on the thread that delivers the agent's replies, so waiting there for
		// a session/close acknowledgement deadlocks until the timeout expires. Nothing fails when it
		// does, which is why this is asserted on the clock: three scripted turns take milliseconds, and
		// the bug made each of them take five seconds.
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, settings().build())) {

			org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
				client.prompt("one").call();
				client.prompt("two").call();
				client.prompt("three").call();
			});
		}
	}

	// --- session operations -----------------------------------------------------------------

	@Test
	void anAgentThatAdvertisesNoSessionOperationsIsRefusedByNameRatherThanOnTheWire() {
		// Every one of these is optional in ACP and the three real runtimes implement different
		// subsets, so "not here" has to be a typed answer rather than a JSON-RPC error code.
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, settings().build())) {

			AgentSessions sessions = client.sessions();

			assertThat(sessions.supports(AgentSessions.Operation.LIST)).isFalse();
			assertThatThrownBy(sessions::list).isInstanceOf(UnsupportedAgentOperationException.class);
			assertThatThrownBy(() -> sessions.load("a", "sid-1"))
					.isInstanceOf(UnsupportedAgentOperationException.class);
			assertThatThrownBy(() -> sessions.delete("sid-1"))
					.isInstanceOf(UnsupportedAgentOperationException.class);
			assertThat(agent.methods()).doesNotContain("session/list", "session/load", "session/delete");
		}
	}

	@Test
	void listingFollowsTheAgentsCursorToTheEnd() {
		try (ScriptedAgent agent = ScriptedAgent.builder().sessionOperations("list")
				.storedSessions("sid-1", "sid-2", "sid-3").build();
				AgentClient client = connect(agent, settings().build())) {

			assertThat(client.sessions().list()).extracting(StoredSession::sessionId)
					.containsExactly("sid-1", "sid-2", "sid-3");
			assertThat(client.sessions().supports(AgentSessions.Operation.LIST)).isTrue();
		}
	}

	/**
	 * {@code LoadSessionResponse} drops {@code configOptions} exactly as {@code NewSessionResponse}
	 * does, and with one extra difficulty: the response does not name its session, so there is
	 * nothing to key the recovered options on. A loaded session that can still negotiate its model
	 * is the proof that the claim-immediately arrangement works.
	 */
	@Test
	void aLoadedSessionStillNegotiatesItsModel() {
		try (ScriptedAgent agent = ScriptedAgent.builder().sessionOperations("load", "list")
				.storedSessions("sid-1").select("model", "model", "a", "a", "b").build();
				AgentClient client = connect(agent, settings().model("b").build())) {

			client.sessions().load("restored", "sid-1");

			assertThat(agent.loaded()).containsExactly("sid-1");
			assertThat(client.session("restored")).get()
					.extracting(session -> session.configuration().of(PortableOption.MODEL).applied())
					.isEqualTo("b");
			assertThat(agent.configSets()).contains(Map.of("configId", "model", "value", "b"));
		}
	}

	@Test
	void aResumedSessionIsBoundWithoutReplayingHistory() {
		try (ScriptedAgent agent = ScriptedAgent.builder().sessionOperations("resume")
				.storedSessions("sid-1").build();
				AgentClient client = connect(agent, settings().build())) {

			assertThat(client.sessions().resume("restored", "sid-1").sessionId()).isEqualTo("sid-1");
			assertThat(agent.methods()).contains("session/resume").doesNotContain("session/load");
		}
	}

	@Test
	void deletingRemovesTheSessionFromTheAgentAndTheNameFromTheClient() {
		try (ScriptedAgent agent = ScriptedAgent.builder().sessionOperations("load", "delete", "list")
				.storedSessions("sid-1", "sid-2").build();
				AgentClient client = connect(agent, settings().build())) {
			client.sessions().load("restored", "sid-1");

			client.sessions().delete("sid-1");

			assertThat(agent.storedSessions()).containsExactly("sid-2");
			assertThat(client.session("restored")).isEmpty();
		}
	}

	@Test
	void theAgentsOwnVersionSurvivesTheHandshake() {
		try (ScriptedAgent agent = ScriptedAgent.builder().agentInfo("pretend", "9.9.9").build();
				AgentClient client = connect(agent, settings().build())) {

			assertThat(client.agentInfo()).get().hasToString("pretend 9.9.9");
		}
	}

	/** A runtime that names no agent: only the core is under test here. */
	private static final class ScriptedRuntime implements AgentRuntime {

		@Override
		public String id() {
			return "scripted";
		}

		@Override
		public AgentLaunchSpec launch(AgentSettings settings) {
			throw new UnsupportedOperationException("the scripted agent is already running");
		}

		@Override
		public java.util.Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
			return org.tanzu.acp.runtime.ToolNames.fromRawInput(toolCall);
		}
	}

	/** Minimal polling, so a fire-and-forget notification can be asserted without a sleep. */
	private static final class Awaits {

		private static void until(java.util.function.BooleanSupplier condition, Duration limit) {
			long deadline = System.nanoTime() + limit.toNanos();
			while (System.nanoTime() < deadline) {
				if (condition.getAsBoolean()) {
					return;
				}
				Thread.onSpinWait();
			}
			throw new AssertionError("condition was not met within " + limit);
		}
	}
}
