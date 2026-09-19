package org.tanzu.acp.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.event.AgentEvent;

/**
 * Everything an application needs to talk to an ACP agent: a dependency, a property, and an
 * injected {@link AgentClient}. Swapping the agent is a change to {@code spring.acp.runtime}
 * alone — no code here mentions Goose.
 */
@SpringBootApplication
public class SmokeApplication {

	private static final Logger logger = LoggerFactory.getLogger(SmokeApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(SmokeApplication.class, args);
	}

	@Bean
	ApplicationRunner demo(AgentClient agent) {
		return args -> {
			logger.info("Runtime: {}", agent.runtimeId());

			String answer = agent.prompt("Reply with exactly the word READY. Do not use tools.").call().content();
			logger.info("Blocking call: {}", answer.strip());

			agent.prompt().session("demo").user("Name one benefit of a standard agent protocol, in one sentence.")
					.stream().events().doOnNext(event -> {
						if (event instanceof AgentEvent.Text text) {
							logger.info("Streamed: {}", text.text().strip());
						}
						else if (event.terminal()) {
							logger.info("Turn ended: {}", event);
						}
					}).blockLast();
		};
	}
}
