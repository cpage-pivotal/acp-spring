package org.springaicommunity.acp.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shapes credentials actually appear in on an agent's output, taken from what the
 * three runtimes print when their log level is raised or their configuration is wrong.
 */
class SecretRedactorTests {

	@Test
	@DisplayName("a key=value credential is blanked, and the key is kept so the line still reads")
	void redactsAssignments() {
		assertThat(SecretRedactor.redact("OPENAI_API_KEY=sk-live-abcdef1234")).isEqualTo("OPENAI_API_KEY=[REDACTED]");
	}

	@Test
	@DisplayName("a JSON-shaped credential is blanked")
	void redactsJson() {
		assertThat(SecretRedactor.redact("{\"api_key\": \"sk-live-abcdef\", \"model\": \"gpt-5.4-mini\"}"))
			.contains("[REDACTED]")
			.doesNotContain("sk-live-abcdef")
			.contains("gpt-5.4-mini");
	}

	@Test
	@DisplayName("an Authorization header is blanked")
	void redactsHeaders() {
		assertThat(SecretRedactor.redact("Authorization: Bearer eyJhbGciOi")).doesNotContain("eyJhbGciOi");
	}

	/** The one this library generates itself, for {@code goose serve}. */
	@Test
	@DisplayName("the server secret is blanked in either spelling")
	void redactsTheServerSecret() {
		assertThat(SecretRedactor.redact("GOOSE_SERVER__SECRET_KEY=deadbeefcafe")).doesNotContain("deadbeefcafe");
		assertThat(SecretRedactor.redact("x-secret-key: deadbeefcafe")).doesNotContain("deadbeefcafe");
	}

	@Test
	@DisplayName("an ordinary line is left exactly as it was")
	void leavesOrdinaryLinesAlone() {
		String line = "starting server on 127.0.0.1:52341";

		assertThat(SecretRedactor.redact(line)).isEqualTo(line);
	}

	@Test
	@DisplayName("null survives, because a log line can be one")
	void toleratesNull() {
		assertThat(SecretRedactor.redact(null)).isNull();
	}

}
