package org.tanzu.acp.goose;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentClientFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.test.AgentProbe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second transport, against a real supervised {@code goose serve}.
 *
 * <p>Worth a live test of its own rather than trusting the contract suite. The contract runs over
 * stdio, and everything specific to this path is below the protocol: a process this library starts
 * and waits for rather than one the transport owns, a generated secret that has to reach both the
 * server's environment and the upgrade header, and a socket where there was a pipe. None of that
 * is observable from a scripted agent, and all of it is the kind of thing that works in a unit
 * test and fails against the real server.
 */
@EnabledIf("usable")
class GooseServeTests {

	private static final Duration LIMIT = Duration.ofMinutes(3);

	static boolean usable() {
		return AgentProbe.isUsable(new GooseRuntime());
	}

	@TempDir
	Path workspace;

	private AgentSettings served() {
		return AgentSettings.builder("goose", workspace).timeout(LIMIT)
				.runtimeOptions(Map.of("serve", Map.of("transport", "websocket"))).build();
	}

	@Test
	@DisplayName("a served agent handshakes and runs a turn that terminates exactly once")
	void aTurnOverWebSocket() {
		try (AgentClient client = AgentClientFactory.create(new GooseRuntime(), served())) {
			assertThat(client.agentInfo()).get().extracting(info -> info.name()).isEqualTo("goose");

			List<AgentEvent> events = client.prompt("Reply with exactly the word READY. Do not use tools.").stream()
					.events().collectList().block(LIMIT);

			assertThat(events).isNotNull();
			assertThat(events.stream().filter(AgentEvent::terminal)).hasSize(1);
			assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.Completed.class);
		}
	}

	@Test
	@DisplayName("a named session keeps context over the socket, as it does over the pipe")
	void namedSessionsOverWebSocket() {
		try (AgentClient client = AgentClientFactory.create(new GooseRuntime(), served())) {
			client.prompt().session("served-memory")
					.user("Remember the number 8675309. Reply with just OK. Do not use tools.").call();

			String recalled = client.prompt().session("served-memory")
					.user("What number did I ask you to remember? Reply with digits only.").call().content();

			assertThat(recalled).contains("8675309");
		}
	}

	/**
	 * The server is this library's to stop. A supervised process that outlived its client would
	 * hold its port and its credentials until something else killed it.
	 */
	@Test
	@DisplayName("closing the client stops the server it started")
	void closingStopsTheServer() {
		AgentClient client = AgentClientFactory.create(new GooseRuntime(), served());
		assertThat(client.isAlive()).isTrue();

		client.close();

		assertThat(client.isAlive()).isFalse();
	}
}
