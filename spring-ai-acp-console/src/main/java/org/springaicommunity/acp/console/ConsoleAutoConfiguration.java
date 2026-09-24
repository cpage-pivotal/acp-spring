package org.springaicommunity.acp.console;

import java.io.Console;
import java.io.IOException;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springaicommunity.acp.boot.AcpAutoConfiguration;
import org.springaicommunity.acp.client.AgentClient;
import org.springaicommunity.acp.permission.PermissionPrompt;

/**
 * A terminal chat over the application's {@link AgentClient}: add the dependency, run the
 * application, talk to the agent.
 *
 * <p>
 * For prototyping an agent — its {@code AGENTS.md}, its skills, its MCP servers — before
 * there is any other front end. Nothing here is registered unless the console will
 * actually run (see {@link ConsoleProperties#getEnabled()}), so the module is safe to
 * leave on the classpath of an application that is also tested and deployed.
 *
 * <p>
 * Also registers the {@link PermissionPrompt} that
 * {@code spring.acp.permissions.policy: ask} needs, which puts each of the agent's
 * permission requests to the person at the keyboard.
 */
@AutoConfiguration(after = AcpAutoConfiguration.class)
@ConditionalOnBean(AgentClient.class)
@Conditional(ConsoleAutoConfiguration.OnConsole.class)
@EnableConfigurationProperties(ConsoleProperties.class)
public class ConsoleAutoConfiguration {

	/**
	 * The system terminal, or a dumb one reading plain lines when the console was forced
	 * on without a terminal to run in.
	 */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	Terminal acpConsoleTerminal() throws IOException {
		return TerminalBuilder.builder().system(true).dumb(true).build();
	}

	@Bean
	@ConditionalOnMissingBean
	ConsoleRenderer acpConsoleRenderer(Terminal terminal, ConsoleProperties properties) {
		return new ConsoleRenderer(terminal, properties.isShowThoughts());
	}

	@Bean
	@ConditionalOnMissingBean(PermissionPrompt.class)
	TerminalPermissionPrompt acpConsolePermissionPrompt(Terminal terminal, ConsoleRenderer renderer) {
		return new TerminalPermissionPrompt(terminal, renderer);
	}

	@Bean
	@ConditionalOnMissingBean
	ConsoleChat acpConsoleChat(AgentClient agent, Terminal terminal, ConsoleRenderer renderer,
			ConsoleProperties properties, Environment environment, ConfigurableApplicationContext context) {
		String application = environment.getProperty("spring.application.name", "spring-ai-acp");
		String title = properties.getTitle() != null ? properties.getTitle() : application;
		return new ConsoleChat(agent, terminal, renderer, title, properties.getGreeting(), properties.getSession(),
				properties.effectiveHistoryFile(application), context::close);
	}

	/**
	 * Whether the JVM's stdin and stdout are an interactive terminal. On Java 21 a
	 * non-null {@link System#console()} already means that; from Java 22 there is always
	 * a console, and {@code isTerminal()} (reached reflectively, since this compiles for
	 * 21) is what says so.
	 */
	static boolean interactive() {
		Console console = System.console();
		if (console == null) {
			return false;
		}
		try {
			return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
		}
		catch (NoSuchMethodException ex) {
			return true;
		}
		catch (ReflectiveOperationException ex) {
			return false;
		}
	}

	/**
	 * {@code spring.acp.console.enabled}, with unset meaning "when there is a terminal to
	 * run in".
	 */
	static final class OnConsole extends SpringBootCondition {

		private static final Logger logger = LoggerFactory.getLogger(ConsoleAutoConfiguration.class);

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
			Boolean enabled = context.getEnvironment().getProperty("spring.acp.console.enabled", Boolean.class);
			if (enabled != null) {
				return enabled ? ConditionOutcome.match("spring.acp.console.enabled is true")
						: ConditionOutcome.noMatch("spring.acp.console.enabled is false");
			}
			if (interactive()) {
				return ConditionOutcome.match("attached to an interactive terminal");
			}
			logger.info("Not starting the ACP console: not attached to an interactive terminal "
					+ "(set spring.acp.console.enabled=true to start it anyway)");
			return ConditionOutcome.noMatch("not attached to an interactive terminal");
		}

	}

}
