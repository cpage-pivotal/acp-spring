package org.springaicommunity.acp.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springaicommunity.acp.client.AgentClient;

/**
 * Registers {@link AcpController} when an application has asked for it.
 *
 * <p>
 * Three conditions, and each one is doing different work. {@code @ConditionalOnClass} on
 * the configuration class — not on the bean method, for the reason
 * {@code AcpAutoConfiguration} documents at length — keeps this quiet when WebFlux is
 * absent. {@code @ConditionalOnProperty} with no {@code matchIfMissing} keeps it quiet by
 * default, so adding the WebFlux starter for some other reason does not publish an agent
 * endpoint as a side effect. {@code @ConditionalOnWebApplication(REACTIVE)} keeps it
 * quiet in an application that has WebFlux on the classpath but is not serving with it.
 */
@AutoConfiguration(after = AcpAutoConfiguration.class)
@ConditionalOnClass(WebFluxConfigurer.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "spring.acp.controller", name = "enabled", havingValue = "true")
public class AcpWebFluxAutoConfiguration {

	@Bean
	@ConditionalOnBean(AgentClient.class)
	@ConditionalOnMissingBean
	AcpController acpController(AgentClient agent, AcpProperties properties) {
		return new AcpController(agent, properties);
	}

}
