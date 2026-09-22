package org.springaicommunity.acp.mcp.oauth;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DefaultMcpOAuth2DcrClientManager;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DynamicClientRegistrationService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.InMemoryMcpClientRegistrationRepository;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springaicommunity.acp.config.McpServerSpec;
import org.springaicommunity.acp.mcp.McpCredentials;
import org.springaicommunity.acp.session.SessionPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider with real Spring Security and mcp-security objects against a fake MCP server and
 * authorization server that behave as the Tanzu MCP gateway was measured to.
 */
class OAuth2McpCredentialsProviderTests {

	private static final SessionPrincipal ALICE = SessionPrincipal.of("alice");

	private static final SessionPrincipal BOB = SessionPrincipal.of("bob");

	private final FakeMcpAuthorizationServer fake = new FakeMcpAuthorizationServer();

	private final InMemoryMcpClientRegistrationRepository registrations = new InMemoryMcpClientRegistrationRepository();

	private final InMemoryOAuth2AuthorizedClientService authorizedClients = new InMemoryOAuth2AuthorizedClientService(
			registrations);

	private final McpServerSpec.Http finops = new McpServerSpec.Http("finops-mcp", fake.mcpUrl(), Map.of());

	@AfterEach
	void close() {
		fake.close();
	}

	private OAuth2McpCredentialsProvider provider(Supplier<Optional<String>> baseUrl) {
		DefaultUrlValidator urlValidator = new DefaultUrlValidator(true);
		return new OAuth2McpCredentialsProvider(List.of(finops), registrations,
				new DefaultMcpOAuth2DcrClientManager(registrations, new DynamicClientRegistrationService(urlValidator),
						new McpMetadataDiscoveryService(urlValidator), urlValidator),
				authorizedClients, McpAuthorizedClientManagers.create(registrations, authorizedClients), "Capacity Agent",
				McpSignIn.redirect("{baseUrl}/login/oauth2/code/{registrationId}", baseUrl));
	}

	private OAuth2McpCredentialsProvider provider() {
		return provider(() -> Optional.of("https://agent.example.com"));
	}

	/** What a finished sign-in leaves behind: the user's tokens in the authorized-client service. */
	private void signIn(SessionPrincipal user, boolean expired) {
		String[] tokens = fake.issueTokens();
		Instant now = Instant.now();
		OAuth2AccessToken access = expired
				? new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokens[0], now.minusSeconds(7200),
						now.minusSeconds(3600))
				: new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokens[0], now, now.plusSeconds(3600));
		authorizedClients.saveAuthorizedClient(
				new OAuth2AuthorizedClient(registrations.findByRegistrationId("finops-mcp"), user.name(), access,
						new OAuth2RefreshToken(tokens[1], now)),
				new TestingAuthenticationToken(user.name(), null));
	}

	private static String token(McpCredentials credentials) {
		return credentials.headers().get("Authorization");
	}

	/** Registers the client the way a first session would, which is what a sign-in needs to exist. */
	private void register(OAuth2McpCredentialsProvider provider) {
		assertThatThrownBy(() -> provider.credentialsFor(finops, ALICE))
			.isInstanceOf(ClientAuthorizationRequiredException.class);
	}

	// --- which servers, and for whom ----------------------------------------------------------

	@Test
	void aServerNotConfiguredForOAuthIsLeftAlone() {
		McpServerSpec.Http other = new McpServerSpec.Http("other", URI.create("https://other.example.com/mcp"), Map.of());

		assertThat(provider().credentialsFor(other, ALICE)).isEmpty();
		assertThat(fake.registrations).isEmpty();
	}

	@Test
	void aSessionOnNobodysBehalfIsRefusedBeforeAnythingIsRegistered() {
		assertThatThrownBy(() -> provider().credentialsFor(finops, null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("finops-mcp").hasMessageContaining("SessionPrincipal");
		assertThat(fake.registrations).isEmpty();
	}

	// --- registering the application ----------------------------------------------------------

	@Test
	void aUserWhoHasNotSignedInIsSentToSignInAndTheApplicationIsRegisteredOnceForEveryone() {
		OAuth2McpCredentialsProvider provider = provider();

		assertThatThrownBy(() -> provider.credentialsFor(finops, ALICE))
			.isInstanceOf(ClientAuthorizationRequiredException.class)
			.satisfies(ex -> assertThat(((ClientAuthorizationRequiredException) ex).getClientRegistrationId())
				.isEqualTo("finops-mcp"));
		assertThatThrownBy(() -> provider.requireAuthorized(BOB)).isInstanceOf(ClientAuthorizationRequiredException.class);

		assertThat(fake.registrations).singleElement().satisfies(registration -> {
			assertThat(registration.get("client_name")).isEqualTo("Capacity Agent");
			assertThat(registration.get("redirect_uris"))
				.isEqualTo(List.of("https://agent.example.com/login/oauth2/code/finops-mcp"));
			assertThat(registration.get("grant_types")).isEqualTo(List.of("authorization_code", "refresh_token"));
			assertThat(registration.get("token_endpoint_auth_method")).isEqualTo("none");
		});
		assertThat(registrations.findResourceIdByRegistrationId("finops-mcp")).isEqualTo(fake.mcpUrl().toString());
		assertThat(registrations.findByRegistrationId("finops-mcp").getRedirectUri())
			.isEqualTo("https://agent.example.com/login/oauth2/code/finops-mcp");
	}

	@Test
	void withoutABaseUrlTheRegistrationSaysWhatToSet() {
		assertThatThrownBy(() -> provider(Optional::empty).credentialsFor(finops, ALICE))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.acp.mcp.oauth.base-url");
		assertThat(fake.registrations).isEmpty();
	}

	// --- tokens -------------------------------------------------------------------------------

	@Test
	void aSignedInUsersTokenIsSentAsIs() {
		OAuth2McpCredentialsProvider provider = provider();
		register(provider);
		signIn(ALICE, false);

		McpCredentials credentials = provider.credentialsFor(finops, ALICE).orElseThrow();

		assertThat(token(credentials)).startsWith("Bearer access-");
		assertThat(fake.refreshCount()).isZero();
		assertThat(provider.isAuthorized("finops-mcp", ALICE)).isTrue();
		assertThat(provider.isAuthorized("finops-mcp", BOB)).isFalse();
	}

	@Test
	void anExpiredTokenIsRefreshedWithTheResourceIndicatorAndTheRotatedRefreshTokenKept() {
		OAuth2McpCredentialsProvider provider = provider();
		register(provider);
		signIn(ALICE, true);
		String before = authorizedClients.loadAuthorizedClient("finops-mcp", "alice").getRefreshToken().getTokenValue();

		String bearer = token(provider.credentialsFor(finops, ALICE).orElseThrow());

		Map<String, String> refresh = fake.tokenRequests.get(0);
		assertThat(refresh).containsEntry("grant_type", "refresh_token").containsEntry("resource",
				fake.mcpUrl().toString());
		OAuth2AuthorizedClient stored = authorizedClients.loadAuthorizedClient("finops-mcp", "alice");
		assertThat(bearer).isEqualTo("Bearer " + stored.getAccessToken().getTokenValue());
		assertThat(stored.getRefreshToken().getTokenValue()).isNotEqualTo(before);
	}

	@Test
	void concurrentRequestsWithAnExpiredTokenRefreshItOnce() throws Exception {
		OAuth2McpCredentialsProvider provider = provider();
		register(provider);
		signIn(ALICE, true);
		McpCredentials credentials = provider.credentialsFor(finops, ALICE).orElseThrow();
		fake.delayTokens(Duration.ofMillis(200));
		List<String> seen = new CopyOnWriteArrayList<>();
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(8);

		for (int i = 0; i < 8; i++) {
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					seen.add(token(credentials));
				}
				catch (Throwable ex) {
					failures.add(ex);
				}
				finally {
					done.countDown();
				}
			});
		}
		start.countDown();

		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		// The fake honours each refresh token once; a second concurrent refresh would have failed.
		assertThat(failures).isEmpty();
		assertThat(fake.refreshCount()).isOne();
		assertThat(seen).hasSize(8).containsOnly(seen.get(0));
	}

	@Test
	void aRefusedRefreshFailsTheRequestAndSendsTheUserBackToSignIn() {
		OAuth2McpCredentialsProvider provider = provider();
		register(provider);
		signIn(ALICE, true);
		McpCredentials credentials = provider.credentialsFor(finops, ALICE).orElseThrow();
		fake.revokeRefreshTokens();

		assertThatThrownBy(() -> token(credentials)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("invalid_grant").hasMessageNotContaining("refresh-");

		assertThat(authorizedClients.<OAuth2AuthorizedClient>loadAuthorizedClient("finops-mcp", "alice")).isNull();
		assertThatThrownBy(() -> provider.credentialsFor(finops, ALICE))
			.isInstanceOf(ClientAuthorizationRequiredException.class);
	}

	@Test
	void twoUsersNeverShareAToken() {
		OAuth2McpCredentialsProvider provider = provider();
		register(provider);
		signIn(ALICE, false);
		signIn(BOB, false);

		String alice = token(provider.credentialsFor(finops, ALICE).orElseThrow());
		String bob = token(provider.credentialsFor(finops, BOB).orElseThrow());

		assertThat(alice).isNotEqualTo(bob);
		assertThat(alice).isEqualTo(
				"Bearer " + authorizedClients.loadAuthorizedClient("finops-mcp", "alice").getAccessToken().getTokenValue());
	}
}
