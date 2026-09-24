package org.springaicommunity.acp.mcp.oauth;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMcpClientRegistrationRepositoryTests {

	private final EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
		.generateUniqueName(true)
		.addScript("org/springaicommunity/acp/mcp/oauth/acp-mcp-oauth-schema.sql")
		.addScript("org/springframework/security/oauth2/client/oauth2-client-schema.sql")
		.build();

	private final JdbcTemplate jdbc = new JdbcTemplate(database);

	@AfterEach
	void close() {
		database.shutdown();
	}

	private static ClientRegistration registration(String clientId) {
		return ClientRegistration.withRegistrationId("finops-mcp")
			.clientId(clientId)
			.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("https://agent.example.com/login/oauth2/code/finops-mcp")
			.clientName("Capacity Agent")
			.authorizationUri("https://gateway.example.com/finops-mcp/authorize")
			.tokenUri("https://gateway.example.com/finops-mcp/token")
			.issuerUri("https://gateway.example.com/finops-mcp")
			.build();
	}

	@Test
	void aRegistrationSurvivesARestartAndReachesEveryInstance() {
		new JdbcMcpClientRegistrationRepository(jdbc).addClientRegistration(registration("client-1"),
				"https://gateway.example.com/finops-mcp/mcp");

		JdbcMcpClientRegistrationRepository another = new JdbcMcpClientRegistrationRepository(jdbc);
		ClientRegistration read = another.findByRegistrationId("finops-mcp");

		assertThat(read.getClientId()).isEqualTo("client-1");
		assertThat(read.getClientSecret()).isEmpty();
		assertThat(read.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
		assertThat(read.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
		assertThat(read.getRedirectUri()).isEqualTo("https://agent.example.com/login/oauth2/code/finops-mcp");
		assertThat(read.getProviderDetails().getTokenUri()).isEqualTo("https://gateway.example.com/finops-mcp/token");
		assertThat(read.getProviderDetails().getIssuerUri()).isEqualTo("https://gateway.example.com/finops-mcp");
		assertThat(read.getScopes()).isEmpty();
		assertThat(another.findResourceIdByRegistrationId("finops-mcp"))
			.isEqualTo("https://gateway.example.com/finops-mcp/mcp");
		assertThat(another.findByRegistrationId("unknown")).isNull();
	}

	@Test
	void whenTwoInstancesRegisterAtOnceTheFirstRegistrationWinsForBoth() {
		JdbcMcpClientRegistrationRepository first = new JdbcMcpClientRegistrationRepository(jdbc);
		JdbcMcpClientRegistrationRepository second = new JdbcMcpClientRegistrationRepository(jdbc);

		first.addClientRegistration(registration("client-1"), "https://gateway.example.com/finops-mcp/mcp");
		second.addClientRegistration(registration("client-2"), "https://gateway.example.com/finops-mcp/mcp");

		assertThat(first.findByRegistrationId("finops-mcp").getClientId()).isEqualTo("client-1");
		assertThat(second.findByRegistrationId("finops-mcp").getClientId()).isEqualTo("client-1");
	}

	@Test
	void aScopeStepUpIsWrittenThrough() {
		JdbcMcpClientRegistrationRepository repository = new JdbcMcpClientRegistrationRepository(jdbc);
		repository.addClientRegistration(registration("client-1"), "https://gateway.example.com/finops-mcp/mcp");

		repository.updateClientRegistration("finops-mcp", builder -> builder.scope("tickets:read", "tickets:write"));

		assertThat(new JdbcMcpClientRegistrationRepository(jdbc).findByRegistrationId("finops-mcp").getScopes())
			.containsExactlyInAnyOrder("tickets:read", "tickets:write");
		assertThat(repository.findResourceIdByRegistrationId("finops-mcp"))
			.isEqualTo("https://gateway.example.com/finops-mcp/mcp");
	}

	@Test
	void springSecuritysTokenStoreWorksOnTopOfIt() {
		JdbcMcpClientRegistrationRepository repository = new JdbcMcpClientRegistrationRepository(jdbc);
		repository.addClientRegistration(registration("client-1"), "https://gateway.example.com/finops-mcp/mcp");
		JdbcOAuth2AuthorizedClientService tokens = new JdbcOAuth2AuthorizedClientService(jdbc, repository);
		Instant now = Instant.now();

		tokens.saveAuthorizedClient(
				new OAuth2AuthorizedClient(repository.findByRegistrationId("finops-mcp"), "alice",
						new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-1", now,
								now.plusSeconds(3600), Set.of("openid")),
						new OAuth2RefreshToken("refresh-1", now)),
				new TestingAuthenticationToken("alice", null));

		OAuth2AuthorizedClient loaded = new JdbcOAuth2AuthorizedClientService(jdbc,
				new JdbcMcpClientRegistrationRepository(jdbc))
			.loadAuthorizedClient("finops-mcp", "alice");
		assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo("access-1");
		assertThat(loaded.getRefreshToken().getTokenValue()).isEqualTo("refresh-1");
		assertThat(loaded.getClientRegistration().getClientId()).isEqualTo("client-1");
	}

}
