package org.springaicommunity.acp.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * A runtime-neutral MCP server declaration. This is the most portable thing in the
 * configuration model: ACP passes {@code mcpServers} through {@code session/new}
 * verbatim, so every compliant agent honors it without adapter involvement.
 */
public sealed interface McpServerSpec {

	String name();

	AcpSchema.McpServer toAcp();

	/**
	 * A one-line, log-safe description of what this server points at.
	 *
	 * <p>
	 * Worth having because a misconfigured MCP server is invisible from the client side:
	 * an agent that fails to connect to one still answers {@code session/new} normally
	 * and reports nothing, so the application gets a healthy-looking session whose model
	 * quietly has no tools. Naming what was requested does not detect that, but it puts
	 * "we asked for finops-mcp" in the log next to a model saying it has no finops tools.
	 * See the "MCP Servers" section of {@code docs/user-guide.html}.
	 *
	 * <p>
	 * Never includes headers or environment values: they carry the tokens.
	 */
	String describe();

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

		@Override
		public String describe() {
			return name + " (stdio: " + command + ")";
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

		@Override
		public String describe() {
			return name + " (http: " + withoutCredentials(url) + ")";
		}

		/**
		 * The URL without anything that could be a credential: a token passed in the
		 * query string or as userinfo is as sensitive as the headers this deliberately
		 * never prints.
		 */
		private static String withoutCredentials(URI url) {
			StringBuilder text = new StringBuilder();
			if (url.getScheme() != null) {
				text.append(url.getScheme()).append("://");
			}
			if (url.getHost() != null) {
				text.append(url.getHost());
				if (url.getPort() != -1) {
					text.append(':').append(url.getPort());
				}
			}
			if (url.getRawPath() != null) {
				text.append(url.getRawPath());
			}
			return text.toString();
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
