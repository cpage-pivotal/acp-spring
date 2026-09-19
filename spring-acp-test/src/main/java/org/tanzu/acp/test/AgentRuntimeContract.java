package org.tanzu.acp.test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentClientFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.OnUnsupported;
import org.tanzu.acp.config.OptionResolution;
import org.tanzu.acp.config.SessionConfiguration;
import org.tanzu.acp.config.UnsupportedAgentOptionException;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.runtime.AgentRuntime.PortableOption;

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
 * <p>To add a runtime: extend this, return the adapter and a model it advertises, and gate the class
 * on the agent being installed.
 *
 * <pre>{@code
 * @EnabledIf("available")
 * class OpenCodeContractTests extends AgentRuntimeContract {
 *     protected AgentRuntime runtime() { return new OpenCodeRuntime(); }
 *     protected String supportedModel() { return "openai/gpt-5.4-mini"; }
 * }
 * }</pre>
 */
public abstract class AgentRuntimeContract {

	/** Agents are slow, and a live turn against a hosted model is slower than a local test. */
	protected static final Duration TURN_TIMEOUT = Duration.ofMinutes(3);

	@TempDir
	protected Path workspace;

	private AgentClient client;

	/** The adapter under test. A fresh instance per test. */
	protected abstract AgentRuntime runtime();

	/**
	 * A model this agent really offers, spelled the way an application would spell it.
	 *
	 * <p>Discovered rather than hardcoded, and specifically the model the agent is <em>already</em>
	 * configured with. Pinning one per agent would put three model catalogs into this source file and
	 * break whenever a vendor retires a name. Picking any other advertised model is worse than it
	 * sounds: an agent advertises what its vendor sells, not what this machine's key can reach, and the
	 * difference surfaces inside the turn, as a provider error, which reads like a bug in this library.
	 * A system property overrides.
	 */
	protected String supportedModel() {
		return System.getProperty("spring-acp.test." + runtime().id() + ".model",
				AgentProbe.of(runtime()).currentModel().orElseThrow(
						() -> new IllegalStateException("runtime '" + runtime().id()
								+ "' advertised no models, so there is nothing portable to negotiate over; set "
								+ "-Dspring-acp.test." + runtime().id() + ".model to name one")));
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
		AgentSettings settings = settings().model("no-such-model-spring-acp-9000")
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
		AgentSettings settings = settings().model("no-such-model-spring-acp-9000")
				.onUnsupported(OnUnsupported.WARN).build();

		try (AgentClient lenient = AgentClientFactory.create(runtime(), settings)) {
			lenient.prompt().session("contract-warn").user("Reply with OK. Do not use tools.").call();

			OptionResolution model = configurationOf(lenient, "contract-warn").of(PortableOption.MODEL);
			assertThat(model.mechanism()).isEqualTo(OptionResolution.Mechanism.UNSUPPORTED);
		}
	}

	@Test
	@DisplayName("a turn that needs a tool still ends exactly once, whatever the policy decides")
	void aToolUsingTurnStillTerminatesExactlyOnce() {
		// The failure this library's deny-by-default policy could most easily have introduced: an
		// unanswered session/request_permission stalls a turn forever, and no timeout in the agent will
		// end it, because from the agent's side nothing is wrong.
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
