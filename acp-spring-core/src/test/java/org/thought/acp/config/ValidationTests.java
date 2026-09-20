package org.thought.acp.config;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationTests {

	@ParameterizedTest
	@ValueSource(strings = { "https://tools.example.com/mcp", "http://localhost:8080/mcp", "http://127.0.0.1/mcp",
			"http://gateway.apps.internal/mcp" })
	void acceptsHttpsAndTheTwoJustifiedPlainHttpCases(String url) {
		assertThatNoException().isThrownBy(() -> Validation.requireSecureUrl(URI.create(url), "url"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://tools.example.com/mcp", "ftp://example.com", "http://evil.com.apps.internal.io" })
	void rejectsPlainHttpToTheOpenInternet(String url) {
		assertThatThrownBy(() -> Validation.requireSecureUrl(URI.create(url), "url"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsCredentialsEmbeddedInAUrlBecauseUrlsGetLogged() {
		assertThatThrownBy(() -> Validation.requireSecureUrl(URI.create("https://user:pw@example.com/mcp"), "url"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credentials");
	}

	@Test
	void rejectsHeaderInjectionWithoutEchoingTheValue() {
		assertThatThrownBy(() -> Validation.requireHeaderValue("Authorization", "Bearer x\r\nX-Evil: 1"))
				.isInstanceOf(IllegalArgumentException.class)
				.satisfies(ex -> assertThat(ex.getMessage()).contains("Authorization").doesNotContain("Bearer"));
	}

	@Test
	void rejectsEnvironmentNamesAShellWouldNotAccept() {
		assertThatNoException().isThrownBy(() -> Validation.requireEnvName("GOOSE_MODEL"));
		assertThatThrownBy(() -> Validation.requireEnvName("2BAD")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Validation.requireEnvName("BAD-NAME")).isInstanceOf(IllegalArgumentException.class);
	}
}
