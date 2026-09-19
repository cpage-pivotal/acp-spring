package org.tanzu.acp.goose;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentClientFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.event.AgentEvent;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real {@code goose acp} subprocess. Skipped when the binary is absent, so the suite stays
 * green on a machine that has never installed Goose.
 */
@EnabledIf("gooseAvailable")
class GooseLiveIntegrationTests {

	@TempDir
	Path workspace;

	static boolean gooseAvailable() {
		try {
			Process process = new ProcessBuilder("goose", "--version").redirectErrorStream(true).start();
			return process.waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	/**
	 * Pinned rather than inherited: the developer's own Goose config may name a model their key
	 * cannot reach, and that failure looks exactly like a library bug in the assertion output.
	 * Setting it here also exercises the negotiated tier, since Goose honors {@code model} through
	 * {@code session/set_config_option}.
	 */
	private static final String MODEL = System.getProperty("spring-acp.test.model", "gpt-5.4-mini");

	private AgentSettings settings() {
		return AgentSettings.builder(GooseRuntime.ID, workspace).timeout(Duration.ofMinutes(3)).model(MODEL).build();
	}

	@Test
	void connectsAndReportsTheRuntime() {
		try (AgentClient client = AgentClientFactory.create(new GooseRuntime(), settings())) {
			assertThat(client.runtimeId()).isEqualTo("goose");
		}
	}

	@Test
	void answersAPromptAndEndsWithExactlyOneTerminalEvent() {
		try (AgentClient client = AgentClientFactory.create(new GooseRuntime(), settings())) {
			List<AgentEvent> events = client.prompt()
					.user("Reply with exactly the word ACP and nothing else. Do not use any tools.").stream().events()
					.collectList().block(Duration.ofMinutes(3));

			assertThat(events).isNotNull();
			assertThat(events.stream().filter(AgentEvent::terminal)).hasSize(1);
			assertThat(events.get(events.size() - 1).terminal()).isTrue();
			assertThat(events).last().isInstanceOf(AgentEvent.Completed.class);

			String text = events.stream().filter(AgentEvent.Text.class::isInstance).map(AgentEvent.Text.class::cast)
					.map(AgentEvent.Text::text).reduce("", String::concat);
			assertThat(text).containsIgnoringCase("ACP");
		}
	}

	@Test
	void blockingCallReturnsAssistantText() {
		try (AgentClient client = AgentClientFactory.create(new GooseRuntime(), settings())) {
			AgentClient.AgentResponse response = client.prompt("Reply with exactly the word READY. Do not use tools.")
					.call();

			assertThat(response.content()).containsIgnoringCase("READY");
			assertThat(response.completion().reason()).isNotNull();
		}
	}

	@Test
	void namedSessionsKeepContextAcrossTurns() {
		try (AgentClient client = AgentClientFactory.create(new GooseRuntime(), settings())) {
			client.prompt().session("memory-test")
					.user("Remember the number 8675309. Reply with just OK. Do not use tools.").call();

			String recalled = client.prompt().session("memory-test")
					.user("What number did I ask you to remember? Reply with digits only.").call().content();

			assertThat(recalled).contains("8675309");
		}
	}

	@Test
	void cancellingTheStreamStopsTheTurn() {
		try (AgentClient client = AgentClientFactory.create(new GooseRuntime(), settings())) {
			StepVerifier.create(client.prompt().user("Count slowly from 1 to 200, one number per line.").stream()
					.events()).thenCancel().verify(Duration.ofMinutes(1));
		}
	}
}
