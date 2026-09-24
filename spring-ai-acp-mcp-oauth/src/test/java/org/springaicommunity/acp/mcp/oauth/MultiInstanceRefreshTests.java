package org.springaicommunity.acp.mcp.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DefaultMcpOAuth2DcrClientManager;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DynamicClientRegistrationService;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springaicommunity.acp.config.McpServerSpec;
import org.springaicommunity.acp.mcp.McpCredentials;
import org.springaicommunity.acp.session.SessionPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two instances of an application sharing one database, refreshing one user's expired
 * token at the same moment.
 *
 * <p>
 * Each instance here has its own provider, token service, registration cache and in-JVM
 * locks — everything an instance does not share — and only the database in common. The
 * fake authorization server honours each refresh token once, as the Tanzu MCP gateway
 * does, so a second refresh racing the first is refused and would cost the user their
 * sign-in.
 */
class MultiInstanceRefreshTests {

	private static final SessionPrincipal ALICE = SessionPrincipal.of("alice");

	private final FakeMcpAuthorizationServer fake = new FakeMcpAuthorizationServer();

	private final McpServerSpec.Http finops = new McpServerSpec.Http("finops-mcp", fake.mcpUrl(), Map.of());

	private final EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
		.generateUniqueName(true)
		.addScript("org/springaicommunity/acp/mcp/oauth/acp-mcp-oauth-schema.sql")
		.addScript("org/springframework/security/oauth2/client/oauth2-client-schema.sql")
		.build();

	private final JdbcTemplate jdbc = new JdbcTemplate(database);

	{
		// H2 gives up on a row lock after a second by default; a refresh held back by the
		// fake takes longer.
		jdbc.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
	}

	@AfterEach
	void close() {
		fake.close();
		database.shutdown();
	}

	/** One instance: nothing shared with another but the database. */
	private record Instance(OAuth2McpCredentialsProvider provider, JdbcOAuth2AuthorizedClientService tokens,
			JdbcMcpClientRegistrationRepository registrations) {
	}

	private Instance instance(Function<JdbcTemplate, RefreshLock> lock) {
		JdbcMcpClientRegistrationRepository registrations = new JdbcMcpClientRegistrationRepository(jdbc);
		JdbcOAuth2AuthorizedClientService tokens = new JdbcOAuth2AuthorizedClientService(jdbc, registrations);
		DefaultUrlValidator urlValidator = new DefaultUrlValidator(true);
		OAuth2McpCredentialsProvider provider = new OAuth2McpCredentialsProvider(List.of(finops), registrations,
				new DefaultMcpOAuth2DcrClientManager(registrations, new DynamicClientRegistrationService(urlValidator),
						new McpMetadataDiscoveryService(urlValidator), urlValidator),
				tokens, McpAuthorizedClientManagers.create(registrations, tokens), "Capacity Agent",
				McpSignIn.redirect("{baseUrl}/login/oauth2/code/{registrationId}",
						() -> Optional.of("https://agent.example.com")),
				lock.apply(jdbc));
		return new Instance(provider, tokens, registrations);
	}

	private static RefreshLock databaseLock(JdbcTemplate jdbc) {
		return new JdbcRefreshLock(new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
				jdbc);
	}

	/**
	 * Alice signed in through the first instance an hour and more ago; her access token
	 * has expired.
	 */
	private void aliceSignedInLongAgo(Instance through) {
		assertThatThrownBy(() -> through.provider().credentialsFor(finops, ALICE))
			.isInstanceOf(ClientAuthorizationRequiredException.class);
		String[] tokens = fake.issueTokens();
		Instant now = Instant.now();
		through.tokens()
			.saveAuthorizedClient(
					new OAuth2AuthorizedClient(through.registrations().findByRegistrationId("finops-mcp"), "alice",
							new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokens[0], now.minusSeconds(7200),
									now.minusSeconds(3600)),
							new OAuth2RefreshToken(tokens[1], now.minusSeconds(7200))),
					new TestingAuthenticationToken("alice", null));
	}

	/** Four tool calls on each instance at once, all needing Alice's token. */
	private Race race(Instance first, Instance second) throws InterruptedException {
		McpCredentials a = first.provider().credentialsFor(finops, ALICE).orElseThrow();
		McpCredentials b = second.provider().credentialsFor(finops, ALICE).orElseThrow();
		fake.delayTokens(Duration.ofMillis(300));
		List<String> tokens = new CopyOnWriteArrayList<>();
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(8);
		for (int i = 0; i < 8; i++) {
			McpCredentials credentials = i % 2 == 0 ? a : b;
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					tokens.add(credentials.headers().get("Authorization"));
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
		assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
		return new Race(tokens, failures);
	}

	private record Race(List<String> tokens, List<Throwable> failures) {
	}

	@Test
	void withOnlyInProcessLocksTwoInstancesBothRefreshAndOneIsRefused() throws Exception {
		// The failure this class exists for, shown rather than asserted about in prose.
		Instance first = instance(jdbc -> RefreshLock.inProcess());
		Instance second = instance(jdbc -> RefreshLock.inProcess());
		aliceSignedInLongAgo(first);

		Race race = race(first, second);

		// Both instances refreshed with the same refresh token and the loser was refused.
		// The refusal also
		// removes Alice's stored token — whether before or after the winner saves its new
		// one is a matter
		// of timing, which is to say that sometimes the race signs her out entirely.
		assertThat(fake.refreshCount()).isEqualTo(2);
		assertThat(race.failures()).anySatisfy(failure -> assertThat(failure).hasMessageContaining("invalid_grant"));
	}

	@Test
	void withTheDatabaseLockTheInstancesRefreshOnceBetweenThem() throws Exception {
		Instance first = instance(MultiInstanceRefreshTests::databaseLock);
		Instance second = instance(MultiInstanceRefreshTests::databaseLock);
		aliceSignedInLongAgo(first);

		Race race = race(first, second);

		assertThat(race.failures()).isEmpty();
		assertThat(fake.refreshCount()).isOne();
		assertThat(race.tokens()).hasSize(8).containsOnly(race.tokens().get(0));
		assertThat(race.tokens().get(0)).isEqualTo("Bearer " + second.tokens()
			.<OAuth2AuthorizedClient>loadAuthorizedClient("finops-mcp", "alice")
			.getAccessToken()
			.getTokenValue());
	}

	@Test
	void aRefusalUnderTheDatabaseLockStillSendsTheUserBackToSignIn() {
		Instance first = instance(MultiInstanceRefreshTests::databaseLock);
		Instance second = instance(MultiInstanceRefreshTests::databaseLock);
		aliceSignedInLongAgo(first);
		McpCredentials credentials = first.provider().credentialsFor(finops, ALICE).orElseThrow();
		fake.revokeRefreshTokens();

		assertThatThrownBy(credentials::headers).hasMessageContaining("invalid_grant");

		// Committed, not rolled back with the failure: every instance now sends her to
		// sign in.
		assertThat(second.tokens().<OAuth2AuthorizedClient>loadAuthorizedClient("finops-mcp", "alice")).isNull();
		assertThatThrownBy(() -> second.provider().credentialsFor(finops, ALICE))
			.isInstanceOf(ClientAuthorizationRequiredException.class);
	}

	@Test
	void aFreshTokenIsHandedOutWithoutTakingTheLock() {
		RefreshLock refusing = new RefreshLock() {
			@Override
			public <T> T whileLocked(String registrationId, String principalName,
					java.util.function.Supplier<T> action) {
				throw new AssertionError("a fresh token must not need the lock");
			}
		};
		Instance instance = instance(jdbc -> refusing);
		assertThatThrownBy(() -> instance.provider().credentialsFor(finops, ALICE))
			.isInstanceOf(ClientAuthorizationRequiredException.class);
		String[] tokens = fake.issueTokens();
		Instant now = Instant.now();
		instance.tokens()
			.saveAuthorizedClient(
					new OAuth2AuthorizedClient(instance.registrations().findByRegistrationId("finops-mcp"), "alice",
							new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokens[0], now,
									now.plusSeconds(3600)),
							new OAuth2RefreshToken(tokens[1], now)),
					new TestingAuthenticationToken("alice", null));

		assertThat(instance.provider().credentialsFor(finops, ALICE).orElseThrow().headers().get("Authorization"))
			.isEqualTo("Bearer " + tokens[0]);
	}

}
