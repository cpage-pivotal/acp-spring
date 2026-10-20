package org.springaicommunity.acp.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standalone configuration file, driven through a real Boot startup.
 *
 * <p>
 * An {@code ApplicationContextRunner} would not do: what is under test is whether Boot's
 * config-data machinery routes {@code agents.yaml} to this library's resolver rather than
 * to its own, and that decision is made in the environment-preparation phase a runner
 * skips.
 */
class AgentsConfigDataLoaderTests {

	@TempDir
	Path directory;

	@Test
	@DisplayName("a bare agents.yaml is read as if every key were under spring.acp")
	void prefixesTheDocument() throws IOException {
		Path file = write("agents.yaml", """
				runtime: goose
				timeout: 90s
				permissions:
				  policy: allowlist
				  allowed-tools: [ developer__text_editor ]
				runtimes:
				  goose:
				    builtins: developer
				""");

		try (ConfigurableApplicationContext context = start("optional:" + file)) {
			Environment environment = context.getEnvironment();
			assertThat(environment.getProperty("spring.acp.runtime")).isEqualTo("goose");
			assertThat(environment.getProperty("spring.acp.timeout")).isEqualTo("90s");
			assertThat(environment.getProperty("spring.acp.permissions.policy")).isEqualTo("allowlist");
			assertThat(environment.getProperty("spring.acp.runtimes.goose.builtins")).isEqualTo("developer");
		}
	}

	/**
	 * So one file can carry the agent configuration it exists for and the odd unrelated
	 * property.
	 */
	@Test
	@DisplayName("a key that is already a spring property is left where it was")
	void leavesSpringKeysAlone() throws IOException {
		Path file = write("agents.yaml", """
				runtime: goose
				spring:
				  application:
				    name: from-agents-yaml
				""");

		try (ConfigurableApplicationContext context = start("optional:" + file)) {
			assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("from-agents-yaml");
			assertThat(context.getEnvironment().getProperty("spring.acp.runtime")).isEqualTo("goose");
		}
	}

	@Test
	@DisplayName("the explicit acp: form works for a file called something else")
	void explicitPrefix() throws IOException {
		Path file = write("my-agent.yaml", "runtime: opencode\n");

		try (ConfigurableApplicationContext context = start("optional:acp:" + file)) {
			assertThat(context.getEnvironment().getProperty("spring.acp.runtime")).isEqualTo("opencode");
		}
	}

	@Test
	@DisplayName("an optional file that is not there is not a failure")
	void optionalAndAbsent() {
		try (ConfigurableApplicationContext context = start("optional:" + directory.resolve("agents.yaml"))) {
			assertThat(context.getEnvironment().getProperty("spring.acp.runtime")).isNull();
		}
	}

	@Test
	@DisplayName("a required file that is not there fails the application, saying which file")
	void requiredAndAbsent() {
		String missing = directory.resolve("agents.yaml").toString();

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> start(missing).close())
			.hasMessageContaining("agents.yaml");
	}

	/**
	 * {@code ${}} resolution and profiles come free, which is the point of reusing Boot's
	 * loader.
	 */
	@Test
	@DisplayName("placeholders in the file resolve against the rest of the environment")
	void resolvesPlaceholders() throws IOException {
		Path file = write("agents.yaml", "runtime: ${chosen-runtime}\n");

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class)
			.web(WebApplicationType.NONE)
			.properties("spring.config.import=optional:" + file, "chosen-runtime=codex")
			.run()) {
			assertThat(context.getEnvironment().getProperty("spring.acp.runtime")).isEqualTo("codex");
		}
	}

	private Path write(String name, String content) throws IOException {
		return Files.writeString(directory.resolve(name), content);
	}

	private static ConfigurableApplicationContext start(String location) {
		return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
			.properties("spring.config.import=" + location, "spring.acp.enabled=false")
			.run();
	}

	@Configuration(proxyBeanMethods = false)
	static class Empty {

	}

}
