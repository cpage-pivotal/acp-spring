package org.tanzu.acp.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which model provider to talk to, and the credentials for it.
 *
 * <p>Split across all three configuration tiers, which is why it is one record rather than four
 * properties. The {@code id} is a <em>negotiated</em> request — the agent decides whether it has a
 * provider by that name. The {@code apiType}, {@code baseUrl} and {@code apiKey} are portable in
 * intent but not in mechanism: ACP's {@code providers/set} carries them when an agent advertises
 * that capability, and otherwise they reach the agent as environment variables the adapter names.
 *
 * <p>Fixed when the agent process starts, never per request. An HTTP endpoint and a credential that
 * could change per call would mean a pool of processes with different credentials and no way to tell
 * them apart; see {@link AgentOptions} for what a caller genuinely may vary.
 */
public record ProviderSpec(String id, String apiType, URI baseUrl, String apiKey, Map<String, String> headers) {

	private static final ProviderSpec NONE = new ProviderSpec(null, null, null, null, Map.of());

	public ProviderSpec {
		if (id != null && !id.isBlank()) {
			Validation.requireName(id, "provider id");
		}
		if (apiType != null && !apiType.isBlank()) {
			Validation.requireName(apiType, "provider api-type");
		}
		if (baseUrl != null) {
			Validation.requireSecureUrl(baseUrl, "provider base-url");
		}
		if (apiKey != null && !apiKey.isEmpty()) {
			Validation.requireSecret(apiKey, "provider api-key");
		}
		headers = headers == null ? Map.of() : sanitized(headers);
	}

	private static Map<String, String> sanitized(Map<String, String> headers) {
		Map<String, String> copy = new LinkedHashMap<>();
		headers.forEach((name, value) -> {
			Validation.requireHeaderName(name);
			Validation.requireHeaderValue(name, value);
			copy.put(name, value);
		});
		return Map.copyOf(copy);
	}

	/** No provider configured: the agent keeps whatever it was configured with itself. */
	public static ProviderSpec none() {
		return NONE;
	}

	public static ProviderSpec of(String id) {
		return new ProviderSpec(id, null, null, null, Map.of());
	}

	public Optional<String> findId() {
		return Optional.ofNullable(id).filter(s -> !s.isBlank());
	}

	public Optional<String> findApiType() {
		return Optional.ofNullable(apiType).filter(s -> !s.isBlank());
	}

	public Optional<URI> findBaseUrl() {
		return Optional.ofNullable(baseUrl);
	}

	public Optional<String> findApiKey() {
		return Optional.ofNullable(apiKey).filter(s -> !s.isEmpty());
	}

	/** Whether anything beyond the name was configured — the part that has to reach the process. */
	public boolean hasCredentials() {
		return findApiKey().isPresent() || findBaseUrl().isPresent() || !headers.isEmpty();
	}

	public ProviderSpec withId(String newId) {
		return newId == null || newId.equals(id) ? this : new ProviderSpec(newId, apiType, baseUrl, apiKey, headers);
	}

	/** Never renders the key or the headers: this record ends up in log lines and exception messages. */
	@Override
	public String toString() {
		return "ProviderSpec[id=" + id + ", apiType=" + apiType + ", baseUrl=" + baseUrl + ", apiKey="
				+ (findApiKey().isPresent() ? "***" : "none") + ", headers=" + headers.keySet() + "]";
	}
}
