package org.thought.acp.mcp.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DynamicClientRegistrationRequest;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpOAuth2DcrClientManager;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.mcp.McpCredentials;
import org.thought.acp.mcp.McpCredentialsProvider;
import org.thought.acp.session.SessionPrincipal;

/**
 * Credentials for MCP servers that follow the MCP authorization spec, one set per signed-in user.
 *
 * <p>Each protected server is its own OAuth client, registered once for the application —
 * discovered from the server's 401 and registered dynamically, through mcp-security — and keyed by
 * the server's configured name, which is also its Spring Security registration id. Tokens are per
 * (server, user) and live in an {@link OAuth2AuthorizedClientService}, the same store Spring
 * Security's own {@code oauth2Client()} flow writes to when the user comes back from signing in.
 * So the sign-in itself is ordinary Spring Security, and all this class adds is the bridge from a
 * session's principal to a bearer header.
 *
 * <p>Asked when a session opens, it refuses rather than guesses. A session on nobody's behalf is
 * refused outright: these tokens are a person's. A user with no token for a server gets
 * {@link ClientAuthorizationRequiredException}, which Spring Security's
 * {@code OAuth2AuthorizationRequestRedirectFilter} turns into the redirect to sign in, provided it
 * is thrown on a request thread — hence {@link #requireAuthorized}, for a controller to call before
 * it starts streaming.
 *
 * <p>Asked on every request after that, it hands back the stored token, refreshed when it has
 * expired. Refreshes are serialized per (server, user): an authorization server that rotates
 * refresh tokens — UAA does — honours each once, and two tool calls racing to refresh would cost
 * the user their sign-in. A refresh the server refuses removes the stored token (Spring
 * Security's own failure handling), so the next session sends the user to sign in again.
 */
public final class OAuth2McpCredentialsProvider implements McpCredentialsProvider {

	private static final Logger logger = LoggerFactory.getLogger(OAuth2McpCredentialsProvider.class);

	private final Map<String, McpServerSpec.Http> servers;

	private final McpClientRegistrationRepository registrations;

	private final McpOAuth2DcrClientManager clientManager;

	private final OAuth2AuthorizedClientService authorizedClients;

	private final OAuth2AuthorizedClientManager authorizedClientManager;

	private final Registration registration;

	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	/**
	 * How this application introduces itself when it registers with a server's authorization server.
	 *
	 * @param clientName shown to the user on the consent screen
	 * @param redirectUri the callback, with {@code {baseUrl}} and {@code {registrationId}} placeholders
	 * @param baseUrl where this application is reached from the user's browser, asked at registration
	 * time; empty when it cannot be known, which fails the registration with a message saying so
	 */
	public record Registration(String clientName, String redirectUri, Supplier<Optional<String>> baseUrl) {
	}

	/**
	 * @param servers the servers to authorize; any other server is left as configured
	 */
	public OAuth2McpCredentialsProvider(List<McpServerSpec.Http> servers, McpClientRegistrationRepository registrations,
			McpOAuth2DcrClientManager clientManager, OAuth2AuthorizedClientService authorizedClients,
			OAuth2AuthorizedClientManager authorizedClientManager, Registration registration) {
		Map<String, McpServerSpec.Http> byName = new LinkedHashMap<>();
		servers.forEach(server -> byName.put(server.name(), server));
		this.servers = Map.copyOf(byName);
		this.registrations = registrations;
		this.clientManager = clientManager;
		this.authorizedClients = authorizedClients;
		this.authorizedClientManager = authorizedClientManager;
		this.registration = registration;
	}

	@Override
	public Optional<McpCredentials> credentialsFor(McpServerSpec.Http server, SessionPrincipal principal) {
		McpServerSpec.Http protectedServer = servers.get(server.name());
		if (protectedServer == null) {
			return Optional.empty();
		}
		requireAuthorized(protectedServer, principal);
		String registrationId = protectedServer.name();
		String principalName = principal.name();
		return Optional.of(() -> Map.of("Authorization", "Bearer " + accessToken(registrationId, principalName)));
	}

	/**
	 * Throws unless {@code principal} can use every protected server now.
	 *
	 * <p>For a controller to call before it opens a session or starts a stream: on a request thread
	 * the {@link ClientAuthorizationRequiredException} becomes a redirect to sign in, where from
	 * inside a stream that has already started answering it could only become an error.
	 *
	 * @throws ClientAuthorizationRequiredException naming the first server the user has not signed in to
	 * @throws IllegalStateException if there is no principal at all
	 */
	public void requireAuthorized(SessionPrincipal principal) {
		servers.values().forEach(server -> requireAuthorized(server, principal));
	}

	/** Whether {@code principal} holds a token for the named server. Registers nothing. */
	public boolean isAuthorized(String serverName, SessionPrincipal principal) {
		return principal != null && servers.containsKey(serverName)
				&& authorizedClients.loadAuthorizedClient(serverName, principal.name()) != null;
	}

	/** The servers this provider authorizes, by name. */
	public List<String> serverNames() {
		return List.copyOf(servers.keySet());
	}

	private void requireAuthorized(McpServerSpec.Http server, SessionPrincipal principal) {
		if (principal == null) {
			throw new IllegalStateException("MCP server '" + server.name() + "' is called with each user's own "
					+ "OAuth token, and this session was opened on nobody's behalf; pass a SessionPrincipal or "
					+ "configure a SessionPrincipalResolver");
		}
		register(server);
		OAuth2AuthorizedClient authorized = authorizedClients.loadAuthorizedClient(server.name(), principal.name());
		if (authorized == null) {
			throw new ClientAuthorizationRequiredException(server.name());
		}
	}

	/**
	 * Registers this application with the server's authorization server, once.
	 *
	 * <p>Serialized per server so two users arriving together do not register twice; the manager
	 * itself skips a registration that already exists.
	 */
	private void register(McpServerSpec.Http server) {
		if (registrations.findByRegistrationId(server.name()) != null) {
			return;
		}
		synchronized (lock("register", server.name())) {
			if (registrations.findByRegistrationId(server.name()) != null) {
				return;
			}
			String redirectUri = registration.redirectUri().replace("{baseUrl}", baseUrl(server))
				.replace("{registrationId}", server.name());
			clientManager.registerMcpClient(server.name(), server.url().toString(),
					DynamicClientRegistrationRequest.builder()
						.clientName(registration.clientName())
						// authorization_code first: the manager takes the first as the registration's grant.
						.grantTypes(List.of(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN))
						.responseTypes(List.of(OAuth2AuthorizationResponseType.CODE))
						.redirectUris(List.of(redirectUri))
						// A public client: PKCE instead of a secret, so there is no secret to keep.
						.tokenEndpointAuthMethod(ClientAuthenticationMethod.NONE)
						.build());
			logger.info("Registered with the authorization server for MCP server '{}'", server.name());
		}
	}

	private String baseUrl(McpServerSpec.Http server) {
		return registration.baseUrl().get()
			.orElseThrow(() -> new IllegalStateException("Cannot register with the authorization server for MCP "
					+ "server '" + server.name() + "': the redirect URI needs this application's base URL, and "
					+ "there is no current request to read it from. Set spring.acp.mcp.oauth.base-url"));
	}

	/**
	 * The current token, refreshed if it has expired. One refresh at a time per (server, user).
	 */
	private String accessToken(String registrationId, String principalName) {
		synchronized (lock(registrationId, principalName)) {
			OAuth2AuthorizedClient client;
			try {
				client = authorizedClientManager.authorize(
						OAuth2AuthorizeRequest.withClientRegistrationId(registrationId).principal(principalName).build());
			}
			catch (ClientAuthorizationException ex) {
				// Never the exception's own message: some servers echo the grant back in it.
				logger.warn("MCP server '{}' refused to refresh a user's token ({}); they will be asked to sign in again",
						registrationId, ex.getError().getErrorCode());
				throw new IllegalStateException("The token for MCP server '" + registrationId
						+ "' could not be refreshed: " + ex.getError().getErrorCode());
			}
			if (client == null) {
				throw new IllegalStateException("No token for MCP server '" + registrationId + "'");
			}
			return client.getAccessToken().getTokenValue();
		}
	}

	private Object lock(String first, String second) {
		return locks.computeIfAbsent(first + '\u0000' + second, key -> new Object());
	}
}
