package org.springaicommunity.acp.config;

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
 * first-party runtimes support. Where an agent spells the same two things differently, its adapter
 * builds the map itself from {@link ProviderSpec#findApiBase()} rather than from a special case
 * here — vendor knowledge belongs in the adapter — and an application can override any of it through
 * the tier-3 {@code env} block, which is applied last and wins.
 *
 * <p>The base URL carried here is the canonical one, so an agent that reads {@code OPENAI_BASE_URL}
 * gets the form its SDK expects whichever way the property was written.
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
	 */
	public static Map<String, String> of(ProviderSpec provider) {
		if (provider == null || !provider.hasCredentials() || provider.findApiType().isEmpty()) {
			return Map.of();
		}
		String apiType = provider.apiType();
		Map<String, String> env = new LinkedHashMap<>();
		provider.findApiKey().ifPresent(key -> env.put(apiKeyVariable(apiType), key));
		provider.findApiBase().ifPresent(url -> env.put(baseUrlVariable(apiType), url.toString()));
		return Map.copyOf(env);
	}
}
