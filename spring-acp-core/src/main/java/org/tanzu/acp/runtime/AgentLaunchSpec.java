package org.tanzu.acp.runtime;

import java.util.List;
import java.util.Map;

import org.tanzu.acp.config.Validation;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;

/**
 * How to start an agent.
 *
 * <p>Sealed with one variant today. WebSocket-attached agents — Goose's {@code goose serve} among
 * them — arrive with the process pool; every runtime in the first milestone speaks stdio.
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
}
