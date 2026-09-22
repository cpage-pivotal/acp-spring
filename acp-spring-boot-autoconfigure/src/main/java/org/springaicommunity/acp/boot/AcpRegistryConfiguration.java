package org.springaicommunity.acp.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springaicommunity.acp.registry.AgentInstaller;
import org.springaicommunity.acp.registry.AgentRegistry;
import org.springaicommunity.acp.registry.RegistryAgentRuntimeProvider;
import org.springaicommunity.acp.registry.RegistrySettings;
import org.springaicommunity.acp.runtime.AgentRuntimeProvider;

/**
 * Registers the registry-driven runtime, when {@code acp-spring-runtime-registry} is present.
 *
 * <p>A configuration class rather than a {@code @ConditionalOnClass} bean method, for the reason M2
 * paid for once: Boot reads a class-level condition from the bytecode without loading the type it
 * names, while on a bean method it reflects over the annotation, throws
 * {@code TypeNotPresentException} for an absent class, logs it, and registers the bean anyway. This
 * module's whole point is that its dependencies are optional, so every condition here has to hold
 * when the class is genuinely missing.
 *
 * <p>Nothing is fetched at refresh. {@link AgentRegistry} reads its snapshot the first time an id is
 * looked up, and {@link AgentInstaller} downloads the first time a runtime is launched.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(AgentRegistry.class)
@ConditionalOnProperty(prefix = "spring.acp.registry", name = "enabled", matchIfMissing = true)
class AcpRegistryConfiguration {

	@Bean
	@ConditionalOnMissingBean
	RegistrySettings acpRegistrySettings(AcpProperties properties) {
		AcpProperties.Registry registry = properties.getRegistry();
		return new RegistrySettings(registry.getUrl(), registry.getCache(), registry.getRefresh(),
				registry.isOffline(), registry.isRequireChecksum(), registry.getDownloadTimeout());
	}

	@Bean
	@ConditionalOnMissingBean
	AgentRegistry acpAgentRegistry(RegistrySettings settings) {
		return new AgentRegistry(settings);
	}

	@Bean
	@ConditionalOnMissingBean
	AgentInstaller acpAgentInstaller(RegistrySettings settings) {
		return new AgentInstaller(settings);
	}

	@Bean
	@ConditionalOnMissingBean(name = "registryAgentRuntimeProvider")
	AgentRuntimeProvider registryAgentRuntimeProvider(AgentRegistry registry, AgentInstaller installer) {
		return new RegistryAgentRuntimeProvider(registry, installer);
	}
}
