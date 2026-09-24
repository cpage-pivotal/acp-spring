package org.springaicommunity.acp.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same model, spelled the way each of the three runtimes spells it.
 */
class SelectMatcherTests {

	/**
	 * {@code select(id, currentValue, availableValues…)} — the values come after the
	 * current one.
	 */
	private static final AcpSchema.SessionConfigSelect OPENCODE_STYLE = select("model", "openai/gpt-5.4",
			"openai/gpt-5.4", "openai/gpt-5.4-mini", "anthropic/claude-opus-5");

	private static final AcpSchema.SessionConfigSelect CODEX_STYLE = select("model", "gpt-5.6-terra", "gpt-5.6-terra",
			"gpt-5.5");

	@Test
	void anExactValueMatches() {
		assertThat(SelectMatcher.match(CODEX_STYLE, "gpt-5.5", null)).contains("gpt-5.5");
	}

	@Test
	void caseDoesNotMatter() {
		assertThat(SelectMatcher.match(CODEX_STYLE, "GPT-5.5", null)).contains("gpt-5.5");
	}

	@Test
	void aProviderQualifiedValueIsFoundFromTheBareModelName() {
		assertThat(SelectMatcher.match(OPENCODE_STYLE, "gpt-5.4-mini", "openai")).contains("openai/gpt-5.4-mini");
	}

	@Test
	void aUniqueSuffixIsEnoughWithoutAProvider() {
		assertThat(SelectMatcher.match(OPENCODE_STYLE, "claude-opus-5", null)).contains("anthropic/claude-opus-5");
	}

	@Test
	void anAmbiguousSuffixIsNotGuessedAt() {
		AcpSchema.SessionConfigSelect ambiguous = select("model", "a/shared", "a/shared", "b/shared");

		assertThat(SelectMatcher.match(ambiguous, "shared", null)).isEmpty();
		assertThat(SelectMatcher.match(ambiguous, "shared", "b")).contains("b/shared");
	}

	@Test
	void aValueTheAgentDoesNotHaveDoesNotMatch() {
		assertThat(SelectMatcher.match(CODEX_STYLE, "no-such-model", null)).isEmpty();
	}

	@Test
	void anOptionWithNoEnumeratedValuesTakesWhateverItIsGiven() {
		AcpSchema.SessionConfigSelect freeform = new AcpSchema.SessionConfigSelect("select", "model", "Model", null,
				"model", "current", List.of(), null);

		assertThat(SelectMatcher.match(freeform, "anything", null)).contains("anything");
	}

	private static AcpSchema.SessionConfigSelect select(String id, String current, String... values) {
		return new AcpSchema.SessionConfigSelect("select", id, id, null, id, current,
				java.util.Arrays.stream(values).map(v -> new AcpSchema.SessionConfigSelectOption(v, v)).toList(), null);
	}

}
