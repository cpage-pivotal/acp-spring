package org.springaicommunity.acp.boot;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bound Tanzu AI Models service, as {@code VCAP_SERVICES} describes it, becoming the
 * provider — and staying out of the way of a provider the application configured itself.
 */
class TanzuAiEnvironmentPostProcessorTests {

	private static final String CATALOG = """
			{"advertisedModels":[
			  {"name":"nomic-embed","capabilities":["EMBEDDING"]},
			  {"name":"gemma-4-31b","capabilities":["CHAT","TOOLS"]},
			  {"name":"deepseek-v4","capabilities":["CHAT","TOOLS"]}]}""";

	private final List<String> catalogRequests = new ArrayList<>();

	private final MockEnvironment environment = new MockEnvironment();

	private static String binding(String label, String name, String apiBase) {
		return """
				"%s": [{
				  "name": "%s",
				  "tags": [],
				  "credentials": {"endpoint": {
				    "api_key": "key-for-%s",
				    "api_base": "%s",
				    "config_url": "https://models.example.com/%s/config"
				  }}
				}]""".formatted(label, name, name, apiBase, name);
	}

	private void bind(String... bindings) {
		environment.setProperty("VCAP_SERVICES", "{" + String.join(",", bindings) + "}");
	}

	private void process() {
		new TanzuAiEnvironmentPostProcessor(new NoOpLog(), (url, key) -> {
			catalogRequests.add(url + " " + key);
			return CATALOG;
		}).postProcessEnvironment(environment, new SpringApplication());
	}

	@Test
	void aBoundServiceBecomesAnOpenAiCompatibleEndpointWithAToolCapableModel() {
		bind(binding("genai", "tanzu-models", "https://genai.example.com/tanzu-models/openai/"));

		process();

		assertThat(environment.getProperty("spring.acp.provider.base-url"))
			.isEqualTo("https://genai.example.com/tanzu-models/openai");
		assertThat(environment.getProperty("spring.acp.provider.api-key")).isEqualTo("key-for-tanzu-models");
		assertThat(environment.getProperty("spring.acp.provider.id")).isEqualTo("openai");
		assertThat(environment.getProperty("spring.acp.provider.api-type")).isEqualTo("openai");
		assertThat(environment.getProperty("spring.acp.model")).isEqualTo("gemma-4-31b");
		assertThat(catalogRequests)
			.containsExactly("https://models.example.com/tanzu-models/config key-for-tanzu-models");
	}

	@Test
	void aConfiguredModelIsKeptAndTheCatalogIsNotRead() {
		bind(binding("genai", "tanzu-models", "https://genai.example.com/openai"));
		environment.setProperty("spring.acp.model", "deepseek-v4");

		process();

		assertThat(environment.getProperty("spring.acp.model")).isEqualTo("deepseek-v4");
		assertThat(catalogRequests).isEmpty();
	}

	@Test
	void blankProviderValuesCountAsNotConfigured() {
		bind(binding("genai", "tanzu-models", "https://genai.example.com/openai"));
		environment.setProperty("spring.acp.provider.api-key", "");
		environment.setProperty("spring.acp.provider.base-url", " ");
		environment.setProperty("spring.acp.model", "");

		process();

		assertThat(environment.getProperty("spring.acp.provider.api-key")).isEqualTo("key-for-tanzu-models");
		assertThat(environment.getProperty("spring.acp.model")).isEqualTo("gemma-4-31b");
	}

	@Test
	void aProviderTheApplicationConfiguredIsLeftAlone() {
		bind(binding("genai", "tanzu-models", "https://genai.example.com/openai"));
		environment.setProperty("spring.acp.provider.api-key", "sk-my-own");

		process();

		assertThat(environment.getProperty("spring.acp.provider.api-key")).isEqualTo("sk-my-own");
		assertThat(environment.getProperty("spring.acp.provider.base-url")).isNull();
		assertThat(environment.getPropertySources().contains(TanzuAiEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
			.isFalse();
	}

	@Test
	void aProviderThatIsNotOpenAiIsLeftAlone() {
		bind(binding("genai", "tanzu-models", "https://genai.example.com/openai"));
		environment.setProperty("spring.acp.provider.id", "anthropic");

		process();

		assertThat(environment.getProperty("spring.acp.provider.base-url")).isNull();
	}

	@Test
	void theServiceNameChoosesAmongSeveralBindings() {
		bind(binding("genai", "first", "https://genai.example.com/first/openai"),
				binding("ai-models", "second", "https://genai.example.com/second/openai"));
		environment.setProperty("spring.acp.tanzu-ai.service-name", "second");

		process();

		assertThat(environment.getProperty("spring.acp.provider.base-url"))
			.isEqualTo("https://genai.example.com/second/openai");
	}

	@Test
	void anUnknownServiceNameUsesNothing() {
		bind(binding("genai", "first", "https://genai.example.com/first/openai"));
		environment.setProperty("spring.acp.tanzu-ai.service-name", "missing");

		process();

		assertThat(environment.getProperty("spring.acp.provider.base-url")).isNull();
	}

	@Test
	void aServiceIsRecognisedByItsGenaiTag() {
		bind("""
				"user-provided": [{"name": "my-models", "tags": ["GenAI"],
				  "credentials": {"endpoint": {"api_key": "k", "api_base": "https://m.example.com/openai",
				  "config_url": "https://m.example.com/config"}}}]""");

		process();

		assertThat(environment.getProperty("spring.acp.provider.base-url")).isEqualTo("https://m.example.com/openai");
	}

	@Test
	void otherServicesAndIncompleteBindingsAreIgnored() {
		bind("""
				"p-mysql": [{"name": "db", "tags": ["mysql"], "credentials": {"uri": "mysql://x"}}]""", """
				"genai": [{"name": "old", "credentials": {"api_base": "https://m.example.com"}}]""");

		process();

		assertThat(environment.getProperty("spring.acp.provider.base-url")).isNull();
	}

	@Test
	void canBeTurnedOff() {
		bind(binding("genai", "tanzu-models", "https://genai.example.com/openai"));
		environment.setProperty("spring.acp.tanzu-ai.enabled", "false");

		process();

		assertThat(environment.getProperty("spring.acp.provider.base-url")).isNull();
	}

	@Test
	void anUnreadableCatalogStillConfiguresTheEndpointButNoModel() {
		bind(binding("genai", "tanzu-models", "https://genai.example.com/openai"));

		new TanzuAiEnvironmentPostProcessor(new NoOpLog(), (url, key) -> {
			throw new IOException("HTTP 503");
		}).postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("spring.acp.provider.base-url"))
			.isEqualTo("https://genai.example.com/openai");
		assertThat(environment.getProperty("spring.acp.model")).isNull();
	}

	@Test
	void readsTheCatalogOverHttpWithTheServicesKey() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		List<String> authorizations = new ArrayList<>();
		server.createContext("/config", exchange -> {
			authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = CATALOG.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String catalog = TanzuAiEnvironmentPostProcessor
				.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/config"), "secret");

			assertThat(TanzuAiBinding.firstToolCapableModel(catalog)).contains("gemma-4-31b");
			assertThat(authorizations).containsExactly("Bearer secret");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void neverPrintsTheKey() {
		TanzuAiBinding binding = TanzuAiBinding.all("{" + binding("genai", "m", "https://g.example.com/openai") + "}")
			.get(0);

		assertThat(binding.toString()).doesNotContain("key-for-m");
	}

	@Test
	void isRegisteredToRunOnEveryStartAndBootCanBuildIt() {
		DeferredLogFactory logs = destination -> destination.get();
		// Boot hands each post-processor what its constructor asks for; the others on the
		// classpath want arguments this test does not have, and are skipped.
		List<EnvironmentPostProcessor> loaded = SpringFactoriesLoader.forDefaultResourceLocation()
			.load(EnvironmentPostProcessor.class,
					SpringFactoriesLoader.ArgumentResolver.of(DeferredLogFactory.class, logs),
					SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {
					}));

		assertThat(loaded).filteredOn(TanzuAiEnvironmentPostProcessor.class::isInstance)
			.singleElement()
			.satisfies(processor -> assertThat(((TanzuAiEnvironmentPostProcessor) processor).getOrder())
				.isGreaterThan(org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER));
	}

}
