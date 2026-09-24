package org.springaicommunity.acp.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springaicommunity.acp.ai.AcpChatModel;
import org.springaicommunity.acp.ai.AcpChatOptions;
import org.springaicommunity.acp.client.AgentClient;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Publishes the agent as a Spring AI {@link ChatModel}, when {@code spring-ai-acp-ai} and
 * Spring AI are both present.
 *
 * <p>
 * Registered without a property to turn it on, because the dependency <em>is</em> the
 * opt-in: nothing pulls {@code spring-ai-acp-ai} in by accident, and an application that
 * added it did so to get this bean. {@code spring.acp.chat-model.enabled=false} exists
 * for the one case that argument does not cover — an application that wants the adapter's
 * types to construct a model of its own, with its own default session.
 *
 * <p>
 * The default options carry no session, so every {@code ChatClient} call is a throwaway
 * conversation unless the caller names one. That is the choice that matches Spring AI's
 * own semantics; naming a session in the defaults would make one {@code ChatModel} bean a
 * single shared conversation for the whole application, which is almost never what is
 * wanted and would be invisible until two users interfered with each other.
 */
@AutoConfiguration(after = AcpAutoConfiguration.class)
@ConditionalOnClass({ ChatModel.class, AcpChatModel.class })
@ConditionalOnProperty(prefix = "spring.acp.chat-model", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(AcpProperties.class)
public class AcpChatModelAutoConfiguration {

	@Bean
	@ConditionalOnBean(AgentClient.class)
	@ConditionalOnMissingBean
	AcpChatModel acpChatModel(AgentClient client, AcpProperties properties) {
		return new AcpChatModel(client,
				AcpChatOptions.builder()
					.model(properties.getModel())
					.mode(properties.getMode())
					.timeout(properties.getTimeout())
					.build());
	}

}
