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
 * that puts those servers behind the loopback proxy, the {@link McpSignIn} for a user without a
 * token, and the resolver that says whose session it is — the security context's user on the web,
 * the operating-system user with {@code mode: local}, where the sign-in is a browser opened on the
 * spot and there is no filter chain at all. The application still owns its {@code SecurityFilterChain}:
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
	McpClientRegistrationRepository acpMcpClientRegistrationRepository(McpOAuthProperties properties,
			org.springframework.beans.factory.ObjectProvider<FileMcpOAuthStore> file) {
		return switch (properties.effectiveStore()) {
			case FILE -> file.getObject().registrations();
			case JDBC -> throw new IllegalStateException("spring.acp.mcp.oauth.store=jdbc needs spring-jdbc on the classpath");
			case MEMORY -> new InMemoryMcpClientRegistrationRepository();
		};
	}

	/** Registrations and tokens in one private file: the default for a terminal application. */
	@Bean
	@ConditionalOnMissingBean
	@Conditional(OnFileStore.class)
	FileMcpOAuthStore acpMcpOAuthFileStore(McpOAuthProperties properties, Environment environment) {
		FileMcpOAuthStore store = new FileMcpOAuthStore(properties.getFile() != null ? properties.getFile()
				: FileMcpOAuthStore.defaultLocation(environment.getProperty("spring.application.name", "acp-spring")));
		org.slf4j.LoggerFactory.getLogger(McpOAuthAutoConfiguration.class)
			.debug("MCP sign-in state is kept at {}", store.location());
		return store;
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
	OAuth2AuthorizedClientService acpMcpAuthorizedClientService(McpClientRegistrationRepository registrations,
			org.springframework.beans.factory.ObjectProvider<FileMcpOAuthStore> file) {
		FileMcpOAuthStore store = file.getIfAvailable();
		return store != null ? store.tokens() : new InMemoryOAuth2AuthorizedClientService(registrations);
	}

	/** One refresh at a time per (server, user) in this JVM, unless {@link Jdbc} made it the database's. */
	@Bean
	@ConditionalOnMissingBean
	RefreshLock acpMcpRefreshLock() {
		return RefreshLock.inProcess();
	}

	/**
	 * What happens for a user without a token: Spring Security's redirect on the web, a browser opened
	 * on the spot for a terminal application.
	 */
	@Bean
	@ConditionalOnMissingBean
	McpSignIn acpMcpSignIn(McpOAuthProperties properties, OAuth2AuthorizedClientService authorizedClients,
			org.springframework.beans.factory.ObjectProvider<FileMcpOAuthStore> file,
			org.springframework.beans.factory.ObjectProvider<AuthorizationPrompt> prompt) {
		if (properties.getMode() == McpOAuthProperties.Mode.WEB) {
			return McpSignIn.redirect(properties.getRedirectUri(), new CurrentRequestBaseUrl(properties.getBaseUrl()));
		}
		FileMcpOAuthStore store = file.getIfAvailable();
		if (store == null) {
			throw new IllegalStateException("spring.acp.mcp.oauth.mode=local keeps its sign-ins in a file; "
					+ "leave spring.acp.mcp.oauth.store unset or set it to file");
		}
		return new LoopbackSignIn(store.registrations(), authorizedClients,
				prompt.getIfAvailable(() -> AuthorizationPrompt.browser(System.out)), properties.getSignInTimeout());
	}

	@Bean
	@ConditionalOnMissingBean
	OAuth2McpCredentialsProvider acpMcpOAuth2CredentialsProvider(AcpProperties acp, McpOAuthProperties properties,
			McpClientRegistrationRepository registrations, McpOAuth2DcrClientManager clientManager,
			OAuth2AuthorizedClientService authorizedClients, McpSignIn signIn, RefreshLock refreshLock,
			Environment environment) {
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
				McpAuthorizedClientManagers.create(registrations, authorizedClients), clientName, signIn, refreshLock);
	}

	/** The signed-in user of the request on the web; the operating-system user in a terminal. */
	@Bean
	@ConditionalOnMissingBean
	SessionPrincipalResolver acpMcpOAuthPrincipalResolver(McpOAuthProperties properties) {
		return properties.getMode() == McpOAuthProperties.Mode.LOCAL ? new LocalPrincipalResolver()
				: new SecurityContextPrincipalResolver();
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

		/**
		 * Every instance sharing the database takes the same row lock before refreshing, so a refresh
		 * token the authorization server honours once is spent once. Uses the application's transaction
		 * manager when there is one, else one over the {@code JdbcTemplate}'s own data source.
		 */
		@Bean
		@ConditionalOnMissingBean
		RefreshLock acpMcpRefreshLock(
				org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcOperations> jdbc,
				org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactions) {
			org.springframework.jdbc.core.JdbcOperations operations = requireJdbc(jdbc);
			org.springframework.transaction.PlatformTransactionManager manager = transactions.getIfAvailable(() -> {
				if (operations instanceof org.springframework.jdbc.core.JdbcTemplate template
						&& template.getDataSource() != null) {
					return new org.springframework.jdbc.datasource.DataSourceTransactionManager(template.getDataSource());
				}
				throw new IllegalStateException("spring.acp.mcp.oauth.store=jdbc needs a PlatformTransactionManager "
						+ "to lock refreshes across instances");
			});
			return new JdbcRefreshLock(new org.springframework.transaction.support.TransactionTemplate(manager), operations);
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

	/** {@code store: file}, or {@code mode: local} with no store named. */
	static final class OnFileStore extends SpringBootCondition {

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
			McpOAuthProperties properties = Binder.get(context.getEnvironment())
				.bind("spring.acp.mcp.oauth", McpOAuthProperties.class)
				.orElseGet(McpOAuthProperties::new);
			return properties.effectiveStore() == McpOAuthProperties.Store.FILE
					? ConditionOutcome.match("MCP sign-ins are kept in a file")
					: ConditionOutcome.noMatch("MCP sign-ins are not kept in a file");
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
