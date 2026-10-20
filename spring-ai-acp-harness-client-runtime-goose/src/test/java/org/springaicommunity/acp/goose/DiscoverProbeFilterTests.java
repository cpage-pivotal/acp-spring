package org.springaicommunity.acp.goose;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.config.McpServerSpec;
import org.springaicommunity.acp.mcp.McpAccess;
import org.springaicommunity.acp.mcp.McpCredentialsProvider;
import org.springaicommunity.acp.mcp.McpRequestFilter;
import org.springaicommunity.acp.mcp.McpRequestFilter.McpRequest;
import org.springaicommunity.acp.mcp.McpRequestFilter.Outcome;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoverProbeFilterTests {

	private static final Duration LIMIT = Duration.ofSeconds(10);

	@TempDir
	Path workspace;

	private final DiscoverProbeFilter filter = new DiscoverProbeFilter();

	private static McpRequest post(String body, Map<String, List<String>> headers) {
		return new McpRequest("finops-mcp", "POST", headers, body.getBytes(StandardCharsets.UTF_8));
	}

	private static String bodyOf(Outcome outcome) {
		return new String(((Outcome.Answer) outcome).body(), StandardCharsets.UTF_8);
	}

	// --- the filter on its own
	// ------------------------------------------------------------------

	@Test
	void answersTheProbeAsAServerThatHasNeverHeardOfItWouldWithTheRequestsOwnId() {
		Outcome numeric = filter
			.filter(post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"server/discover\"}", Map.of()));
		Outcome text = filter
			.filter(post("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"server/discover\"}", Map.of()));

		assertThat(((Outcome.Answer) numeric).status()).isEqualTo(200);
		assertThat(((Outcome.Answer) numeric).contentType()).isEqualTo("application/json");
		assertThat(bodyOf(numeric))
			.isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
		assertThat(bodyOf(text)).contains("\"id\":\"abc\"");
	}

	@Test
	void aProbeSentAsANotificationGetsNoAnswerBody() {
		Outcome outcome = filter.filter(post("{\"jsonrpc\":\"2.0\",\"method\":\"server/discover\"}", Map.of()));

		assertThat(((Outcome.Answer) outcome).status()).isEqualTo(202);
		assertThat(((Outcome.Answer) outcome).body()).isEmpty();
	}

	@Test
	void dropsOnlyTheProbeVersionHeaderAndForwardsEverythingElseAsItCame() {
		Map<String, List<String>> probeVersion = Map.of("Mcp-protocol-version", List.of("2026-07-28"), "User-agent",
				List.of("goose/1.51"));
		Map<String, List<String>> olderVersion = Map.of("Mcp-protocol-version", List.of("2025-06-18"));

		McpRequest stripped = ((Outcome.Forward) filter
			.filter(post("{\"id\":1,\"method\":\"initialize\"}", probeVersion))).request();
		McpRequest kept = ((Outcome.Forward) filter.filter(post("{\"id\":2,\"method\":\"tools/list\"}", olderVersion)))
			.request();
		McpRequest notJson = ((Outcome.Forward) filter.filter(post("not json", Map.of()))).request();

		assertThat(stripped.header("MCP-Protocol-Version")).isEmpty();
		assertThat(stripped.header("user-agent")).containsExactly("goose/1.51");
		assertThat(kept.header("mcp-protocol-version")).containsExactly("2025-06-18");
		assertThat(new String(notJson.body(), StandardCharsets.UTF_8)).isEqualTo("not json");
	}

	@Test
	void isOffUnlessTheApplicationAsksForIt() {
		GooseRuntime goose = new GooseRuntime();
		AgentSettings.Builder settings = AgentSettings.builder("goose", workspace);

		assertThat(goose.mcpRequestFilters(settings.build())).isEmpty();
		assertThat(goose.mcpRequestFilters(settings.runtimeOptions(Map.of("mcp.answer-discover", "false")).build()))
			.isEmpty();
		assertThat(goose
			.mcpRequestFilters(settings.runtimeOptions(Map.of("mcp", Map.of("answer-discover", true))).build()))
			.singleElement()
			.isInstanceOf(DiscoverProbeFilter.class);
	}

	// --- through the proxy, against a gateway with the bug
	// -------------------------------------

	private final BrokenGateway gateway = new BrokenGateway();

	@AfterEach
	void stopGateway() {
		gateway.close();
	}

	private static HttpResponse<String> send(URI url, String body, String version) throws Exception {
		return HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(url)
				.timeout(LIMIT)
				.header("Content-Type", "application/json")
				.header("MCP-Protocol-Version", version)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI routeThrough(McpAccess access) {
		McpServerSpec.Http server = new McpServerSpec.Http("finops-mcp", gateway.url(), Map.of());
		return ((McpServerSpec.Http) access.grant(null, List.of(server)).servers().get(0)).url();
	}

	@Test
	void withoutTheFilterGooseGetsAnAnswerItCannotCorrelate() throws Exception {
		// What goose 1.51 saw: an error whose id is not the request's, so it never falls
		// back.
		try (McpAccess access = new McpAccess(McpCredentialsProvider.none(), LIMIT,
				List.of(McpRequestFilter.McpRequest::forward))) {
			HttpResponse<String> response = send(routeThrough(access),
					"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}", DiscoverProbeFilter.PROBE_VERSION);

			assertThat(response.statusCode()).isEqualTo(400);
			assertThat(response.body()).contains("\"id\":\"server-error\"");
		}
	}

	@Test
	void withTheFilterTheProbeFailsCleanlyAndTheFallbackConnects() throws Exception {
		try (McpAccess access = new McpAccess(McpCredentialsProvider.none(), LIMIT,
				List.of(new DiscoverProbeFilter()))) {
			URI url = routeThrough(access);

			HttpResponse<String> discover = send(url, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}",
					DiscoverProbeFilter.PROBE_VERSION);
			HttpResponse<String> initialize = send(url, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\"}",
					DiscoverProbeFilter.PROBE_VERSION);

			assertThat(discover.body()).contains("\"id\":1").contains("-32601");
			assertThat(initialize.statusCode()).isEqualTo(200);
			assertThat(initialize.body()).contains("\"result\"");
			assertThat(gateway.methods).containsExactly("initialize");
		}
	}

	/**
	 * The Tanzu MCP gateway's behaviour as measured: {@code server/discover}, or anything
	 * carrying the probe version, is refused with an error whose id is not the request's.
	 */
	static final class BrokenGateway implements AutoCloseable {

		final List<String> methods = new CopyOnWriteArrayList<>();

		private final HttpServer server;

		BrokenGateway() {
			try {
				server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			server.createContext("/mcp", exchange -> {
				try (exchange) {
					String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
					String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
					boolean refused = body.contains("server/discover")
							|| DiscoverProbeFilter.PROBE_VERSION.equals(version);
					if (!refused) {
						methods.add(body.replaceAll(".*\"method\":\"([^\"]+)\".*", "$1"));
					}
					byte[] answer = (refused
							? "{\"jsonrpc\":\"2.0\",\"id\":\"server-error\",\"error\":{\"code\":-32600,\"message\":\"Unsupported protocol version\"}}"
							: "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"protocolVersion\":\"2025-06-18\"}}")
						.getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().add("Content-Type", "application/json");
					exchange.sendResponseHeaders(refused ? 400 : 200, answer.length);
					try (OutputStream out = exchange.getResponseBody()) {
						out.write(answer);
					}
				}
			});
			server.start();
		}

		URI url() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
		}

		@Override
		public void close() {
			server.stop(0);
		}

	}

}
