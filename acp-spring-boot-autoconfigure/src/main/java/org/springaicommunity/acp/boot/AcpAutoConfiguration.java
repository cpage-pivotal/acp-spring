package org.springaicommunity.acp.boot;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springaicommunity.acp.client.AgentClient;
import org.springaicommunity.acp.client.AgentClientPool;
import org.springaicommunity.acp.codex.CodexRuntime;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.executor.AgentExecutor;
import org.springaicommunity.acp.executor.DefaultAgentExecutor;
import org.springaicommunity.acp.goose.GooseRuntime;
import org.springaicommunity.acp.opencode.OpenCodeRuntime;
import org.springaicommunity.acp.observation.AgentObservations;
import org.springaicommunity.acp.runtime.AgentRuntime;
import org.springaicommunity.acp.runtime.AgentRuntimeProvider;

/**
 * Wires an {@link AgentClient} from {@code spring.acp.*}.
 *
 * <p>Registering a runtime means contributing an {@link AgentRuntime} bean; the one whose
 * {@code id()} matches {@code spring.acp.runtime} is selected. Each first-party adapter registers
 * itself when it is on the classpath, so an application chooses its agents by dependency and then
 * picks one of them by property — and an application that wants its own adapter for an agent this
 * library ships just declares the bean, because every registration backs off by name.
 */
@AutoConfiguration
@ConditionalOnClass(AgentClient.class)
@ConditionalOnProperty(prefix = "spring.acp", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(AcpProperties.class)
@org.springframework.context.annotation.Import({ AcpAutoConfiguration.GooseRuntimeConfiguration.class,
		AcpAutoConfiguration.CodexRuntimeConfiguration.class,
		AcpAutoConfiguration.OpenCodeRuntimeConfiguration.class, AcpRegistryConfiguration.class,
		AcpObservationConfiguration.class })
public class AcpAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AcpAutoConfiguration.class);

	/**
	 * Each adapter registers from its own nested configuration rather than from a
	 * {@code @ConditionalOnClass} bean method.
	 *
	 * <p>Not a style choice. Boot reads the condition on a configuration class from the bytecode,
	 * without loading the class it names; on a {@code @Bean} method it has to reflect over the
	 * annotation, which throws {@code TypeNotPresentException} for an absent adapter — and then logs
	 * the failure and registers the bean anyway, so the application dies on
	 * {@code NoClassDefFoundError} at refresh. Since the whole point of these being optional
	 * dependencies is that an application ships only the agents it wants, the condition has to hold
	 * when the class is genuinely missing.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(GooseRuntime.class)
	static class GooseRuntimeConfiguration {

		@Bean
		@ConditionalOnMissingBean(name = "gooseAgentRuntime")
		AgentRuntime gooseAgentRuntime() {
			return new GooseRuntime();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(CodexRuntime.class)
	static class CodexRuntimeConfiguration {

		@Bean
		@ConditionalOnMissingBean(name = "codexAgentRuntime")
		AgentRuntime codexAgentRuntime() {
			return new CodexRuntime();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(OpenCodeRuntime.class)
	static class OpenCodeRuntimeConfiguration {

		@Bean
		@ConditionalOnMissingBean(name = "openCodeAgentRuntime")
		AgentRuntime openCodeAgentRuntime() {
			return new OpenCodeRuntime();
		}
	}

	@Bean
	@ConditionalOnMissingBean
	SelectedRuntime acpSelectedRuntime(List<AgentRuntime> runtimes, List<AgentRuntimeProvider> providers,
			AcpProperties properties) {
		return SelectedRuntime.from(runtimes, providers, properties);
	}

	/**
	 * The settings every connection runs with.
	 *
	 * <p>A {@code McpCredentialsProvider} bean puts the HTTP MCP servers it answers for behind the
	 * loopback proxy, with that session's credentials; a {@code SessionPrincipalResolver} bean says
	 * whose session it is when a prompt does not. Neither is required, and without them every server
	 * is handed to the agent as configured. A {@code PermissionPrompt} bean is who
	 * {@code permissions.policy: ask} asks, and that policy fails to start without one.
	 */
	@Bean
	@ConditionalOnMissingBean
	AgentSettings acpAgentSettings(AcpProperties properties, SelectedRuntime selected,
			org.springframework.beans.factory.ObjectProvider<org.springaicommunity.acp.mcp.McpCredentialsProvider> credentials,
			org.springframework.beans.factory.ObjectProvider<org.springaicommunity.acp.session.SessionPrincipalResolver> principals,
			org.springframework.beans.factory.ObjectProvider<org.springaicommunity.acp.permission.PermissionPrompt> prompts) {
		Path workspace = properties.getWorkspace() == null ? Paths.get("").toAbsolutePath()
				: properties.getWorkspace().toAbsolutePath();
		warnAboutUnprotectedOAuthServers(properties, credentials);
		String runtime = selected.runtime().id();

		return AgentSettings.builder(runtime, workspace).runtimeHome(properties.getRuntimeHome())
				.timeout(properties.getTimeout()).model(properties.getModel())
				.provider(properties.toProviderSpec()).mode(properties.getMode())
				.mcpServers(properties.toMcpServerSpecs()).skills(properties.toSkillSpecs())
				.mcp(properties.toMcpSettings().withCredentials(credentials.getIfAvailable())
						.withPrincipals(principals.getIfAvailable()))
				.permissions(properties.toPermissionPolicy(prompts.getIfAvailable()))
				.filesystem(properties.toFileSystemAccess()).terminal(properties.toTerminalAccess())
				.onUnsupported(properties.getOnUnsupported()).sessionTtl(properties.getPool().getSessionTtl())
				.pool(properties.toPoolSettings()).protocol(properties.toProtocolSettings())
				.runtimeOptions(properties.optionsFor(runtime)).build();
	}

	/**
	 * Says so when a server asks for OAuth and nothing will provide it. Left alone, it would be handed
	 * to the agent without a token and fail in the silent way MCP servers fail: no tools, no error.
	 */
	private static void warnAboutUnprotectedOAuthServers(AcpProperties properties,
			org.springframework.beans.factory.ObjectProvider<org.springaicommunity.acp.mcp.McpCredentialsProvider> credentials) {
		List<String> oauth = properties.getMcpServers().stream()
			.filter(server -> server.getAuth() == AcpProperties.McpAuth.OAUTH).map(AcpProperties.McpServer::getName)
			.toList();
		if (!oauth.isEmpty() && credentials.getIfAvailable() == null) {
			logger.warn("MCP server(s) {} are configured with auth: oauth, but nothing provides OAuth credentials; "
					+ "add acp-spring-mcp-oauth, or they will be called without a token", oauth);
		}
	}

	/**
	 * The application's agent, as a pool of one or more connections.
	 *
	 * <p>Always pooled, even at the default of one process, because two of the pool's three jobs
	 * apply to a single connection as much as to four: sweeping sessions nobody has touched since
	 * the TTL, and replacing a connection whose agent has gone. The third — running unrelated
	 * conversations at once — is the one that needs {@code spring.acp.pool.max-processes}.
	 *
	 * <p>Connections are opened on first use rather than at refresh, so an application whose agent
	 * is misconfigured starts and reports it on the first prompt rather than failing to start. That
	 * is the wrapper's rule carried over: an application healthy apart from its agent stays up.
	 */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	AgentClient acpAgentClient(SelectedRuntime selected, AgentSettings settings,
			org.springframework.beans.factory.ObjectProvider<AgentObservations> observations) {
		logger.info("ACP runtime '{}' selected, workspace {}, up to {} process(es)", selected.runtime().id(),
				settings.workspace(), settings.pool().maxProcesses());
		// ObjectProvider rather than an optional parameter: the observations bean only exists when
		// Micrometer does, and this bean must be constructible either way.
		return new AgentClientPool(selected.runtime(), settings,
				observations.getIfAvailable(() -> AgentObservations.NONE));
	}

	/**
	 * The {@code GooseExecutor}-shaped facade, for applications migrating off the buildpack's
	 * wrapper. Costs nothing when unused, and having it registered is what makes the migration a
	 * change of import rather than a change of wiring.
	 */
	@Bean
	@ConditionalOnMissingBean
	AgentExecutor acpAgentExecutor(AgentClient client) {
		return new DefaultAgentExecutor(client);
	}
}
