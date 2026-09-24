package org.springaicommunity.acp.mcp;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adjusts, or answers, one request the loopback proxy is about to forward to an MCP
 * server.
 *
 * <p>
 * For interoperability bugs between one agent and some MCP servers that neither side has
 * fixed yet — the kind of thing that makes an agent drop a server without saying so. An
 * {@code AgentRuntime} contributes these through {@code mcpRequestFilters}, which keeps
 * vendor knowledge in the adapter that owns it; the proxy only runs them. Filters run in
 * order on every request, after the route is known and before credentials are added, and
 * the first to {@link Outcome.Answer answer} ends the chain: nothing reaches the
 * upstream.
 *
 * <p>
 * Each one should name the versions it was measured against and be easy to delete,
 * because the right number of these is zero.
 */
@FunctionalInterface
public interface McpRequestFilter {

	Outcome filter(McpRequest request);

	/** What a filter decided. */
	sealed interface Outcome {

		/**
		 * Carry on with this request, which may differ from the one the filter was given.
		 */
		record Forward(McpRequest request) implements Outcome {
		}

		/** Answer the agent here and send nothing upstream. */
		record Answer(int status, String contentType, byte[] body) implements Outcome {

			public static Answer json(int status, String json) {
				return new Answer(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
			}
		}

	}

	/**
	 * A request as the agent sent it to the proxy.
	 *
	 * @param server the configured name of the MCP server it is for
	 * @param method the HTTP method
	 * @param headers the agent's headers, minus those the proxy never forwards; names as
	 * received
	 * @param body the raw body, possibly empty
	 */
	record McpRequest(String server, String method, Map<String, List<String>> headers, byte[] body) {

		public McpRequest {
			Map<String, List<String>> copy = new LinkedHashMap<>();
			headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
			headers = Map.copyOf(copy);
		}

		/** Every value of a header, matched without regard to case. */
		public List<String> header(String name) {
			return headers.entrySet()
				.stream()
				.filter(e -> e.getKey().equalsIgnoreCase(name))
				.flatMap(e -> e.getValue().stream())
				.toList();
		}

		/** The same request without one header, matched without regard to case. */
		public McpRequest withoutHeader(String name) {
			Map<String, List<String>> kept = new LinkedHashMap<>(headers);
			kept.keySet().removeIf(key -> key.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
			return new McpRequest(server, method, kept, body);
		}

		public Outcome forward() {
			return new Outcome.Forward(this);
		}
	}

}
