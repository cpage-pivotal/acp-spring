package org.springaicommunity.acp.mcp.oauth;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.InMemoryMcpClientRegistrationRepository;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springaicommunity.acp.boot.AcpAutoConfiguration;
import org.springaicommunity.acp.client.AgentClient;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.mcp.McpCredentialsProvider;
import org.springaicommunity.acp.runtime.AgentLaunchSpec;
import org.springaicommunity.acp.runtime.AgentRuntime;
import org.springaicommunity.acp.session.SessionPrincipalResolver;

import static org.assertj.core.api.Assertions.assertThat;

class McpOAuthAutoConfigurationTests {

	@TempDir
	Path workspace;

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AcpAutoConfiguration.class, McpOAuthAutoConfiguration.class))
			.withUserConfiguration(NoAgent.class)
			.withPropertyValues("spring.acp.runtime=test", "spring.acp.workspace=" + workspace);
	}

	private static final String[] OAUTH_SERVER = { "spring.acp.mcp-servers[0].name=finops-mcp",
			"spring.acp.mcp-servers[0].url=https://gateway.example.com/finops-mcp/mcp",
			"spring.acp.mcp-servers[0].auth=oauth" };

	@Test
	void staysOutOfTheWayWhenNoServerAsksForOAuth() {
		runner()
			.withPropertyValues("spring.acp.mcp-servers[0].name=open",
					"spring.acp.mcp-servers[0].url=https://open.example.com/mcp")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(McpCredentialsProvider.class);
				assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
				assertThat(context.getBean(AgentSettings.class).mcp().credentials())
					.isSameAs(McpCredentialsProvider.none());
			});
	}

	@Test
	void wiresTheProviderResolverAndSpringSecurityBeansForAnOAuthServer() {
		runner().withPropertyValues(OAUTH_SERVER).run(context -> {
			AgentSettings settings = context.getBean(AgentSettings.class);
			assertThat(settings.mcp().credentials()).isInstanceOf(OAuth2McpCredentialsProvider.class);
			assertThat(settings.mcp().principals()).isInstanceOf(SecurityContextPrincipalResolver.class);
			assertThat(context.getBean(OAuth2McpCredentialsProvider.class).serverNames()).containsExactly("finops-mcp");
			// The one repository Spring Security's redirect filter and the provider must
			// share.
			assertThat(context.getBean(ClientRegistrationRepository.class))
				.isSameAs(context.getBean(McpClientRegistrationRepository.class))
				.isInstanceOf(InMemoryMcpClientRegistrationRepository.class);
			assertThat(context.getBean(OAuth2AuthorizedClientService.class))
				.isInstanceOf(InMemoryOAuth2AuthorizedClientService.class);
			assertThat(context.getBean(RefreshLock.class)).isNotInstanceOf(JdbcRefreshLock.class);
		});
	}

	@Test
	void keepsStateInTheDatabaseWhenAskedTo() {
		runner().withPropertyValues(OAUTH_SERVER)
			.withPropertyValues("spring.acp.mcp.oauth.store=jdbc")
			.withBean(JdbcOperations.class, () -> new JdbcTemplate(
					new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build()))
			.run(context -> {
				assertThat(context.getBean(McpClientRegistrationRepository.class))
					.isInstanceOf(JdbcMcpClientRegistrationRepository.class);
				assertThat(context.getBean(OAuth2AuthorizedClientService.class))
					.isInstanceOf(JdbcOAuth2AuthorizedClientService.class);
				// Instances sharing the database take the database's lock before
				// refreshing.
				assertThat(context.getBean(RefreshLock.class)).isInstanceOf(JdbcRefreshLock.class);
			});
	}

	@Test
	void aDatabaseStoreWithoutADatabaseSaysWhatIsMissing() {
		runner().withPropertyValues(OAUTH_SERVER)
			.withPropertyValues("spring.acp.mcp.oauth.store=jdbc")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("JdbcOperations"));
	}

	@Test
	void oauthOnAStdioServerIsAConfigurationError() {
		runner()
			.withPropertyValues("spring.acp.mcp-servers[0].name=local", "spring.acp.mcp-servers[0].command=helper",
					"spring.acp.mcp-servers[0].auth=oauth")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("only to a server reached by url"));
	}

	@Test
	void anApplicationsOwnResolverWins() {
		SessionPrincipalResolver own = SessionPrincipalResolver.none();
		runner().withPropertyValues(OAUTH_SERVER)
			.withBean(SessionPrincipalResolver.class, () -> own)
			.run(context -> assertThat(context.getBean(AgentSettings.class).mcp().principals()).isSameAs(own));
	}

	@Test
	void aTerminalApplicationSignsInThroughTheBrowserAndKeepsItInAFile() {
		Path file = workspace.resolve("state").resolve("mcp-oauth.json");
		runner().withPropertyValues(OAUTH_SERVER)
			.withPropertyValues("spring.acp.mcp.oauth.mode=local", "spring.acp.mcp.oauth.file=" + file)
			.run(context -> {
				assertThat(context.getBean(FileMcpOAuthStore.class).location()).isEqualTo(file);
				assertThat(context.getBean(McpSignIn.class)).isInstanceOf(LoopbackSignIn.class);
				assertThat(context.getBean(McpClientRegistrationRepository.class))
					.isSameAs(context.getBean(FileMcpOAuthStore.class).registrations());
				assertThat(context.getBean(AgentSettings.class).mcp().principals())
					.isInstanceOf(LocalPrincipalResolver.class);
			});
	}

	@Test
	void aTerminalApplicationWithAStoreThatCannotHoldItsSignInsSaysSo() {
		runner().withPropertyValues(OAUTH_SERVER)
			.withPropertyValues("spring.acp.mcp.oauth.mode=local", "spring.acp.mcp.oauth.store=memory")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("store unset or set it to file"));
	}

	@Configuration(proxyBeanMethods = false)
	static class NoAgent {

		@Bean
		AgentRuntime testRuntime() {
			return new AgentRuntime() {
				@Override
				public String id() {
					return "test";
				}

				@Override
				public AgentLaunchSpec launch(AgentSettings settings) {
					throw new UnsupportedOperationException("no agent in this test");
				}
			};
		}

		@Bean
		AgentClient acpAgentClient() {
			return org.mockito.Mockito.mock(AgentClient.class);
		}

	}

}
