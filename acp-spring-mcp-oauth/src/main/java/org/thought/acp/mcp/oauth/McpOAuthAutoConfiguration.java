package org.thought.acp.mcp.oauth;

import java.util.List;

import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DefaultMcpOAuth2DcrClientManager;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DynamicClientRegistrationService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.InMemoryMcpClientRegistrationRepository;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpOAuth2DcrClientManager;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.springaicommunity.mcp.security.common.url.UrlValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.thought.acp.boot.AcpProperties;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.session.SessionPrincipalResolver;

/**
 * Per-user OAuth for the MCP servers configured with {@code auth: oauth}.
 *
 * <p>Registers the beans Spring Security's {@code oauth2Client()} flow and mcp-security's
 * {@code McpClientOAuth2Configurer} look for — the registration repository, the dynamic
 * registration manager, the authorized-client service — plus the {@link OAuth2McpCredentialsProvider}
 * that puts those servers behind the loopback proxy and the resolver that takes a session's
 * principal from the security context. The application still owns its {@code SecurityFilterChain}:
 * it signs users in however it likes and adds
 * {@code .with(McpClientOAuth2Configurer.mcpClientOAuth2(), mcp -> mcp.cimd(false))} so that
 * signing in to an MCP server works.
 *
 * <p>Ordered before Boot's OAuth2 client auto-configuration, so the repository here is the one
 * Spring Security uses: dynamically registered clients have to be visible to the redirect filter
 * that sends users to sign in with them.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration")
@Conditional(McpOAuthAutoConfiguration.OnOAuthServers.class)
@EnableConfigurationProperties(McpOAuthProperties.class)
public class McpOAuthAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	UrlValidator acpMcpOAuthUrlValidator(McpOAuthProperties properties) {
		return new DefaultUrlValidator(properties.isAllowLoopback());
	}

	@Bean
	@ConditionalOnMissingBean
	McpMetadataDiscoveryService acpMcpMetadataDiscoveryService(UrlValidator urlValidator) {
		return new McpMetadataDiscoveryService(urlValidator);
	}

	@Bean
	@ConditionalOnMissingBean
	DynamicClientRegistrationService acpMcpDynamicClientRegistrationService(UrlValidator urlValidator) {
		return new DynamicClientRegistrationService(urlValidator);
	}

	/**
	 * Also the application's {@code ClientRegistrationRepository}, which is what makes sign-in work.
	 * In memory unless {@link Jdbc} registered one first.
	 */
	@Bean
	@ConditionalOnMissingBean
	McpClientRegistrationRepository acpMcpClientRegistrationRepository(McpOAuthProperties properties) {
		if (properties.getStore() == McpOAuthProperties.Store.JDBC) {
			throw new IllegalStateException("spring.acp.mcp.oauth.store=jdbc needs spring-jdbc on the classpath");
		}
		return new InMemoryMcpClientRegistrationRepository();
	}

	@Bean
	@ConditionalOnMissingBean
	McpOAuth2DcrClientManager acpMcpOAuth2DcrClientManager(McpClientRegistrationRepository registrations,
			DynamicClientRegistrationService registration, McpMetadataDiscoveryService discovery,
			UrlValidator urlValidator) {
		return new DefaultMcpOAuth2DcrClientManager(registrations, registration, discovery, urlValidator);
	}

	/**
	 * Where users' tokens live: written by Spring Security's sign-in flow, read by the proxy. In
	 * memory unless {@link Jdbc} registered one first.
	 */
	@Bean
	@ConditionalOnMissingBean
	OAuth2AuthorizedClientService acpMcpAuthorizedClientService(McpClientRegistrationRepository registrations) {
		return new InMemoryOAuth2AuthorizedClientService(registrations);
	}

	@Bean
	@ConditionalOnMissingBean
	OAuth2McpCredentialsProvider acpMcpOAuth2CredentialsProvider(AcpProperties acp, McpOAuthProperties properties,
			McpClientRegistrationRepository registrations, McpOAuth2DcrClientManager clientManager,
			OAuth2AuthorizedClientService authorizedClients, Environment environment) {
		List<McpServerSpec.Http> servers = acp.getMcpServers().stream()
			.filter(server -> server.getAuth() == AcpProperties.McpAuth.OAUTH)
			.map(AcpProperties.McpServer::spec)
			.map(spec -> {
				if (spec instanceof McpServerSpec.Http http) {
					return http;
				}
				throw new IllegalStateException("MCP server '" + spec.name()
						+ "' is configured with auth: oauth, which applies only to a server reached by url");
			})
			.toList();
		String clientName = properties.getClientName() != null ? properties.getClientName()
				: environment.getProperty("spring.application.name", "acp-spring");
		return new OAuth2McpCredentialsProvider(servers, registrations, clientManager, authorizedClients,
				McpAuthorizedClientManagers.create(registrations, authorizedClients),
				new OAuth2McpCredentialsProvider.Registration(clientName, properties.getRedirectUri(),
						new CurrentRequestBaseUrl(properties.getBaseUrl())));
	}

	@Bean
	@ConditionalOnMissingBean
	SessionPrincipalResolver acpSecurityContextPrincipalResolver() {
		return new SecurityContextPrincipalResolver();
	}

	/**
	 * The database-backed store, shared by every instance. A member class, so it is only loaded
	 * when spring-jdbc is present, and processed before the in-memory defaults above.
	 */
	@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
	@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcOperations")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.acp.mcp.oauth.store",
			havingValue = "jdbc")
	static class Jdbc {

		@Bean
		@ConditionalOnMissingBean
		McpClientRegistrationRepository acpMcpClientRegistrationRepository(
				org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcOperations> jdbc) {
			return new JdbcMcpClientRegistrationRepository(requireJdbc(jdbc));
		}

		@Bean
		@ConditionalOnMissingBean
		OAuth2AuthorizedClientService acpMcpAuthorizedClientService(McpClientRegistrationRepository registrations,
				org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcOperations> jdbc) {
			return new org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService(requireJdbc(jdbc),
					registrations);
		}

		private static org.springframework.jdbc.core.JdbcOperations requireJdbc(
				org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcOperations> jdbc) {
			org.springframework.jdbc.core.JdbcOperations operations = jdbc.getIfAvailable();
			if (operations == null) {
				throw new IllegalStateException("spring.acp.mcp.oauth.store=jdbc needs a JdbcOperations bean; "
						+ "add spring-boot-starter-jdbc and a DataSource");
			}
			return operations;
		}
	}

	/** Active only when some configured MCP server asks for OAuth. */
	static final class OnOAuthServers extends SpringBootCondition {

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
			List<AcpProperties.McpServer> servers = Binder.get(context.getEnvironment())
				.bind("spring.acp.mcp-servers", Bindable.listOf(AcpProperties.McpServer.class))
				.orElse(List.of());
			boolean any = servers.stream().anyMatch(server -> server.getAuth() == AcpProperties.McpAuth.OAUTH);
			return any ? ConditionOutcome.match("an MCP server is configured with auth: oauth")
					: ConditionOutcome.noMatch("no MCP server is configured with auth: oauth");
		}
	}
}
