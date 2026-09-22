package org.thought.acp.mcp.oauth;

import org.thought.acp.config.McpServerSpec;
import org.thought.acp.session.SessionPrincipal;

/**
 * How a user who has no token for an MCP server comes to have one.
 *
 * <p>Two answers, one per kind of application. A web application cannot sign anyone in from inside
 * a call: it throws, and Spring Security's redirect filter sends the user's browser on the trip
 * ({@link #redirect}). A terminal application has the user at the keyboard and a browser on the same
 * machine, so it runs the whole authorization-code flow itself against a loopback redirect
 * ({@code LoopbackSignIn}). The provider does not care which; it asks for a redirect URI when it
 * registers and for a sign-in when a token is missing.
 */
public interface McpSignIn {

	/** The redirect URI to register with {@code server}'s authorization server. */
	String redirectUri(McpServerSpec.Http server);

	/**
	 * Obtains and stores a token for {@code principal}, or throws.
	 *
	 * @throws StaleRegistration if the registration cannot be used and should be made again
	 */
	void signIn(McpServerSpec.Http server, SessionPrincipal principal);

	/**
	 * The registration this sign-in was handed cannot work — its loopback port is taken — and has been
	 * forgotten, so registering again and retrying once is the fix.
	 */
	final class StaleRegistration extends RuntimeException {

		public StaleRegistration(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * The web answer: registers {@code redirectUri} (with {@code {baseUrl}} and
	 * {@code {registrationId}} placeholders) and, for a missing token, throws Spring Security's
	 * {@code ClientAuthorizationRequiredException} for its redirect filter to act on.
	 */
	static McpSignIn redirect(String redirectUri, java.util.function.Supplier<java.util.Optional<String>> baseUrl) {
		return new McpSignIn() {
			@Override
			public String redirectUri(McpServerSpec.Http server) {
				String base = baseUrl.get()
					.orElseThrow(() -> new IllegalStateException("Cannot register with the authorization server for "
							+ "MCP server '" + server.name() + "': the redirect URI needs this application's base URL, "
							+ "and there is no current request to read it from. Set spring.acp.mcp.oauth.base-url"));
				return redirectUri.replace("{baseUrl}", base).replace("{registrationId}", server.name());
			}

			@Override
			public void signIn(McpServerSpec.Http server, SessionPrincipal principal) {
				throw new org.springframework.security.oauth2.client.ClientAuthorizationRequiredException(server.name());
			}
		};
	}
}
