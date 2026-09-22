package org.thought.acp.mcp.oauth;

import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;

/**
 * The {@link OAuth2AuthorizedClientManager} the proxy gets tokens from.
 *
 * <p>The service-backed manager rather than the request-backed default, because the proxy asks from
 * its own threads, where there is no servlet request to hand one. It can refresh a token but not
 * obtain the first one: that takes the user's browser, which is Spring Security's
 * {@code oauth2Client()} flow on a request, and without a stored token this manager answers
 * {@code ClientAuthorizationRequiredException} — the same signal that flow redirects on.
 *
 * <p>Refreshes carry {@code resource=}, the RFC 8707 indicator the MCP authorization spec requires
 * on every token request. Measured against the Tanzu MCP gateway: tokens come back with {@code aud}
 * set to the server's URL, and a new refresh token each time.
 */
public final class McpAuthorizedClientManagers {

	private McpAuthorizedClientManagers() {
	}

	public static OAuth2AuthorizedClientManager create(McpClientRegistrationRepository registrations,
			OAuth2AuthorizedClientService authorizedClients) {
		RestClientRefreshTokenTokenResponseClient refresh = new RestClientRefreshTokenTokenResponseClient();
		refresh.addParametersConverter(request -> {
			var parameters = new org.springframework.util.LinkedMultiValueMap<String, String>();
			String resource = registrations
				.findResourceIdByRegistrationId(request.getClientRegistration().getRegistrationId());
			if (resource != null) {
				parameters.add("resource", resource);
			}
			return parameters;
		});
		AuthorizedClientServiceOAuth2AuthorizedClientManager manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
				registrations, authorizedClients);
		manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
			.authorizationCode()
			.refreshToken(r -> r.accessTokenResponseClient(refresh))
			.build());
		return manager;
	}
}
