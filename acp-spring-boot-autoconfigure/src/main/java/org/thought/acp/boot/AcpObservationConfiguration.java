package org.thought.acp.boot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thought.acp.observation.AgentObservations;
import org.thought.acp.observation.AgentTurnObservationContext;
import org.thought.acp.observation.MicrometerAgentObservations;
import org.thought.acp.observation.ToolCallObservationContext;

import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;

/**
 * Reports turns and tool calls to Micrometer, when Micrometer is present.
 *
 * <p>Same class-level condition, same reason as {@link AcpRegistryConfiguration}. Defaults on,
 * unlike the HTTP endpoint: an observation publishes nothing and reaches nothing, and an
 * application that has an {@code ObservationRegistry} has already asked to be measured.
 *
 * <p>An application replaces the tags rather than the mechanism by contributing an
 * {@link ObservationConvention} for either context type — which is how the session name moves from
 * a span attribute to a meter dimension in a deployment where the cardinality is known to be
 * small.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "spring.acp.observations", name = "enabled", matchIfMissing = true)
class AcpObservationConfiguration {

	@Bean
	@ConditionalOnMissingBean
	AgentObservations acpAgentObservations(ObjectProvider<ObservationRegistry> registry,
			ObjectProvider<ObservationConvention<AgentTurnObservationContext>> turnConvention,
			ObjectProvider<ObservationConvention<ToolCallObservationContext>> toolCallConvention) {
		// NOOP rather than absent: a context with Micrometer on the classpath and no registry bean
		// is a legitimate arrangement, and it should cost nothing rather than fail to start.
		return new MicrometerAgentObservations(registry.getIfAvailable(() -> ObservationRegistry.NOOP),
				turnConvention.getIfAvailable(), toolCallConvention.getIfAvailable());
	}
}
