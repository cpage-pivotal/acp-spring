package org.thought.acp.mcp.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * refused outright: these tokens are a person's. A user with no token for a server is handed to the
 * {@link McpSignIn}: in a web application that throws {@link ClientAuthorizationRequiredException},
 * which Spring Security's {@code OAuth2AuthorizationRequestRedirectFilter} turns into the redirect to
 * sign in, provided it is thrown on a request thread — hence {@link #requireAuthorized}, for a
 * controller to call before it starts streaming. In a terminal application it opens a browser and
 * waits, and the session opens once the user is back.
 *
 * <p>Asked on every request after that, it hands back the stored token, refreshed when it has
 * expired. Refreshes are serialized per (server, user) by a {@link RefreshLock} — across instances
 * when they share a database: an authorization server that rotates refresh tokens — UAA does —
 * honours each once, and two tool calls racing to refresh would cost the user their sign-in. A refresh the server refuses removes the stored token (Spring
 * Security's own failure handling), so the next session sends the user to sign in again.
 */
public final class OAuth2McpCredentialsProvider implements McpCredentialsProvider {

	private static final Logger logger = LoggerFactory.getLogger(OAuth2McpCredentialsProvider.class);

	private final Map<String, McpServerSpec.Http> servers;

	private final McpClientRegistrationRepository registrations;

	private final McpOAuth2DcrClientManager clientManager;

	private final OAuth2AuthorizedClientService authorizedClients;

	private final OAuth2AuthorizedClientManager authorizedClientManager;

	private final String clientName;

	private final McpSignIn signIn;

	private final RefreshLock refreshLock;

	/** Guards registration and sign-in, which only ever happen in the JVM the user is talking to. */
	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	/**
	 * How long before it expires a token counts as expired: the same minute Spring Security's refresh
	 * provider allows, so a token this class hands out as fresh is one the manager would not refresh.
	 */
	private static final java.time.Duration CLOCK_SKEW = java.time.Duration.ofSeconds(60);

	/**
	 * @param servers the servers to authorize; any other server is left as configured
	 * @param clientName how this application introduces itself when it registers: shown to the user
	 * on the consent screen
	 * @param signIn what happens for a user with no token: a redirect in a web application, a
	 * browser sign-in run on the spot in a terminal one
	 */
	public OAuth2McpCredentialsProvider(List<McpServerSpec.Http> servers, McpClientRegistrationRepository registrations,
			McpOAuth2DcrClientManager clientManager, OAuth2AuthorizedClientService authorizedClients,
			OAuth2AuthorizedClientManager authorizedClientManager, String clientName, McpSignIn signIn) {
		this(servers, registrations, clientManager, authorizedClients, authorizedClientManager, clientName, signIn,
				RefreshLock.inProcess());
	}

	/**
	 * @param refreshLock serializes refreshes per (server, user); {@link RefreshLock#inProcess()} for
	 * one instance, {@code JdbcRefreshLock} for several sharing a database
	 */
	public OAuth2McpCredentialsProvider(List<McpServerSpec.Http> servers, McpClientRegistrationRepository registrations,
			McpOAuth2DcrClientManager clientManager, OAuth2AuthorizedClientService authorizedClients,
			OAuth2AuthorizedClientManager authorizedClientManager, String clientName, McpSignIn signIn,
			RefreshLock refreshLock) {
		this.refreshLock = refreshLock;
		Map<String, McpServerSpec.Http> byName = new LinkedHashMap<>();
		servers.forEach(server -> byName.put(server.name(), server));
		this.servers = Map.copyOf(byName);
		this.registrations = registrations;
		this.clientManager = clientManager;
		this.authorizedClients = authorizedClients;
		this.authorizedClientManager = authorizedClientManager;
		this.clientName = clientName;
		this.signIn = signIn;
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
		if (authorizedClients.loadAuthorizedClient(server.name(), principal.name()) != null) {
			return;
		}
		// One sign-in at a time per (server, user): two sessions opening together must not open two browsers.
		synchronized (lock(server.name(), principal.name())) {
			if (authorizedClients.loadAuthorizedClient(server.name(), principal.name()) != null) {
				return;
			}
			try {
				signIn.signIn(server, principal);
			}
			catch (McpSignIn.StaleRegistration ex) {
				logger.info("Registering again for MCP server '{}': {}", server.name(), ex.getMessage());
				register(server);
				signIn.signIn(server, principal);
			}
		}
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
			String redirectUri = signIn.redirectUri(server);
			clientManager.registerMcpClient(server.name(), server.url().toString(),
					DynamicClientRegistrationRequest.builder()
						.clientName(clientName)
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

	/**
	 * The current token, refreshed if it has expired.
	 *
	 * <p>A fresh token is handed out without taking the lock — the common case, and the one every
	 * request pays for. An expired one is refreshed under the {@link RefreshLock}, where the manager
	 * re-reads the stored token first: if another thread or instance refreshed it while this one
	 * waited, it is fresh now and nothing is sent.
	 */
	private String accessToken(String registrationId, String principalName) {
		OAuth2AuthorizedClient stored = authorizedClients.loadAuthorizedClient(registrationId, principalName);
		if (stored != null && isFresh(stored.getAccessToken())) {
			return stored.getAccessToken().getTokenValue();
		}
		Refresh refresh = refreshLock.whileLocked(registrationId, principalName,
				() -> refresh(registrationId, principalName));
		if (refresh.refusal() != null) {
			// Never the exception's own message: some servers echo the grant back in it.
			logger.warn("MCP server '{}' refused to refresh a user's token ({}); they will be asked to sign in again",
					registrationId, refresh.refusal());
			throw new IllegalStateException("The token for MCP server '" + registrationId
					+ "' could not be refreshed: " + refresh.refusal());
		}
		if (refresh.token() == null) {
			throw new IllegalStateException("The user is no longer signed in to MCP server '" + registrationId + "'");
		}
		return refresh.token();
	}

	/**
	 * Refreshes under the lock, and reports a refusal as a value rather than throwing it.
	 *
	 * <p>The difference matters with a database lock. A refused refresh makes Spring Security remove
	 * the stored token, so the user signs in again next time; an exception leaving the transaction
	 * would roll that removal back and leave a dead token to be refused on every request after.
	 */
	private Refresh refresh(String registrationId, String principalName) {
		try {
			OAuth2AuthorizedClient client = authorizedClientManager
				.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId).principal(principalName).build());
			return new Refresh(client == null ? null : client.getAccessToken().getTokenValue(), null);
		}
		catch (ClientAuthorizationRequiredException ex) {
			// No stored token at all: signed out since this session opened, by a refusal elsewhere.
			return new Refresh(null, null);
		}
		catch (ClientAuthorizationException ex) {
			return new Refresh(null, ex.getError().getErrorCode());
		}
	}

	private record Refresh(String token, String refusal) {
	}

	private static boolean isFresh(org.springframework.security.oauth2.core.OAuth2AccessToken token) {
		return token.getExpiresAt() == null || token.getExpiresAt().isAfter(java.time.Instant.now().plus(CLOCK_SKEW));
	}

	private Object lock(String first, String second) {
		return locks.computeIfAbsent(first + '\u0000' + second, key -> new Object());
	}
}
