package org.tanzu.acp.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Derives the environment variable names that carry a provider's credentials to an agent that will
 * not take them over the protocol.
 *
 * <p>This is the least portable corner of the configuration model and the one place where guessing
 * is unavoidable, so the guess is a rule rather than a table: an api type of {@code openai} becomes
 * {@code OPENAI_API_KEY} and {@code OPENAI_BASE_URL}, {@code azure_openai} becomes
 * {@code AZURE_OPENAI_API_KEY}, and so on. That rule is right for every provider the three
 * first-party runtimes support, and where it is wrong the adapter renames the variable (Goose reads
 * {@code OPENAI_HOST}, not {@code OPENAI_BASE_URL}) or the application overrides it outright through
 * the tier-3 {@code env} block, which is applied last and wins.
 */
public final class ProviderEnvironment {

	private ProviderEnvironment() {
	}

	/** {@code openai} → {@code OPENAI}. */
	public static String prefix(String apiType) {
		Validation.requireText(apiType, "provider api-type");
		return apiType.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
	}

	public static String apiKeyVariable(String apiType) {
		return prefix(apiType) + "_API_KEY";
	}

	public static String baseUrlVariable(String apiType) {
		return prefix(apiType) + "_BASE_URL";
	}

	/**
	 * The environment a provider needs, or an empty map when nothing was configured or no api type
	 * says what to call the variables.
	 *
	 * @param baseUrlVariable the name to carry the base URL under, for a runtime that does not use
	 * the derived one
	 */
	public static Map<String, String> of(ProviderSpec provider, String baseUrlVariable) {
		if (provider == null || !provider.hasCredentials() || provider.findApiType().isEmpty()) {
			return Map.of();
		}
		String apiType = provider.apiType();
		Map<String, String> env = new LinkedHashMap<>();
		provider.findApiKey().ifPresent(key -> env.put(apiKeyVariable(apiType), key));
		provider.findBaseUrl().ifPresent(url -> env
				.put(baseUrlVariable == null ? baseUrlVariable(apiType) : baseUrlVariable, url.toString()));
		return Map.copyOf(env);
	}

	public static Map<String, String> of(ProviderSpec provider) {
		return of(provider, null);
	}
}
