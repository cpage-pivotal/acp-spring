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
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentClientFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.goose.GooseRuntime;
import org.tanzu.acp.runtime.AgentRuntime;

/**
 * Wires an {@link AgentClient} from {@code spring.acp.*}.
 *
 * <p>Registering a runtime means contributing an {@link AgentRuntime} bean; the one whose
 * {@code id()} matches {@code spring.acp.runtime} is selected. Goose is registered automatically
 * when its adapter is on the classpath, which the starter arranges.
 */
@AutoConfiguration
@ConditionalOnClass(AgentClient.class)
@ConditionalOnProperty(prefix = "spring.acp", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(AcpProperties.class)
public class AcpAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AcpAutoConfiguration.class);

	@Bean
	@ConditionalOnClass(GooseRuntime.class)
	@ConditionalOnMissingBean(name = "gooseAgentRuntime")
	AgentRuntime gooseAgentRuntime() {
		return new GooseRuntime();
	}

	@Bean
	@ConditionalOnMissingBean
	SelectedRuntime acpSelectedRuntime(List<AgentRuntime> runtimes, AcpProperties properties) {
		return SelectedRuntime.from(runtimes, properties);
	}

	@Bean
	@ConditionalOnMissingBean
	AgentSettings acpAgentSettings(AcpProperties properties) {
		Path workspace = properties.getWorkspace() == null ? Paths.get("").toAbsolutePath()
				: properties.getWorkspace().toAbsolutePath();

		return AgentSettings.builder(properties.getRuntime(), workspace).timeout(properties.getTimeout())
				.model(properties.getModel()).provider(properties.getProvider()).mode(properties.getMode())
				.mcpServers(properties.toMcpServerSpecs()).permissions(properties.toPermissionPolicy())
				.onUnsupported(properties.getOnUnsupported()).sessionTtl(properties.getSessionTtl())
				.runtimeOptions(properties.optionsFor(properties.getRuntime())).build();
	}

	/** Starts the agent when the context refreshes and stops it when the context closes. */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	AgentClient acpAgentClient(SelectedRuntime selected, AgentSettings settings) {
		logger.info("Starting ACP runtime '{}' in workspace {}", selected.runtime().id(), settings.workspace());
		return AgentClientFactory.create(selected.runtime(), settings);
	}
}
