package org.tanzu.acp.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.session.AgentSessions;

/**
 * Everything an application needs to talk to an ACP agent: a dependency, a property, and an
 * injected {@link AgentClient}.
 *
 * <p>Nothing in this file names an agent. Goose, Codex and OpenCode are all on the classpath, and
 * {@code spring.acp.runtime} in {@code application.yaml} is the only thing that decides which one
 * runs — which is the claim the second milestone exists to make good on. Run it three times:
 *
 * <pre>{@code
 * mvn -pl samples/smoke-app spring-boot:run
 * mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=codex
 * mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=opencode
 * }</pre>
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

			// What the negotiated tier actually achieved, before spending a turn to find out. With
			// on-unsupported=warn this is the difference between the model that was asked for and the
			// model this agent is really running.
			agent.openSession("demo").configuration().resolutions()
					.forEach((option, resolution) -> logger.info("Config {}: {} -> {} ({})", option,
							resolution.requested(), resolution.applied(), resolution.mechanism()));

			// Which optional session methods this particular agent has. All of them are optional in
			// ACP and the three runtimes implement different subsets, so an application that cares
			// asks rather than finding out from an error.
			agent.agentInfo().ifPresent(info -> logger.info("Agent: {}", info));
			for (AgentSessions.Operation operation : AgentSessions.Operation.values()) {
				logger.info("Supports {}: {}", operation.method(), agent.sessions().supports(operation));
			}

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
