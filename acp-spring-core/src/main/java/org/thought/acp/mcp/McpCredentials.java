package org.thought.acp.mcp;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The credentials for one MCP server, on behalf of one session, asked for afresh on every request.
 *
 * <p>An interface rather than a header map because the map an agent is given in
 * {@code session/new} is frozen for the life of the session, and the tokens worth protecting are
 * the ones that expire: an OAuth access token good for a few hours will not outlive a long
 * conversation. The loopback proxy calls {@link #headers()} for every request it forwards, so a
 * refresh lands between two tool calls instead of failing one.
 *
 * <p><strong>Called concurrently.</strong> Two tool calls in one turn can arrive at once, and an
 * implementation that refreshes must make sure only one of them does: an authorization server
 * that rotates refresh tokens honours each one once, so two racing refreshes can cost the user
 * their sign-in.
 */
@FunctionalInterface
public interface McpCredentials {

	/**
	 * The headers to add to the next request, typically just {@code Authorization}.
	 *
	 * <p>These replace any header of the same name the agent sent. A failure thrown from here is
	 * answered to the agent as a proxy error for that one request; the session carries on.
	 */
	Map<String, String> headers();

	/** A bearer token read at request time, so whatever supplies it can rotate it. */
	static McpCredentials bearer(Supplier<String> token) {
		return () -> {
			String value = token.get();
			return value == null || value.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + value);
		};
	}

	/** Fixed headers: an API key, say, that still ought not to be handed to the agent. */
	static McpCredentials of(Map<String, String> headers) {
		Map<String, String> copy = Map.copyOf(headers);
		return () -> copy;
	}
}
