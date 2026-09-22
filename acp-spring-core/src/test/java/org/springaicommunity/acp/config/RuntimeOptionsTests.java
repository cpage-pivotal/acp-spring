package org.springaicommunity.acp.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 3 arrives in whatever shape the binder produced, and an adapter must not care which.
 */
class RuntimeOptionsTests {

	@Test
	void readsANestedBlock() {
		RuntimeOptions options = RuntimeOptions.of(Map.of("config-toml", Map.of("model_reasoning_effort", "high")));

		assertThat(options.section("config-toml")).containsEntry("model_reasoning_effort", "high");
	}

	@Test
	void readsTheSameBlockWrittenAsFlatDottedKeys() {
		// What an environment variable or a .properties file produces.
		RuntimeOptions options = RuntimeOptions.of(Map.of("config-toml.model_reasoning_effort", "high"));

		assertThat(options.section("config-toml")).containsEntry("model_reasoning_effort", "high");
	}

	@Test
	void mergesFlatKeysThatShareAPrefix() {
		RuntimeOptions options = RuntimeOptions
				.of(new java.util.LinkedHashMap<>(Map.of("env.A", "1", "env.B", "2")));

		assertThat(options.textSection("env")).containsEntry("A", "1").containsEntry("B", "2");
	}

	@Test
	void lookupIgnoresCaseAndSeparatorsButOutputKeepsThem() {
		RuntimeOptions options = RuntimeOptions.of(Map.of("configToml", Map.of("model_reasoning_effort", "high")));

		assertThat(options.section("config-toml")).containsKey("model_reasoning_effort");
		assertThat(options.section("config_toml")).containsKey("model_reasoning_effort");
	}

	@Test
	void aListIsAListWhetherItWasWrittenAsOneOrCommaSeparated() {
		assertThat(RuntimeOptions.of(Map.of("builtins", List.of("developer", "todo"))).textList("builtins"))
				.containsExactly("developer", "todo");
		assertThat(RuntimeOptions.of(Map.of("builtins", "developer, todo")).textList("builtins"))
				.containsExactly("developer", "todo");
	}

	@Test
	void anAbsentPathIsEmptyRatherThanNull() {
		RuntimeOptions options = RuntimeOptions.of(Map.of("a", "1"));

		assertThat(options.text("nope")).isEmpty();
		assertThat(options.textList("nope")).isEmpty();
		assertThat(options.section("nope")).isEmpty();
	}

	@Test
	void nestedTablesSurviveTheRoundTrip() {
		RuntimeOptions options = RuntimeOptions.of(Map.of("config.tools.web_search", "true"));

		assertThat(options.section("config")).containsKey("tools");
		assertThat(options.text("config.tools.web_search")).contains("true");
	}
}
