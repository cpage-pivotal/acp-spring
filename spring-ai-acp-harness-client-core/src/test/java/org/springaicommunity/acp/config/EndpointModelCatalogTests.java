package org.springaicommunity.acp.config;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listing that replaces the agent's own catalogue for an endpoint the application
 * named.
 *
 * <p>
 * Against a real HTTP server rather than a mocked client: what is being tested is a wire
 * format and the handling of an endpoint that declines to speak it, neither of which a
 * mock would notice getting wrong.
 */
class EndpointModelCatalogTests {

	private HttpServer server;

	private final AtomicInteger requests = new AtomicInteger();

	private String lastAuthorization;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	private ProviderSpec serving(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/models", exchange -> {
			requests.incrementAndGet();
			lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
			respond(exchange, status, body);
		});
		server.start();
		return new ProviderSpec("acme", "openai",
				URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), "sk-x", Map.of());
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@Test
	void readsTheModelsAnOpenAiCompatibleEndpointLists() throws IOException {
		ProviderSpec provider = serving(200, """
				{"object":"list","data":[{"id":"acme/llm-1","object":"model"},{"id":"acme/llm-2"}]}""");

		assertThat(new EndpointModelCatalog().modelsOf(provider)).contains(List.of("acme/llm-1", "acme/llm-2"));
	}

	@Test
	void sendsTheEndpointsOwnCredential() throws IOException {
		ProviderSpec provider = serving(200, """
				{"data":[{"id":"acme/llm-1"}]}""");

		new EndpointModelCatalog().modelsOf(provider);

		assertThat(lastAuthorization).isEqualTo("Bearer sk-x");
	}

	@Test
	void asksOnceNoMatterHowManySessionsAreOpened() throws IOException {
		ProviderSpec provider = serving(200, """
				{"data":[{"id":"acme/llm-1"}]}""");
		EndpointModelCatalog catalog = new EndpointModelCatalog();

		catalog.modelsOf(provider);
		catalog.modelsOf(provider);
		catalog.modelsOf(provider);

		assertThat(requests).hasValue(1);
	}

	@Test
	void anEndpointThatPublishesNoListingRefusesNothing() throws IOException {
		ProviderSpec provider = serving(404, """
				{"detail":"No endpoint GET /v1/models."}""");

		assertThat(new EndpointModelCatalog().modelsOf(provider)).isEmpty();
	}

	@Test
	void norDoesOneThatAnswersInAShapeNobodyStandardised() throws IOException {
		ProviderSpec provider = serving(200, "<html>who knows</html>");

		assertThat(new EndpointModelCatalog().modelsOf(provider)).isEmpty();
	}

	@Test
	void aProviderWithNoEndpointIsNotAskedAtAll() {
		assertThat(new EndpointModelCatalog().modelsOf(new ProviderSpec("openai", "openai", null, "k", Map.of())))
			.isEmpty();
		assertThat(requests).hasValue(0);
	}

}
