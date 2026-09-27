package org.springaicommunity.acp.boot;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;

/**
 * A Tanzu AI Models (GenAI) service bound to a Cloud Foundry application, as
 * {@code VCAP_SERVICES} describes it.
 *
 * <p>
 * The binding carries an OpenAI-compatible endpoint rather than a model: {@code api_base}
 * and {@code api_key} reach every model the service plan offers, and {@code config_url}
 * lists them with their capabilities. Recognised the way the goose buildpack and
 * java-cfenv recognise it — a label starting {@code genai} or {@code ai-models}, or a
 * {@code genai} tag — and only with a complete {@code credentials.endpoint}.
 *
 * @param name the service instance name, for logs and for choosing between bindings
 * @param apiBase the OpenAI-compatible base URL, e.g. {@code https://host/plan/openai}
 * @param apiKey the key for it; never logged
 * @param configUrl where the service lists its models, or null
 */
record TanzuAiBinding(String name, URI apiBase, String apiKey, URI configUrl) {

	/** The capability an agent needs: a model that cannot call tools cannot drive one. */
	static final String TOOLS = "TOOLS";

	/** A record prints every component, and one of these is a credential. */
	@Override
	public String toString() {
		return "TanzuAiBinding[" + name + ", " + apiBase + "]";
	}

	/** Every complete Tanzu AI Models binding in {@code vcapServices}, in order. */
	static List<TanzuAiBinding> all(String vcapServices) {
		List<TanzuAiBinding> bindings = new ArrayList<>();
		if (vcapServices == null || vcapServices.isBlank()) {
			return bindings;
		}
		JsonParser json = JsonParserFactory.getJsonParser();
		for (Map.Entry<String, Object> offering : json.parseMap(vcapServices).entrySet()) {
			if (offering.getValue() instanceof List<?> instances) {
				for (Object instance : instances) {
					if (instance instanceof Map<?, ?> service && isAiModels(offering.getKey(), service)) {
						from(service).ifPresent(bindings::add);
					}
				}
			}
		}
		return bindings;
	}

	/**
	 * The binding named {@code serviceName}, or the first one when no name is given.
	 */
	static Optional<TanzuAiBinding> find(List<TanzuAiBinding> bindings, String serviceName) {
		return bindings.stream()
			.filter(binding -> serviceName == null || serviceName.isBlank() || serviceName.equals(binding.name()))
			.findFirst();
	}

	/**
	 * The first model the service offers that can call tools, according to its catalog
	 * document ({@code {"advertisedModels":[{"name":…,"capabilities":[…]}]}}).
	 */
	static Optional<String> firstToolCapableModel(String catalog) {
		Object models = JsonParserFactory.getJsonParser().parseMap(catalog).get("advertisedModels");
		if (!(models instanceof List<?> list)) {
			return Optional.empty();
		}
		for (Object entry : list) {
			if (entry instanceof Map<?, ?> model && model.get("name") instanceof String name && !name.isBlank()
					&& model.get("capabilities") instanceof List<?> capabilities && capabilities.stream()
						.anyMatch(capability -> TOOLS.equalsIgnoreCase(String.valueOf(capability)))) {
				return Optional.of(name);
			}
		}
		return Optional.empty();
	}

	private static boolean isAiModels(String label, Map<?, ?> service) {
		String lower = label.toLowerCase(Locale.ROOT);
		if (lower.startsWith("genai") || lower.startsWith("ai-models")) {
			return true;
		}
		return service.get("tags") instanceof List<?> tags
				&& tags.stream().anyMatch(tag -> "genai".equalsIgnoreCase(String.valueOf(tag)));
	}

	private static Optional<TanzuAiBinding> from(Map<?, ?> service) {
		if (!(service.get("credentials") instanceof Map<?, ?> credentials)
				|| !(credentials.get("endpoint") instanceof Map<?, ?> endpoint)) {
			return Optional.empty();
		}
		String apiBase = text(endpoint.get("api_base"));
		String apiKey = text(endpoint.get("api_key"));
		if (apiBase == null || apiKey == null) {
			return Optional.empty();
		}
		String configUrl = text(endpoint.get("config_url"));
		String name = Optional.ofNullable(text(service.get("name")))
			.or(() -> Optional.ofNullable(text(service.get("instance_name"))))
			.orElse("ai-models");
		return Optional.of(new TanzuAiBinding(name, URI.create(stripTrailingSlashes(apiBase)), apiKey,
				configUrl == null ? null : URI.create(configUrl)));
	}

	private static String text(Object value) {
		return value instanceof String s && !s.isBlank() ? s.strip() : null;
	}

	private static String stripTrailingSlashes(String url) {
		String result = url;
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}

}
