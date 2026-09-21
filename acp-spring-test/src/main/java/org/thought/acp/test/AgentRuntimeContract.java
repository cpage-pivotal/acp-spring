package org.thought.acp.test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thought.acp.client.AgentClient;
import org.thought.acp.client.AgentClientFactory;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.config.OnUnsupported;
import org.thought.acp.config.OptionResolution;
import org.thought.acp.config.ProviderSpec;
import org.thought.acp.config.SessionConfiguration;
import org.thought.acp.config.UnsupportedAgentOptionException;
import org.thought.acp.event.AgentEvent;
import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentRuntime;
import org.thought.acp.runtime.AgentRuntime.PortableOption;
import org.thought.acp.session.AgentSessions;
import org.thought.acp.session.UnsupportedAgentOperationException;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The portable contract every {@link AgentRuntime} must satisfy, run against a live agent.
 *
 * <p><strong>This suite, not the {@code AgentRuntime} interface, is the definition of the
 * abstraction.</strong> An interface only constrains signatures; three adapters can implement it and
 * still behave differently enough that an application cannot move between them, which is the failure
 * this library exists to prevent. What is asserted here is exactly what {@code spring.acp.runtime}
 * promises: change that one line and the application keeps working.
 *
 * <p>It therefore asserts only what ACP genuinely standardizes — sessions, turns, the
 * exactly-one-terminal-event invariant, cancellation, permission denial, and the negotiated tier's
 * two honest outcomes. It never asserts a model name, a tool name, or anything about how an agent
 * phrases an answer. A test that a runtime could only pass by behaving like Goose would make the
 * suite a Goose conformance suite.
 *
 * <p>Every test here spends real turns against a real vendor, so the suite is opt-in and skips
 * unless the build asked for it — see {@link LiveAgents}, and gate the subclass on
 * {@link AgentProbe#isUsable}, which answers for both that switch and this machine:
 *
 * <pre>{@code
 * mvn test -Dacp-spring.test.live=true
 * }</pre>
 *
 * <p>To add a runtime: extend this, return the adapter, name the cheap end of its catalog, and gate
 * the class on the agent being installed. The model itself is discovered, not declared.
 *
 * <pre>{@code
 * @EnabledIf("usable")
 * class OpenCodeContractTests extends AgentRuntimeContract {
 *     static boolean usable() { return AgentProbe.isUsable(new OpenCodeRuntime()); }
 *     protected AgentRuntime runtime() { return new OpenCodeRuntime(); }
 *     protected List<String> preferredModels() { return List.of("openai/gpt-5.4-mini"); }
 * }
 * }</pre>
 */
public abstract class AgentRuntimeContract {

	/** Agents are slow, and a live turn against a hosted model is slower than a local test. */
	protected static final Duration TURN_TIMEOUT = Duration.ofMinutes(3);

	/**
	 * An endpoint shaped like the ones platforms hand out — a path prefix, no version segment — and
	 * unresolvable on purpose, because nothing here connects to it.
	 */
	private static final String CONTRACT_ENDPOINT = "https://gateway.example.invalid/team-x/openai";

	@TempDir
	protected Path workspace;

	/** Where an adapter may write the files its agent reads at startup, per test. */
	@TempDir
	protected Path runtimeHome;

	private AgentClient client;

	/** The adapter under test. A fresh instance per test. */
	protected abstract AgentRuntime runtime();

	/**
	 * A model this agent really offers, spelled the way an application would spell it.
	 *
	 * <p>Discovered rather than hardcoded. Pinning one per agent would put three model catalogs into
	 * this source file and break whenever a vendor retires a name, so the order is: an explicit system
	 * property, then the first of {@link #preferredModels()} the agent actually advertises, then the
	 * model the agent is <em>already</em> configured with.
	 *
	 * <p>That last fallback matters: an agent advertises what its vendor sells, not what this
	 * machine's key can reach, and the difference surfaces inside the turn, as a provider error, which
	 * reads like a bug in this library. A preference is therefore only ever taken from the advertised
	 * list, and never invented.
	 */
	protected String supportedModel() {
		String requested = System.getProperty("acp-spring.test." + runtime().id() + ".model");
		if (requested != null && !requested.isBlank()) {
			return requested;
		}
		AgentProbe probe = AgentProbe.of(runtime());
		return preferredModels().stream().filter(probe.models()::contains).findFirst()
				.or(probe::currentModel)
				.orElseThrow(() -> new IllegalStateException("runtime '" + runtime().id()
						+ "' advertised no models, so there is nothing portable to negotiate over; set "
						+ "-Dacp-spring.test." + runtime().id() + ".model to name one"));
	}

	/**
	 * Models this suite would rather spend its turns on, most preferred first.
	 *
	 * <p>The contract runs several live turns per runtime, and nothing it asserts needs a frontier
	 * model: the questions are whether a turn terminates exactly once, whether cancellation reaches
	 * the agent, and whether a permission denial is honoured. A runtime names the cheap end of its own
	 * catalog here — cheap enough to be worth the saving, capable enough to still follow an
	 * instruction and call a tool — and any name the agent does not advertise is simply skipped, so
	 * this list going stale costs nothing but the saving.
	 *
	 * <p>This is a testing economy, not a recommendation: nothing here reaches
	 * {@code spring.acp.model} or any runtime default. An application picks its own model.
	 */
	protected List<String> preferredModels() {
		return List.of();
	}

	/** A provider id to qualify {@link #supportedModel()} with, when the agent needs one. */
	protected Optional<String> provider() {
		return Optional.empty();
	}

	/**
	 * The mode in which this agent asks before it acts, if it has one.
	 *
	 * <p>The one piece of vendor knowledge this suite cannot do without, and it earns its place.
	 * Measured against all three runtimes, {@code permissions.policy: deny} on its own does
	 * <em>not</em> stop an agent writing a file: declaring {@code fs.writeTextFile: false} only
	 * declines to lend the agent the client's filesystem, and an agent in its default mode uses its
	 * own and never asks. goose 1.51 and OpenCode 1.18 both create the file with zero
	 * {@code session/request_permission} calls. Put the same agent in its reviewing mode and goose
	 * asks twice, is refused twice, and writes nothing.
	 *
	 * <p>So the permission policy answers questions; the mode decides whether they get asked. Both
	 * halves are needed, and the mode's name is the agent's own — goose {@code approve}, Codex
	 * {@code read-only}, OpenCode {@code plan} — with nothing in the protocol to derive it from.
	 */
	protected Optional<String> reviewingMode() {
		return Optional.empty();
	}

	/** Settings for this runtime, already carrying the pinned model. */
	protected AgentSettings.Builder settings() {
		AgentSettings.Builder builder = AgentSettings.builder(runtime().id(), workspace).timeout(TURN_TIMEOUT)
				.model(supportedModel());
		provider().ifPresent(builder::provider);
		return builder;
	}

	protected AgentClient client() {
		if (client == null) {
			client = AgentClientFactory.create(runtime(), settings().build());
		}
		return client;
	}

	@BeforeEach
	void resetClient() {
		client = null;
	}

	@AfterEach
	void closeClient() {
		if (client != null) {
			client.close();
			client = null;
		}
	}

	@Test
	@DisplayName("connects, negotiates the protocol, and reports its own id")
	void connects() {
		assertThat(client().runtimeId()).isEqualTo(runtime().id());
	}

	@Test
	@DisplayName("a turn ends with exactly one terminal event, and it is last")
	void oneTerminalEvent() {
		List<AgentEvent> events = client().prompt("Reply with exactly the word ACP. Do not use any tools.").stream()
				.events().collectList().block(TURN_TIMEOUT);

		assertThat(events).isNotNull().isNotEmpty();
		assertThat(events.stream().filter(AgentEvent::terminal)).hasSize(1);
		assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.Completed.class);
	}

	@Test
	@DisplayName("a blocking call returns the assistant's text and nothing else")
	void blockingCall() {
		AgentClient.AgentResponse response = client()
				.prompt("Reply with exactly the word READY. Do not use any tools.").call();

		assertThat(response.content()).containsIgnoringCase("READY");
		assertThat(response.completion().reason()).isNotNull();
	}

	@Test
	@DisplayName("a named session keeps context across turns")
	void namedSessionsKeepContext() {
		client().prompt().session("contract-memory")
				.user("Remember the number 8675309. Reply with just OK. Do not use tools.").call();

		String recalled = client().prompt().session("contract-memory")
				.user("What number did I ask you to remember? Reply with digits only.").call().content();

		assertThat(recalled).contains("8675309");
	}

	@Test
	@DisplayName("cancelling the stream ends the turn rather than leaking it")
	void cancellationReachesTheAgent() {
		StepVerifier
				.create(client().prompt().user("Count slowly from 1 to 200, one number per line.").stream().events())
				.thenCancel().verify(Duration.ofMinutes(1));
	}

	@Test
	@DisplayName("the requested model is applied through a mechanism the agent advertises")
	void negotiatesTheRequestedModel() {
		// No turn: opening the session is enough, which is the point of being able to open one.
		client().openSession("contract-config");

		OptionResolution model = configurationOf(client(), "contract-config").of(PortableOption.MODEL);
		assertThat(model.isApplied())
				.withFailMessage("expected model '%s' to be applied, but it was %s (%s)", supportedModel(),
						model.mechanism(), model.detail())
				.isTrue();
		assertThat(model.applied()).isNotBlank();
	}

	@Test
	@DisplayName("a different model the agent advertises can be applied too")
	void switchesToAnotherAdvertisedModel() {
		String alternative = AgentProbe.of(runtime()).alternativeModel().orElse(null);
		org.junit.jupiter.api.Assumptions.assumeTrue(alternative != null,
				"this agent advertises only one model, so there is nothing to switch to");

		try (AgentClient switched = AgentClientFactory.create(runtime(), settings().model(alternative).build())) {
			OptionResolution model = switched.openSession("contract-switch").configuration()
					.of(PortableOption.MODEL);

			assertThat(model.mechanism()).isIn(OptionResolution.Mechanism.CONFIG_OPTION,
					OptionResolution.Mechanism.SESSION_MODEL);
			assertThat(model.applied()).isNotNull();
		}
	}

	@Test
	@DisplayName("a model the agent does not have is refused before the turn, not during it")
	void anUnsupportedModelFailsFastWhenAsked() {
		AgentSettings settings = settings().model("no-such-model-acp-spring-9000")
				.onUnsupported(OnUnsupported.FAIL).build();

		try (AgentClient strict = AgentClientFactory.create(runtime(), settings)) {
			assertThatThrownBy(() -> strict.prompt("Reply with OK.").call())
					.satisfies(thrown -> assertThat(chain(thrown))
							.hasAtLeastOneElementOfType(UnsupportedAgentOptionException.class));
		}
	}

	@Test
	@DisplayName("the same request with on-unsupported=warn runs anyway and says so")
	void anUnsupportedModelIsRecordedWhenWarning() {
		AgentSettings settings = settings().model("no-such-model-acp-spring-9000")
				.onUnsupported(OnUnsupported.WARN).build();

		try (AgentClient lenient = AgentClientFactory.create(runtime(), settings)) {
			lenient.prompt().session("contract-warn").user("Reply with OK. Do not use tools.").call();

			OptionResolution model = configurationOf(lenient, "contract-warn").of(PortableOption.MODEL);
			assertThat(model.mechanism()).isEqualTo(OptionResolution.Mechanism.UNSUPPORTED);
		}
	}

	/**
	 * A tool-using turn terminates once, on an agent with no mode in which it would ask.
	 *
	 * <p>The failure this library's deny-by-default policy could most easily have introduced: an
	 * unanswered {@code session/request_permission} stalls a turn forever, and no timeout in the agent
	 * will end it, because from the agent's side nothing is wrong.
	 *
	 * <p>Skipped where {@link #reviewingMode()} exists, because
	 * {@link #denyByDefaultStopsAWriteWhenTheAgentAsksFirst} then asserts the same invariant over the
	 * same file-writing prompt, and asserts it in the harder case — the one where permission really is
	 * requested and refused, which is where a stall would actually happen. An agentic turn is the most
	 * expensive thing this suite does, by more than the other turns put together, and running two of
	 * them per runtime to prove one thing is not worth what it costs.
	 */
	@Test
	@DisplayName("a turn that needs a tool still ends exactly once, whatever the policy decides")
	void aToolUsingTurnStillTerminatesExactlyOnce() {
		org.junit.jupiter.api.Assumptions.assumeTrue(reviewingMode().isEmpty(),
				"this agent has a reviewing mode, so denyByDefaultStopsAWriteWhenTheAgentAsksFirst "
						+ "covers this invariant in the harder case");

		List<AgentEvent> events = client().prompt()
				.user("Create a file called contract.txt containing the word NO, then reply DONE.").stream().events()
				.collectList().block(TURN_TIMEOUT);

		assertThat(events).isNotNull();
		assertThat(events.stream().filter(AgentEvent::terminal)).hasSize(1);
	}

	@Test
	@DisplayName("in the agent's reviewing mode, deny-by-default actually stops the write")
	void denyByDefaultStopsAWriteWhenTheAgentAsksFirst() {
		String mode = reviewingMode().orElse(null);
		org.junit.jupiter.api.Assumptions.assumeTrue(mode != null,
				"this agent has no mode in which it asks before acting");

		try (AgentClient reviewed = AgentClientFactory.create(runtime(), settings().mode(mode).build())) {
			List<AgentEvent> events = reviewed.prompt().session("contract-deny")
					.user("Create a file called contract.txt containing the word NO, then reply DONE.").stream()
					.events().collectList().block(TURN_TIMEOUT);

			assertThat(events).isNotNull();
			assertThat(events.stream().filter(AgentEvent::terminal)).hasSize(1);
			assertThat(workspace.resolve("contract.txt")).doesNotExist();
		}
	}

	/**
	 * The optional session methods are declared rather than discovered by failing.
	 *
	 * <p>The portable claim is not that any of these work — {@code session/list},
	 * {@code session/load}, {@code session/resume} and {@code session/delete} are each gated on a
	 * capability, and the three runtimes implement different subsets, which is exactly the kind of
	 * difference an application must not have to know about. What is portable is that asking is
	 * safe: {@code supports} answers for every operation, and an operation the agent never
	 * advertised throws a typed exception naming the method instead of failing on the wire with an
	 * error code the caller would have to interpret.
	 */
	@Test
	@DisplayName("every optional session operation is askable, and says no by name when it must")
	void optionalSessionOperationsAreDeclared() {
		AgentSessions sessions = client().sessions();

		for (AgentSessions.Operation operation : AgentSessions.Operation.values()) {
			assertThat(sessions.supports(operation)).isNotNull();
		}

		if (sessions.supports(AgentSessions.Operation.LIST)) {
			assertThat(sessions.list()).isNotNull();
		}
		else {
			assertThatThrownBy(sessions::list).isInstanceOf(UnsupportedAgentOperationException.class)
					.hasMessageContaining("session/list");
		}

		if (!sessions.supports(AgentSessions.Operation.DELETE)) {
			assertThatThrownBy(() -> sessions.delete("no-such-session"))
					.isInstanceOf(UnsupportedAgentOperationException.class);
		}
	}

	/**
	 * Closing a session works whether or not the agent has {@code session/close}.
	 *
	 * <p>An agent without it cannot be told, and there is nothing a client can do about that except
	 * stop using the session; an application should not have to find out which kind of agent it has
	 * before it can end a conversation. No turn is spent: a name that came back with a new session
	 * id is proof enough that the old one was let go.
	 */
	@Test
	@DisplayName("closing a named session works on every runtime, told or not")
	void closingANamedSessionAlwaysWorks() {
		AgentClient agent = client();
		String first = agent.openSession("contract-close").sessionId();

		agent.sessions().close("contract-close");

		assertThat(agent.session("contract-close")).isEmpty();
		assertThat(agent.openSession("contract-close").sessionId()).isNotEqualTo(first);
	}

	/**
	 * A conversation survives being closed and loaded again, on the agents that can load.
	 *
	 * <p>Skipped rather than asserted where {@code session/load} is absent, because the protocol
	 * genuinely makes it optional and a suite that failed goose for not having {@code resume} — or
	 * OpenCode for not having {@code delete} — would be asserting a feature matrix rather than a
	 * contract. What it does assert, for every agent that offers the method, is the only reason to
	 * offer it: the reloaded session is the same conversation, not a new one wearing its id.
	 */
	@Test
	@DisplayName("a closed session loaded again is still the same conversation")
	void loadingRestoresContext() {
		AgentClient agent = client();
		org.junit.jupiter.api.Assumptions.assumeTrue(agent.sessions().supports(AgentSessions.Operation.LOAD),
				"this agent does not implement session/load");

		agent.prompt().session("contract-load")
				.user("Remember the number 8675309. Reply with just OK. Do not use tools.").call();
		String sessionId = agent.session("contract-load").orElseThrow().sessionId();
		agent.sessions().close("contract-load");

		agent.sessions().load("contract-reloaded", sessionId);
		String recalled = agent.prompt().session("contract-reloaded")
				.user("What number did I ask you to remember? Reply with digits only.").call().content();

		assertThat(recalled).contains("8675309");
	}

	/**
	 * An MCP server the agent cannot reach is either reported, or admitted to be unreportable.
	 *
	 * <p>The one assertion in this suite about something ACP does <em>not</em> standardize, and it
	 * earns its place because the protocol's silence here is total. An agent that fails to connect
	 * to an MCP server answers {@code session/new} normally, sends no {@code session/update}, and —
	 * measured on goose 1.51.0 across both its transports — writes nothing to stdout or stderr
	 * either. The application gets a session whose model quietly has no tools, and finds out from
	 * the model's prose.
	 *
	 * <p>So an adapter may answer this in one of two ways, and both are honest. It can decline to
	 * report — {@link AgentRuntime#logDirectory} empty, which is where every adapter starts and
	 * where most of them stay — and this test skips. Or it can claim it reports, in which case the
	 * claim is tested against a real agent and a server that is really unreachable, because a
	 * detector nobody has pointed at the failure is a detector nobody knows is broken.
	 *
	 * <p>This is the test that goes red the day the agent rewords its own diagnostics. It only runs
	 * live, so the day it goes red is a release day rather than a commit — a real limitation of
	 * depending on another program's log, stated here rather than papered over.
	 */
	@Test
	@DisplayName("an MCP server the agent cannot reach is reported, or the adapter does not claim to")
	void anUnreachableMcpServerIsReportedOrNotClaimed() {
		org.junit.jupiter.api.Assumptions.assumeTrue(
				runtime().logDirectory(settings().build()).isPresent(),
				"this adapter does not claim to read the agent's own diagnostics");

		// Port 9 is the discard port: reserved, and nothing there answers.
		String name = "acp-spring-contract-mcp";
		McpServerSpec unreachable = new McpServerSpec.Http(name, URI.create("http://127.0.0.1:9/mcp"), Map.of());

		try (AgentClient agent = AgentClientFactory.create(runtime(),
				settings().mcpServers(List.of(unreachable)).build())) {
			agent.openSession("contract-mcp");

			assertThat(noticeAbout(agent, name, Duration.ofSeconds(20)))
					.describedAs("the adapter reports a log directory, so a server the agent could not "
							+ "load must produce a notice naming it")
					.isTrue();
		}
	}

	/** Polls, because the agent writes its log on its own schedule and the watcher reads it on another. */
	private static boolean noticeAbout(AgentClient agent, String server, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (agent.notices().stream().anyMatch(notice -> notice.concerns(server))) {
				return true;
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	/**
	 * An endpoint the application named reaches the agent, one way or another.
	 *
	 * <p>The portable claim is not that every agent takes a base URL the same way — Goose splits it
	 * across two variables, Codex will only read it out of a {@code [model_providers.*]} table,
	 * OpenCode wants a provider declared in its own JSON — but that naming one is never silently
	 * dropped. An adapter that ignored {@code spring.acp.provider.base-url} would leave the
	 * application talking to a vendor it did not choose, with a key that does not work there, and
	 * nothing in the configuration to say so.
	 *
	 * <p>The second assertion is the one that keeps {@code on-unsupported} honest:
	 * {@link AgentRuntime#appliedOutOfBand} is a claim about what launching did, and it must be the
	 * same answer as what launching actually wrote. An adapter that over-claims turns a failed model
	 * request into a silent default; one that under-claims fails a configuration that is working.
	 *
	 * <p>No agent is started: this is about what the adapter hands the process.
	 */
	@Test
	@DisplayName("an endpoint the application named is carried to the agent, and declared honestly")
	void anEndpointOfTheApplicationsOwnIsCarriedToTheAgent() throws Exception {
		String model = "acp-spring-contract-model";
		AgentSettings settings = AgentSettings.builder(runtime().id(), workspace).runtimeHome(runtimeHome)
				.provider(new ProviderSpec(null, "openai", URI.create(CONTRACT_ENDPOINT), "sk-contract", Map.of()))
				.model(model).build();

		runtime().provision(settings);
		String carried = String.join("\n", carriedBy(runtime().launch(settings), settings));

		assertThat(carried).contains("gateway.example.invalid/team-x/openai");
		assertThat(runtime().appliedOutOfBand(PortableOption.MODEL, settings))
				.describedAs("appliedOutOfBand(MODEL) must say whether launching really carried the model")
				.isEqualTo(carried.contains(model));
	}

	/** Everything the adapter hands the agent that a human could read: its environment and its files. */
	private List<String> carriedBy(AgentLaunchSpec launch, AgentSettings settings) throws IOException {
		List<String> carried = new java.util.ArrayList<>(switch (launch) {
			case AgentLaunchSpec.Stdio stdio -> stdio.env().values();
			case AgentLaunchSpec.WebSocket socket -> socket.process().env().values();
		});
		if (Files.isDirectory(settings.runtimeHome())) {
			try (java.util.stream.Stream<Path> files = Files.walk(settings.runtimeHome())) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					carried.add(Files.readString(file));
				}
			}
		}
		return carried;
	}

	private SessionConfiguration configurationOf(AgentClient agent, String session) {
		return agent.session(session).map(s -> s.configuration()).orElse(SessionConfiguration.empty());
	}

	private static List<Throwable> chain(Throwable thrown) {
		List<Throwable> causes = new java.util.ArrayList<>();
		for (Throwable current = thrown; current != null && !causes.contains(current); current = current.getCause()) {
			causes.add(current);
		}
		return causes;
	}
}
