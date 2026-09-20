package org.tanzu.acp.boot;

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
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentClientPool;
import org.tanzu.acp.codex.CodexRuntime;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.executor.AgentExecutor;
import org.tanzu.acp.executor.DefaultAgentExecutor;
import org.tanzu.acp.goose.GooseRuntime;
import org.tanzu.acp.opencode.OpenCodeRuntime;
import org.tanzu.acp.observation.AgentObservations;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.runtime.AgentRuntimeProvider;

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

	@Bean
	@ConditionalOnMissingBean
	AgentSettings acpAgentSettings(AcpProperties properties) {
		Path workspace = properties.getWorkspace() == null ? Paths.get("").toAbsolutePath()
				: properties.getWorkspace().toAbsolutePath();

		return AgentSettings.builder(properties.getRuntime(), workspace).runtimeHome(properties.getRuntimeHome())
				.timeout(properties.getTimeout()).model(properties.getModel())
				.provider(properties.toProviderSpec()).mode(properties.getMode())
				.mcpServers(properties.toMcpServerSpecs()).permissions(properties.toPermissionPolicy())
				.filesystem(properties.toFileSystemAccess()).terminal(properties.toTerminalAccess())
				.onUnsupported(properties.getOnUnsupported()).sessionTtl(properties.getPool().getSessionTtl())
				.pool(properties.toPoolSettings()).protocol(properties.toProtocolSettings())
				.runtimeOptions(properties.optionsFor(properties.getRuntime())).build();
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
