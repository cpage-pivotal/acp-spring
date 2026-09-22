package org.springaicommunity.acp.test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.client.AgentClient;
import org.springaicommunity.acp.client.AgentClientFactory;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.config.McpServerSpec;
import org.springaicommunity.acp.config.OnUnsupported;
import org.springaicommunity.acp.config.OptionResolution.Mechanism;
import org.springaicommunity.acp.config.UnsupportedAgentOptionException;
import org.springaicommunity.acp.event.AgentEvent;
import org.springaicommunity.acp.runtime.AgentLaunchSpec;
import org.springaicommunity.acp.runtime.AgentRuntime;
import org.springaicommunity.acp.runtime.AgentRuntime.PortableOption;
import org.springaicommunity.acp.session.AgentSessions;
import org.springaicommunity.acp.session.StoredSession;
import org.springaicommunity.acp.session.UnsupportedAgentOperationException;

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

	private AgentClient connect(ScriptedAgent agent, AgentSettings settings,
			org.springaicommunity.acp.process.AgentLogWatcher watcher) {
		return AgentClientFactory.connect(new ScriptedRuntime(), settings, agent.transport(),
				org.springaicommunity.acp.observation.AgentObservations.NONE, watcher);
	}

	/** A watcher over a log written by hand, standing in for one the agent wrote. */
	private org.springaicommunity.acp.process.AgentLogWatcher watcher(java.nio.file.Path logs) {
		org.springaicommunity.acp.process.AgentLogWatcher watcher = new org.springaicommunity.acp.process.AgentLogWatcher(logs,
				line -> line.startsWith("FAILED ")
						? java.util.Optional.of(org.springaicommunity.acp.runtime.AgentNotice
								.warning(line.substring(7).split(" ")[0], line))
						: java.util.Optional.empty());
		watcher.start();
		return watcher;
	}

	// --- protocol version negotiation -------------------------------------------------------

	@Test
	void reportsTheVersionTheConversationIsReallyIn() {
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, settings().build())) {
			assertThat(client.protocolVersion()).isEqualTo(org.springaicommunity.acp.protocol.AcpProtocol.V1);
		}
	}

	@Test
	void clampsAnAgentThatEchoesBackAVersionNobodyOffered() {
		// goose 1.51 answers whatever it is given, so the response's own number is not evidence of
		// anything. A mock of AcpAsyncClient cannot demonstrate this; a wire agent can.
		try (ScriptedAgent agent = ScriptedAgent.builder().echoesProtocolVersion(true).build();
				AgentClient client = connect(agent,
						settings().protocol(org.springaicommunity.acp.protocol.ProtocolSettings.of(1)).build())) {
			assertThat(client.protocolVersion()).isEqualTo(1);
		}
	}

	@Test
	void refusesToRunATurnAgainstAnAgentClaimingAVersionThisLibraryCannotSpeak() {
		// The feature flag's honest failure: offering v2 finds out what an agent claims, and an
		// agent that claims it is refused rather than misread, because acp-core decodes v1 shapes.
		ScriptedAgent agent = ScriptedAgent.builder().echoesProtocolVersion(true).build();

		assertThatThrownBy(() -> connect(agent,
				settings().protocol(org.springaicommunity.acp.protocol.ProtocolSettings.of(
						org.springaicommunity.acp.protocol.AcpProtocol.DRAFT_V2)).build()))
								.isInstanceOf(org.springaicommunity.acp.protocol.UnsupportedProtocolVersionException.class)
								.hasMessageContaining("negotiated ACP v2");
		agent.close();
	}

	@Test
	void anAgentThatNegotiatesDownIsTakenAtItsWord() {
		// The case the ACP announcement says will be common for a long time: offer the draft, get v1.
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent,
						settings().protocol(org.springaicommunity.acp.protocol.ProtocolSettings.of(
								org.springaicommunity.acp.protocol.AcpProtocol.DRAFT_V2)).build())) {
			assertThat(client.protocolVersion()).isEqualTo(1);
		}
	}

	@Test
	void namesTheModelTheAgentIsActuallySetToEvenWhenNothingAskedForOne() {
		// An application that never set spring.acp.model is the common case, and a metric tagged
		// "unknown" for all of them would be no use. The agent advertises what it is set to; that
		// is the honest answer, and it is not the same as what was requested.
		try (ScriptedAgent agent = ScriptedAgent.builder()
				.select("model", "model", "gpt-5.4-mini", "gpt-5.4-mini", "gpt-6-astra").build();
				AgentClient client = connect(agent, settings().build())) {

			assertThat(client.openSession("s").advertised().select("model", "model"))
					.get().extracting(AcpSchema.SessionConfigSelect::currentValue).isEqualTo("gpt-5.4-mini");
		}
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

	// --- MCP credentials and principals ----------------------------------------------------------

	/** A provider that gives every HTTP server a token naming whoever the session is for. */
	private static org.springaicommunity.acp.mcp.McpCredentialsProvider tokensPerUser(
			List<org.springaicommunity.acp.session.SessionPrincipal> askedFor) {
		return (server, principal) -> {
			askedFor.add(principal);
			return java.util.Optional.of(org.springaicommunity.acp.mcp.McpCredentials
				.bearer(() -> "token-" + (principal == null ? "nobody" : principal.name())));
		};
	}

	private AgentSettings.Builder withProtectedServer(org.springaicommunity.acp.mcp.McpCredentialsProvider provider,
			org.springaicommunity.acp.session.SessionPrincipalResolver principals) {
		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://gateway.example.com/finops-mcp/mcp"), Map.of("X-Tenant", "acme"));
		return settings().mcpServers(List.of(server))
			.mcp(org.springaicommunity.acp.config.McpSettings.defaults().withCredentials(provider).withPrincipals(principals));
	}

	private static String declaredUrl(AcpSchema.NewSessionRequest request) {
		return ((AcpSchema.McpServerHttp) request.mcpServers().get(0)).url();
	}

	/** Whether the proxy still answers for a route; any answer but 404 means it does. */
	private static int statusOf(String url) throws Exception {
		return java.net.http.HttpClient.newHttpClient()
			.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(LIMIT)
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),
					java.net.http.HttpResponse.BodyHandlers.discarding())
			.statusCode();
	}

	@Test
	void aCredentialedMcpServerReachesTheAgentAsALoopbackRouteWithNoSecretsInIt() {
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, withProtectedServer(tokensPerUser(new java.util.ArrayList<>()),
						null).build())) {

			client.prompt().session("s").principal(org.springaicommunity.acp.session.SessionPrincipal.of("alice"))
				.user("hi").call();

			AcpSchema.McpServerHttp declared = (AcpSchema.McpServerHttp) agent.newSessions().get(0).mcpServers().get(0);
			assertThat(declared.name()).isEqualTo("finops-mcp");
			assertThat(declared.url()).startsWith("http://127.0.0.1:").endsWith("/finops-mcp")
				.doesNotContain("gateway.example.com");
			assertThat(declared.headers()).isEmpty();
		}
	}

	@Test
	void aSessionsRouteStopsAnsweringWhenTheSessionCloses() throws Exception {
		try (ScriptedAgent agent = ScriptedAgent.builder().sessionOperations("close").build();
				AgentClient client = connect(agent, withProtectedServer(tokensPerUser(new java.util.ArrayList<>()),
						null).build())) {

			client.prompt().session("s").user("hi").call();
			String url = declaredUrl(agent.newSessions().get(0));
			// The upstream does not exist, so a live route answers 502; what matters is that it is not 404.
			assertThat(statusOf(url)).isNotEqualTo(404);

			client.sessions().close("s");

			assertThat(statusOf(url)).isEqualTo(404);
		}
	}

	@Test
	void anEphemeralTurnsRouteEndsWithTheTurn() throws Exception {
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, withProtectedServer(tokensPerUser(new java.util.ArrayList<>()),
						null).build())) {

			client.prompt("hi").call();

			assertThat(statusOf(declaredUrl(agent.newSessions().get(0)))).isEqualTo(404);
		}
	}

	@Test
	void closingTheClientStopsTheProxy() {
		String url;
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, withProtectedServer(tokensPerUser(new java.util.ArrayList<>()),
						null).build())) {
			client.prompt().session("s").user("hi").call();
			url = declaredUrl(agent.newSessions().get(0));
		}
		assertThatThrownBy(() -> statusOf(url)).isInstanceOf(java.io.IOException.class);
	}

	@Test
	void aSessionNameOpenForOneUserIsRefusedToAnother() {
		List<org.springaicommunity.acp.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (ScriptedAgent agent = ScriptedAgent.builder().reply("ok").build();
				AgentClient client = connect(agent, withProtectedServer(tokensPerUser(askedFor), null).build())) {

			client.prompt().session("ticket-42").principal(org.springaicommunity.acp.session.SessionPrincipal.of("alice"))
				.user("hi").call();

			assertThatThrownBy(() -> client.prompt().session("ticket-42")
				.principal(org.springaicommunity.acp.session.SessionPrincipal.of("bob")).user("hi").call())
				.isInstanceOf(org.springaicommunity.acp.session.SessionOwnershipException.class);
			assertThatThrownBy(() -> client.openSession("ticket-42"))
				.isInstanceOf(org.springaicommunity.acp.session.SessionOwnershipException.class);
			assertThat(client.prompt().session("ticket-42")
				.principal(org.springaicommunity.acp.session.SessionPrincipal.of("alice")).user("again").call().content())
				.isEqualTo("ok");
			assertThat(agent.newSessions()).hasSize(1);
			assertThat(askedFor).containsExactly(org.springaicommunity.acp.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void thePrincipalIsReadOnTheCallersThreadNotWhereTheStreamIsSubscribed() {
		ThreadLocal<String> signedIn = new ThreadLocal<>();
		List<org.springaicommunity.acp.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		org.springaicommunity.acp.session.SessionPrincipalResolver resolver = () -> java.util.Optional.ofNullable(signedIn.get())
			.map(org.springaicommunity.acp.session.SessionPrincipal::of);

		try (ScriptedAgent agent = ScriptedAgent.builder().reply("ok").build();
				AgentClient client = connect(agent, withProtectedServer(tokensPerUser(askedFor), resolver).build())) {

			signedIn.set("alice");
			AgentClient.AgentStream stream = client.prompt().session("s").user("hi").stream();
			signedIn.remove();

			StepVerifier.create(stream.events().subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
				.filter(AgentEvent.Completed.class::isInstance)).expectNextCount(1).verifyComplete();

			assertThat(askedFor).containsExactly(org.springaicommunity.acp.session.SessionPrincipal.of("alice"));
			assertThat(client.session("s").orElseThrow().principal())
				.contains(org.springaicommunity.acp.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void theSamePathsHoldThroughThePool() {
		List<org.springaicommunity.acp.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		ThreadLocal<String> signedIn = new ThreadLocal<>();
		org.springaicommunity.acp.session.SessionPrincipalResolver resolver = () -> java.util.Optional.ofNullable(signedIn.get())
			.map(org.springaicommunity.acp.session.SessionPrincipal::of);
		AgentSettings settings = withProtectedServer(tokensPerUser(askedFor), resolver).build();

		try (ScriptedAgent agent = ScriptedAgent.builder().reply("ok").build();
				AgentClient pool = new org.springaicommunity.acp.client.AgentClientPool("scripted", settings,
						() -> connect(agent, settings))) {

			signedIn.set("alice");
			AgentClient.AgentStream stream = pool.prompt().session("s").user("hi").stream();
			signedIn.remove();
			StepVerifier.create(stream.events().subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
				.filter(AgentEvent.Completed.class::isInstance)).expectNextCount(1).verifyComplete();

			assertThatThrownBy(() -> pool.openSession("s", org.springaicommunity.acp.session.SessionPrincipal.of("bob")))
				.isInstanceOf(org.springaicommunity.acp.session.SessionOwnershipException.class);
			assertThat(askedFor).containsExactly(org.springaicommunity.acp.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void aLoadedSessionsServersAreRoutedForItsPrincipalToo() {
		List<org.springaicommunity.acp.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (ScriptedAgent agent = ScriptedAgent.builder().sessionOperations("load", "resume").build();
				AgentClient client = connect(agent, withProtectedServer(tokensPerUser(askedFor), null).build())) {

			client.sessions().load("a", "stored-1", org.springaicommunity.acp.session.SessionPrincipal.of("alice"));
			client.sessions().resume("b", "stored-2", org.springaicommunity.acp.session.SessionPrincipal.of("bob"));

			assertThat(agent.attachedMcpServers()).hasSize(2).allSatisfy(servers -> {
				assertThat(servers).hasSize(1);
				assertThat(String.valueOf(servers.get(0).get("url"))).startsWith("http://127.0.0.1:")
					.doesNotContain("gateway.example.com");
			});
			assertThat(askedFor).containsExactly(org.springaicommunity.acp.session.SessionPrincipal.of("alice"),
					org.springaicommunity.acp.session.SessionPrincipal.of("bob"));
			assertThat(client.session("a").orElseThrow().principal())
				.contains(org.springaicommunity.acp.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void anAdaptersMcpWorkaroundPutsEveryHttpServerBehindTheProxyEvenWithoutCredentials() throws Exception {
		AgentRuntime withWorkaround = new ScriptedRuntime() {
			@Override
			public List<org.springaicommunity.acp.mcp.McpRequestFilter> mcpRequestFilters(AgentSettings settings) {
				return List.of(request -> org.springaicommunity.acp.mcp.McpRequestFilter.Outcome.Answer.json(200, "{}"));
			}
		};
		McpServerSpec server = new McpServerSpec.Http("tools", java.net.URI.create("https://tools.example.com/mcp"),
				Map.of());
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = AgentClientFactory.connect(withWorkaround,
						settings().mcpServers(List.of(server)).build(), agent.transport())) {

			client.prompt().session("s").user("hi").call();

			String url = declaredUrl(agent.newSessions().get(0));
			assertThat(url).startsWith("http://127.0.0.1:");
			assertThat(statusOf(url)).isEqualTo(200);
		}
	}

	@Test
	void aProviderThatRefusesTheUserRefusesTheSessionBeforeTheAgentHearsOfIt() {
		org.springaicommunity.acp.mcp.McpCredentialsProvider notSignedIn = (server, principal) -> {
			throw new IllegalStateException(server.name() + " needs a sign-in first");
		};
		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				AgentClient client = connect(agent, withProtectedServer(notSignedIn, null).build())) {

			assertThatThrownBy(() -> client.openSession("s", org.springaicommunity.acp.session.SessionPrincipal.of("alice")))
				.hasMessageContaining("needs a sign-in first");
			assertThat(agent.newSessions()).isEmpty();
			assertThat(client.session("s")).isEmpty();
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
	void anMcpServerTheAgentCouldNotLoadFailsTheSessionWhenTheApplicationAsksItTo() throws java.io.IOException {
		// The failure this whole path exists for: the agent answers session/new normally and says
		// nothing over the wire, so the only evidence is the line it wrote to its own log.
		java.nio.file.Path logs = java.nio.file.Files.createDirectories(workspace.resolve("logs"));
		java.nio.file.Files.writeString(logs.resolve("agent.log"), "FAILED finops-mcp refused the connection\n");

		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://tools.example.com/mcp"), Map.of());
		AgentSettings settings = settings().mcpServers(List.of(server))
				.mcp(new org.springaicommunity.acp.config.McpSettings(
						org.springaicommunity.acp.config.McpSettings.OnServerFailure.FAIL, Duration.ofSeconds(2)))
				.build();

		try (ScriptedAgent agent = ScriptedAgent.builder().build();
				org.springaicommunity.acp.process.AgentLogWatcher watcher = watcher(logs);
				AgentClient client = connect(agent, settings, watcher)) {

			assertThatThrownBy(() -> client.prompt().session("s").user("hi").call())
					.hasMessageContaining("finops-mcp").hasMessageContaining("refused the connection");
			assertThat(client.notices()).isNotEmpty();
		}
	}

	@Test
	void aFailureAboutSomeOtherServerDoesNotFailThisSession() throws java.io.IOException {
		java.nio.file.Path logs = java.nio.file.Files.createDirectories(workspace.resolve("logs"));
		java.nio.file.Files.writeString(logs.resolve("agent.log"), "FAILED some-other-mcp refused the connection\n");

		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://tools.example.com/mcp"), Map.of());
		AgentSettings settings = settings().mcpServers(List.of(server))
				.mcp(new org.springaicommunity.acp.config.McpSettings(
						org.springaicommunity.acp.config.McpSettings.OnServerFailure.FAIL, Duration.ofMillis(250)))
				.build();

		try (ScriptedAgent agent = ScriptedAgent.builder().reply("done").build();
				org.springaicommunity.acp.process.AgentLogWatcher watcher = watcher(logs);
				AgentClient client = connect(agent, settings, watcher)) {

			assertThat(client.prompt().session("s").user("hi").call().content()).isEqualTo("done");
		}
	}

	@Test
	void theDefaultIsToOpenTheSessionAndLetTheApplicationReadTheNotice() throws java.io.IOException {
		java.nio.file.Path logs = java.nio.file.Files.createDirectories(workspace.resolve("logs"));
		java.nio.file.Files.writeString(logs.resolve("agent.log"), "FAILED finops-mcp refused the connection\n");

		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://tools.example.com/mcp"), Map.of());

		try (ScriptedAgent agent = ScriptedAgent.builder().reply("done").build();
				org.springaicommunity.acp.process.AgentLogWatcher watcher = watcher(logs);
				AgentClient client = connect(agent, settings().mcpServers(List.of(server)).build(), watcher)) {
			watcher.poll();

			assertThat(client.prompt().session("s").user("hi").call().content()).isEqualTo("done");
			assertThat(client.notices()).singleElement()
					.satisfies(notice -> assertThat(notice.subject()).isEqualTo("finops-mcp"));
		}
	}

	@Test
	void anAgentThatReportsNothingOpensTheSessionEvenUnderFail() throws java.io.IOException {
		java.nio.file.Path logs = java.nio.file.Files.createDirectories(workspace.resolve("logs"));

		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://tools.example.com/mcp"), Map.of());
		AgentSettings settings = settings().mcpServers(List.of(server))
				.mcp(new org.springaicommunity.acp.config.McpSettings(
						org.springaicommunity.acp.config.McpSettings.OnServerFailure.FAIL, Duration.ofMillis(250)))
				.build();

		try (ScriptedAgent agent = ScriptedAgent.builder().reply("done").build();
				org.springaicommunity.acp.process.AgentLogWatcher watcher = watcher(logs);
				AgentClient client = connect(agent, settings, watcher)) {

			// Silence is the normal case, and nothing here infers a failure from it.
			assertThat(client.prompt().session("s").user("hi").call().content()).isEqualTo("done");
			assertThat(client.notices()).isEmpty();
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
	private static class ScriptedRuntime implements AgentRuntime {

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
			return org.springaicommunity.acp.runtime.ToolNames.fromRawInput(toolCall);
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
