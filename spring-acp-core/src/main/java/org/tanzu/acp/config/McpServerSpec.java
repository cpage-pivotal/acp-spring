package org.tanzu.acp.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * A runtime-neutral MCP server declaration. This is the most portable thing in the configuration
 * model: ACP passes {@code mcpServers} through {@code session/new} verbatim, so every compliant
 * agent honors it without adapter involvement.
 */
public sealed interface McpServerSpec {

	String name();

	AcpSchema.McpServer toAcp();

	record Stdio(String name, String command, List<String> args, Map<String, String> env) implements McpServerSpec {
		public Stdio {
			Validation.requireName(name, "mcp server name");
			Validation.requireText(command, "mcp server command");
			args = args == null ? List.of() : List.copyOf(args);
			env = env == null ? Map.of() : Map.copyOf(env);
			env.forEach((k, v) -> Validation.requireEnvName(k));
		}

		@Override
		public AcpSchema.McpServer toAcp() {
			return new AcpSchema.McpServerStdio(name, command, args,
					env.entrySet().stream().map(e -> new AcpSchema.EnvVariable(e.getKey(), e.getValue())).toList());
		}
	}

	record Http(String name, URI url, Map<String, String> headers) implements McpServerSpec {
		public Http {
			Validation.requireName(name, "mcp server name");
			Validation.requireSecureUrl(url, "mcp server url");
			headers = headers == null ? Map.of() : sanitizedHeaders(headers);
		}

		@Override
		public AcpSchema.McpServer toAcp() {
			return new AcpSchema.McpServerHttp(name, url.toString(),
					headers.entrySet().stream().map(e -> new AcpSchema.HttpHeader(e.getKey(), e.getValue())).toList());
		}

		private static Map<String, String> sanitizedHeaders(Map<String, String> headers) {
			Map<String, String> copy = new LinkedHashMap<>();
			headers.forEach((k, v) -> {
				Validation.requireHeaderName(k);
				Validation.requireHeaderValue(k, v);
				copy.put(k, v);
			});
			return Map.copyOf(copy);
		}
	}
}
