package org.springaicommunity.acp.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springaicommunity.acp.registry.AcpAgentInstaller;
import org.springaicommunity.acp.registry.AcpAgentRegistry;
import org.springaicommunity.acp.registry.RegistryAcpAgentRuntimeProvider;
import org.springaicommunity.acp.registry.RegistrySettings;
import org.springaicommunity.acp.runtime.AcpAgentRuntimeProvider;

/**
 * Registers the registry-driven runtime, when
 * {@code spring-ai-acp-harness-client-runtime-registry} is present.
 *
 * <p>
 * A configuration class rather than a {@code @ConditionalOnClass} bean method, for the
 * reason M2 paid for once: Boot reads a class-level condition from the bytecode without
 * loading the type it names, while on a bean method it reflects over the annotation,
 * throws {@code TypeNotPresentException} for an absent class, logs it, and registers the
 * bean anyway. This module's whole point is that its dependencies are optional, so every
 * condition here has to hold when the class is genuinely missing.
 *
 * <p>
 * Nothing is fetched at refresh. {@link AcpAgentRegistry} reads its snapshot the first
 * time an id is looked up, and {@link AcpAgentInstaller} downloads the first time a
 * runtime is launched.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(AcpAgentRegistry.class)
@ConditionalOnProperty(prefix = "spring.acp.registry", name = "enabled", matchIfMissing = true)
class AcpRegistryConfiguration {

	@Bean
	@ConditionalOnMissingBean
	RegistrySettings acpRegistrySettings(AcpProperties properties) {
		AcpProperties.Registry registry = properties.registry();
		return new RegistrySettings(registry.url(), registry.cache(), registry.refresh(), registry.offline(),
				registry.requireChecksum(), registry.downloadTimeout());
	}

	@Bean
	@ConditionalOnMissingBean
	AcpAgentRegistry acpAgentRegistry(RegistrySettings settings) {
		return new AcpAgentRegistry(settings);
	}

	@Bean
	@ConditionalOnMissingBean
	AcpAgentInstaller acpAgentInstaller(RegistrySettings settings) {
		return new AcpAgentInstaller(settings);
	}

	@Bean
	@ConditionalOnMissingBean(name = "registryAgentRuntimeProvider")
	AcpAgentRuntimeProvider registryAgentRuntimeProvider(AcpAgentRegistry registry, AcpAgentInstaller installer) {
		return new RegistryAcpAgentRuntimeProvider(registry, installer);
	}

}
