package org.springaicommunity.acp.runtime;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springaicommunity.acp.config.Validation;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;

/**
 * How to start an agent, or how to reach one.
 *
 * <p>
 * Two shapes, and the difference between them is who owns the process. With {@link Stdio}
 * the transport owns it: the SDK spawns the child, writes to its stdin and reads its
 * stdout, and the process dies when the transport closes. With {@link WebSocket} the
 * agent is a server, and this library either supervises the server it started
 * ({@link ManagedProcess}) or attaches to one somebody else runs. Only Goose offers the
 * second shape today; every other runtime is stdio-only, which is why the sealed
 * hierarchy exists rather than one record with optional fields.
 */
public sealed interface AgentLaunchSpec {

	/** A subprocess speaking ACP over its stdin and stdout. */
	record Stdio(String command, List<String> args, Map<String, String> env) implements AgentLaunchSpec {

		public Stdio {
			Validation.requireText(command, "agent command");
			args = args == null ? List.of() : List.copyOf(args);
			env = env == null ? Map.of() : Map.copyOf(env);
			env.keySet().forEach(Validation::requireEnvName);
		}

		public AgentParameters toAgentParameters() {
			return AgentParameters.builder(command).args(args).env(env).build();
		}
	}

	/**
	 * An agent reached over a WebSocket, optionally one this library starts and
	 * supervises.
	 *
	 * @param uri the ACP endpoint, e.g. {@code ws://127.0.0.1:3284/acp}
	 * @param headers sent on the upgrade request; where an auth secret goes, and never
	 * logged
	 * @param process the server to start first, or {@code null} to attach to a running
	 * one
	 */
	record WebSocket(URI uri, Map<String, String> headers, ManagedProcess process) implements AgentLaunchSpec {

		public WebSocket {
			if (uri == null) {
				throw new IllegalArgumentException("agent WebSocket uri must not be null");
			}
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
			if (!scheme.equals("ws") && !scheme.equals("wss")) {
				throw new IllegalArgumentException("agent WebSocket uri must use ws or wss but was '" + uri + "'");
			}
			headers = headers == null ? Map.of() : Map.copyOf(headers);
			headers.forEach((name, value) -> {
				Validation.requireHeaderName(name);
				Validation.requireHeaderValue(name, value);
			});
		}

		public WebSocket(URI uri, Map<String, String> headers) {
			this(uri, headers, null);
		}
	}

	/**
	 * A server process to start before connecting, and keep running afterwards.
	 *
	 * @param healthUri polled until it answers 200, or {@code null} to treat "still
	 * alive" as ready
	 * @param startupTimeout how long to wait for that before giving up
	 */
	record ManagedProcess(String command, List<String> args, Map<String, String> env, URI healthUri,
			Duration startupTimeout) {

		public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);

		public ManagedProcess {
			Validation.requireText(command, "agent command");
			args = args == null ? List.of() : List.copyOf(args);
			env = env == null ? Map.of() : Map.copyOf(env);
			env.keySet().forEach(Validation::requireEnvName);
			startupTimeout = startupTimeout == null ? DEFAULT_STARTUP_TIMEOUT : startupTimeout;
		}
	}

}
