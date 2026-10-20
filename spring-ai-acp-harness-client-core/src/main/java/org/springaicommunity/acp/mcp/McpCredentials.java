package org.springaicommunity.acp.mcp;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springaicommunity.acp.config.Validation;

/**
 * The credentials for one MCP server, on behalf of one session, asked for afresh on every
 * request.
 *
 * <p>
 * An interface rather than a header map because the map an agent is given in
 * {@code session/new} is frozen for the life of the session, and the tokens worth
 * protecting are the ones that expire: an OAuth access token good for a few hours will
 * not outlive a long conversation. The loopback proxy calls {@link #headers()} for every
 * request it forwards, so a refresh lands between two tool calls instead of failing one.
 *
 * <p>
 * <strong>Called concurrently.</strong> Two tool calls in one turn can arrive at once,
 * and an implementation that refreshes must make sure only one of them does: an
 * authorization server that rotates refresh tokens honours each one once, so two racing
 * refreshes can cost the user their sign-in.
 */
@FunctionalInterface
public interface McpCredentials {

	/**
	 * The headers to add to the next request, typically just {@code Authorization}.
	 *
	 * <p>
	 * These replace any header of the same name the agent sent. A failure thrown from
	 * here is answered to the agent as a proxy error for that one request; the session
	 * carries on.
	 */
	Map<String, String> headers();

	/**
	 * Where the proxy sends this session's requests, when that is not the URL the server
	 * was configured with.
	 *
	 * <p>
	 * For a server whose location is issued together with its credential — a credential
	 * broker that answers with a token and the endpoint it is good for — rather than
	 * known when the application is configured. Such a server can be declared without a
	 * URL at all; see {@code McpServerSpec.Http.provided}. Read once, when the session is
	 * granted, not per request: the agent is handed one loopback URL per server for the
	 * life of the session, and it should reach one upstream through it.
	 * @return the upstream, or empty to use the configured URL
	 */
	default Optional<URI> upstream() {
		return Optional.empty();
	}

	/**
	 * Credentials for an upstream this provider chose, rather than the configured one.
	 * @param upstream the server's real URL; https, or http only for loopback and
	 * {@code .apps.internal}
	 * @param credentials the headers to send there, asked for on every request as usual
	 */
	static McpCredentials routed(URI upstream, McpCredentials credentials) {
		URI url = Validation.requireSecureUrl(upstream, "mcp server upstream url");
		McpCredentials headers = credentials == null ? NONE : credentials;
		return new McpCredentials() {

			@Override
			public Map<String, String> headers() {
				return headers.headers();
			}

			@Override
			public Optional<URI> upstream() {
				return Optional.of(url);
			}

		};
	}

	/**
	 * Nothing to add: a server routed through the proxy for some reason other than a
	 * credential.
	 */
	McpCredentials NONE = Map::of;

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
