package org.springaicommunity.acp.boot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.springaicommunity.acp.config.Validation;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Points the agent at a Tanzu AI Models service bound to the application on Cloud
 * Foundry, so that binding the service is all the configuration a model needs.
 *
 * <p>
 * The binding becomes the portable bring-your-own-endpoint configuration:
 * {@code spring.acp.provider.base-url} is the binding's {@code api_base} plus the wire
 * format its catalog names ({@code /openai}), {@code api-key} is its key, and {@code id}
 * and {@code api-type} are {@code openai}. Every runtime reaches it the way it reaches
 * any other endpoint, and the endpoint's own {@code /models} listing then decides which
 * models are valid. When no {@code spring.acp.model} is configured, the first model the
 * service's catalog marks {@code TOOLS}-capable is chosen, since an agent that cannot
 * call tools is not one.
 *
 * <p>
 * Explicit configuration wins, as a whole: a provider with its own {@code base-url} or
 * {@code api-key} is left alone, so a key for one service is never paired with another
 * service's endpoint. A blank value — {@code api-key: ${OPENAI_API_KEY:}} with the
 * variable unset — counts as not configured, which is what lets one
 * {@code application.yaml} serve a laptop and a platform.
 *
 * <p>
 * Runs after the application's own config data is loaded, so it can see what was
 * configured, and writes only keys that had no value. Off with
 * {@code spring.acp.tanzu-ai.enabled=false}; {@code spring.acp.tanzu-ai.service-name}
 * picks one binding when there are several.
 */
public class TanzuAiEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	/** Right after config data: the application's own properties are all in place. */
	public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 10;

	static final String PROPERTY_SOURCE_NAME = "springAcpTanzuAi";

	private static final Duration CATALOG_TIMEOUT = Duration.ofSeconds(15);

	/** Reads a service's model catalog. A seam for tests; production uses HTTP. */
	@FunctionalInterface
	interface Catalog {

		String fetch(URI configUrl, String apiKey) throws IOException, InterruptedException;

	}

	private final Log logger;

	private final Catalog catalogReader;

	public TanzuAiEnvironmentPostProcessor(DeferredLogFactory logs) {
		this(logs.getLog(TanzuAiEnvironmentPostProcessor.class), TanzuAiEnvironmentPostProcessor::fetch);
	}

	TanzuAiEnvironmentPostProcessor(Log logger, Catalog catalog) {
		this.logger = logger;
		this.catalogReader = catalog;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.getProperty("spring.acp.tanzu-ai.enabled", Boolean.class, true)) {
			return;
		}
		List<TanzuAiBinding> bindings = TanzuAiBinding.all(environment.getProperty("VCAP_SERVICES"));
		if (bindings.isEmpty()) {
			return;
		}
		if (hasText(environment, "spring.acp.provider.base-url")
				|| hasText(environment, "spring.acp.provider.api-key")) {
			logger.info("A Tanzu AI Models service is bound, but spring.acp.provider sets its own base-url or api-key; "
					+ "using the provider as configured");
			return;
		}
		for (String key : List.of("spring.acp.provider.id", "spring.acp.provider.api-type")) {
			String value = environment.getProperty(key);
			if (StringUtils.hasText(value) && !"openai".equals(value.strip())) {
				logger.warn("A Tanzu AI Models service is bound, but " + key + " is '" + value
						+ "'; its endpoint speaks the OpenAI API, so it is not used");
				return;
			}
		}
		String serviceName = environment.getProperty("spring.acp.tanzu-ai.service-name");
		Optional<TanzuAiBinding> found = TanzuAiBinding.find(bindings, serviceName);
		if (found.isEmpty()) {
			logger.warn("spring.acp.tanzu-ai.service-name is '" + serviceName + "', but the bound Tanzu AI Models "
					+ "service(s) are " + bindings.stream().map(TanzuAiBinding::name).toList());
			return;
		}
		TanzuAiBinding binding = found.get();

		Optional<TanzuAiBinding.Catalog> catalog = readCatalog(binding);
		String wireFormat = catalog.map(TanzuAiBinding.Catalog::wireFormat).orElse(null);
		if (wireFormat == null) {
			wireFormat = "openai";
		}
		else if (!"openai".equalsIgnoreCase(wireFormat)) {
			logger.warn("Tanzu AI Models service '" + binding.name() + "' serves the " + wireFormat
					+ " wire format rather than the OpenAI API, so it is not used");
			return;
		}

		Map<String, Object> properties = new LinkedHashMap<>();
		URI baseUrl = binding.apiFor(wireFormat);
		properties.put("spring.acp.provider.base-url", baseUrl.toString());
		properties.put("spring.acp.provider.api-key", binding.apiKey());
		if (!hasText(environment, "spring.acp.provider.id")) {
			properties.put("spring.acp.provider.id", "openai");
		}
		if (!hasText(environment, "spring.acp.provider.api-type")) {
			properties.put("spring.acp.provider.api-type", "openai");
		}
		String model = environment.getProperty("spring.acp.model");
		if (!StringUtils.hasText(model)) {
			Optional<String> chosen = catalog.flatMap(TanzuAiBinding.Catalog::toolCapableModel);
			if (catalog.isPresent() && chosen.isEmpty()) {
				logger.warn("Tanzu AI Models service '" + binding.name() + "' advertises no TOOLS-capable model; "
						+ "set spring.acp.model");
			}
			chosen.ifPresent(name -> properties.put("spring.acp.model", name));
			model = chosen.orElse(null);
		}
		environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
		logger.info("Using Tanzu AI Models service '" + binding.name() + "' at " + baseUrl.getHost()
				+ baseUrl.getRawPath() + " as the provider" + (model == null ? "" : ", model '" + model + "'")
				+ (bindings.size() > 1 ? " (one of " + bindings.size() + " bound)" : ""));
	}

	/**
	 * The service's catalog: which wire format its models speak, and which of them can
	 * call tools. Empty, with a warning, if it cannot be read; the OpenAI API is then
	 * assumed and no model is chosen.
	 */
	private Optional<TanzuAiBinding.Catalog> readCatalog(TanzuAiBinding binding) {
		if (binding.configUrl() == null) {
			logger.warn("Tanzu AI Models service '" + binding.name() + "' publishes no config_url; assuming the "
					+ "OpenAI API, and set spring.acp.model");
			return Optional.empty();
		}
		try {
			Validation.requireSecureUrl(binding.configUrl(), "Tanzu AI Models config_url");
			return Optional
				.of(TanzuAiBinding.Catalog.parse(catalogReader.fetch(binding.configUrl(), binding.apiKey())));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (IOException | RuntimeException ex) {
			logger.warn("Could not read the catalog of Tanzu AI Models service '" + binding.name() + "' ("
					+ ex.getMessage() + "); assuming the OpenAI API, and set spring.acp.model");
			return Optional.empty();
		}
	}

	private static boolean hasText(ConfigurableEnvironment environment, String key) {
		return StringUtils.hasText(environment.getProperty(key));
	}

	static String fetch(URI configUrl, String apiKey) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CATALOG_TIMEOUT).build();
		HttpResponse<String> response = client.send(HttpRequest.newBuilder(configUrl)
			.timeout(CATALOG_TIMEOUT)
			.header("Authorization", "Bearer " + apiKey)
			.header("Accept", "application/json")
			.GET()
			.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		return response.body();
	}

}
