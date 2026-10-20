package org.springaicommunity.acp.test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.client.AcpAgentClientException;
import org.springaicommunity.acp.client.AcpAgentClientFactory;
import org.springaicommunity.acp.config.AcpAgentSettings;
import org.springaicommunity.acp.runtime.AcpAgentLaunchSpec;
import org.springaicommunity.acp.runtime.AcpAgentRuntime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an operator is told when an agent refuses to start.
 *
 * <p>
 * This has a test because getting it wrong is cheap and costs someone an afternoon. An
 * agent that will not start explains itself on stderr — an invalid config file, a missing
 * login — while the only thing this library observes is a handshake that never completed.
 * Reporting just the handshake gives an operator a message that names the runtime and
 * nothing else, for a problem the agent already described in full.
 */
class AcpAgentStartupFailureTests {

	@TempDir
	Path workspace;

	@Test
	void theAgentsOwnExplanationIsInTheException() {
		assertThatThrownBy(() -> AcpAgentClientFactory.create(new FailingRuntime("Configuration is invalid at line 3"),
				settings()))
			.isInstanceOf(AcpAgentClientException.class)
			.hasMessageContaining("Failed to initialize runtime 'failing'")
			.hasMessageContaining("Configuration is invalid at line 3");
	}

	@Test
	void theColourCodesAnAgentWritesForATerminalAreStrippedOut() {
		// OpenCode's real complaint arrives as "\u001B[91m\u001B[1mError:
		// \u001B[0mConfiguration is
		// invalid …", and an exception message is not a terminal.
		assertThatThrownBy(() -> AcpAgentClientFactory
			.create(new FailingRuntime("\u001B[91m\u001B[1mError: \u001B[0mbad config"), settings()))
			.hasMessageContaining("Error: bad config")
			.hasMessageNotContaining("\u001B");
	}

	@Test
	void anAgentThatFailsSilentlyStillFailsCleanly() {
		assertThatThrownBy(() -> AcpAgentClientFactory.create(new FailingRuntime(null), settings()))
			.isInstanceOf(AcpAgentClientException.class)
			.hasMessageContaining("Failed to initialize runtime");
	}

	private AcpAgentSettings settings() {
		return AcpAgentSettings.builder("failing", workspace).timeout(Duration.ofSeconds(5)).build();
	}

	/**
	 * Launches something that complains and exits, the way a misconfigured agent does.
	 */
	private record FailingRuntime(String complaint) implements AcpAgentRuntime {

		@Override
		public String id() {
			return "failing";
		}

		/**
		 * Complains, then lingers before exiting.
		 *
		 * <p>
		 * The lingering is not padding. A process that dies within microseconds of
		 * writing to stderr races the SDK's own stderr plumbing, which subscribes to the
		 * child's error stream slightly after starting it and loses anything already
		 * written — measured at roughly one run in five. That race belongs to acp-core,
		 * and no real agent is in it: an agent that rejects its configuration has parsed
		 * a file first. Reproducing it here would only make this suite flaky about
		 * somebody else's bug.
		 */
		@Override
		public AcpAgentLaunchSpec launch(AcpAgentSettings settings) {
			String script = complaint == null ? "sleep 1; exit 1"
					: "printf '%s\\n' \"" + complaint + "\" >&2; sleep 1; exit 1";
			return new AcpAgentLaunchSpec.Stdio("sh", List.of("-c", script), Map.of());
		}
	}

}
