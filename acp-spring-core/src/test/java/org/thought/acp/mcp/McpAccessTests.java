package org.thought.acp.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.session.SessionPrincipal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The proxy against a real upstream on a real socket.
 *
 * <p>Not mocked, because everything it gets wrong is invisible to a mock: a header line that should
 * not exist, a body announced as chunked that has no bytes, a stream held in a buffer. Each of those
 * made a real agent drop an MCP server without a word.
 */
class McpAccessTests {

	private static final Duration LIMIT = Duration.ofSeconds(10);

	private final HttpClient agent = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final Upstream upstream = new Upstream();

	private McpAccess access;

	@AfterEach
	void close() {
		if (access != null) {
			access.close();
		}
		upstream.close();
	}

	private McpServerSpec.Http tools() {
		return new McpServerSpec.Http("tools", upstream.url(), Map.of("X-Configured", "from-spec"));
	}

	private McpAccess access(McpCredentialsProvider provider) {
		access = new McpAccess(provider, LIMIT);
		return access;
	}

	private static McpCredentialsProvider perUser() {
		return (server, principal) -> Optional
			.of(McpCredentials.bearer(() -> "token-" + (principal == null ? "nobody" : principal.name())));
	}

	private HttpResponse<String> post(URI url, Map<String, String> headers, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(LIMIT)
			.POST(HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(request::header);
		return agent.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static URI urlOf(McpAccess.Grant grant, String name) {
		return grant.servers().stream().filter(s -> s.name().equals(name)).map(McpServerSpec.Http.class::cast)
			.findFirst().orElseThrow().url();
	}

	// --- what the agent is told ---------------------------------------------------------------

	@Test
	void withoutAProviderEveryServerIsHandedOverAsConfiguredAndNoPortIsOpened() {
		McpAccess access = access(McpCredentialsProvider.none());
		McpServerSpec stdio = new McpServerSpec.Stdio("local", "tool", List.of(), Map.of());

		McpAccess.Grant grant = access.grant(SessionPrincipal.of("alice"), List.of(tools(), stdio));

		assertThat(grant.servers()).containsExactly(tools(), stdio);
		assertThat(access.activeGrants()).isZero();
	}

	@Test
	void aCredentialedServerIsHandedOverAsALoopbackUrlCarryingNoHeaders() {
		McpServerSpec.Http open = new McpServerSpec.Http("open", URI.create("https://open.example.com/mcp"), Map.of());
		McpAccess access = access((server, principal) -> server.name().equals("tools")
				? Optional.of(McpCredentials.bearer(() -> "secret")) : Optional.empty());

		McpAccess.Grant grant = access.grant(null, List.of(tools(), open));

		McpServerSpec.Http proxied = (McpServerSpec.Http) grant.servers().get(0);
		assertThat(proxied.name()).isEqualTo("tools");
		assertThat(proxied.url().getScheme()).isEqualTo("http");
		assertThat(proxied.url().getHost()).isEqualTo("127.0.0.1");
		assertThat(proxied.url().getPath()).endsWith("/tools").hasSizeGreaterThan(20);
		assertThat(proxied.headers()).isEmpty();
		assertThat(grant.servers().get(1)).isEqualTo(open);
		assertThat(access.activeGrants()).isOne();
	}

	// --- what the upstream receives -------------------------------------------------------------

	@Test
	void forwardsTheRequestWithTheSessionsCredentialsInPlaceOfAnythingTheAgentSent() throws Exception {
		McpAccess.Grant grant = access(perUser()).grant(SessionPrincipal.of("alice"), List.of(tools()));

		HttpResponse<String> response = post(urlOf(grant, "tools") , Map.of("Authorization", "Bearer forged",
				"User-Agent", "goose/1.51", "Mcp-Session-Id", "abc", "Content-Type", "application/json"),
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"ok\":true}");
		assertThat(response.headers().firstValue("mcp-session-id")).hasValue("upstream-session");
		Seen seen = upstream.requests.get(0);
		assertThat(seen.method()).isEqualTo("POST");
		assertThat(seen.body()).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
		assertThat(seen.headers().get("Authorization")).containsExactly("Bearer token-alice");
		assertThat(seen.headers().get("User-agent")).containsExactly("goose/1.51");
		assertThat(seen.headers().get("Mcp-session-id")).containsExactly("abc");
		assertThat(seen.headers().get("X-configured")).containsExactly("from-spec");
	}

	@Test
	void asksForCredentialsOnEveryRequestSoARefreshLandsMidSession() throws Exception {
		AtomicInteger issued = new AtomicInteger();
		McpAccess.Grant grant = access((server, principal) -> Optional
			.of(McpCredentials.bearer(() -> "token-" + issued.incrementAndGet()))).grant(null, List.of(tools()));

		post(urlOf(grant, "tools"), Map.of(), "{}");
		post(urlOf(grant, "tools"), Map.of(), "{}");

		assertThat(upstream.requests).extracting(seen -> seen.headers().get("Authorization").get(0))
			.containsExactly("Bearer token-1", "Bearer token-2");
	}

	@Test
	void twoUsersSessionsNeverCarryEachOthersToken() throws Exception {
		McpAccess access = access(perUser());
		McpAccess.Grant alice = access.grant(SessionPrincipal.of("alice"), List.of(tools()));
		McpAccess.Grant bob = access.grant(SessionPrincipal.of("bob"), List.of(tools()));

		post(urlOf(bob, "tools"), Map.of(), "{}");
		post(urlOf(alice, "tools"), Map.of(), "{}");

		assertThat(urlOf(alice, "tools")).isNotEqualTo(urlOf(bob, "tools"));
		assertThat(upstream.requests).extracting(seen -> seen.headers().get("Authorization").get(0))
			.containsExactly("Bearer token-bob", "Bearer token-alice");
	}

	// --- who gets in ------------------------------------------------------------------------------

	@Test
	void aClosedOrUnknownRouteIsNotFoundAndReachesNoUpstream() throws Exception {
		McpAccess.Grant grant = access(perUser()).grant(SessionPrincipal.of("alice"), List.of(tools()));
		URI url = urlOf(grant, "tools");
		URI guessed = url.resolve("/AAAAAAAAAAAAAAAAAAAAAA/tools");
		URI otherServer = url.resolve(url.getPath().replace("/tools", "/other"));

		grant.close();
		grant.close();

		assertThat(post(url, Map.of(), "{}").statusCode()).isEqualTo(404);
		assertThat(post(guessed, Map.of(), "{}").statusCode()).isEqualTo(404);
		assertThat(post(otherServer, Map.of(), "{}").statusCode()).isEqualTo(404);
		assertThat(upstream.requests).isEmpty();
		assertThat(access.activeGrants()).isZero();
	}

	@Test
	void aProviderThatRefusesOneServerLeavesNoRouteBehind() {
		McpServerSpec.Http second = new McpServerSpec.Http("second", upstream.url(), Map.of());
		McpAccess access = access((server, principal) -> {
			if (server.name().equals("second")) {
				throw new IllegalStateException("alice has not signed in to second");
			}
			return Optional.of(McpCredentials.bearer(() -> "t"));
		});

		assertThatThrownBy(() -> access.grant(SessionPrincipal.of("alice"), List.of(tools(), second)))
			.hasMessageContaining("not signed in");
		assertThat(access.activeGrants()).isZero();
	}

	@Test
	void credentialsThatFailFailOnlyThatRequest() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		McpAccess.Grant grant = access((server, principal) -> Optional.of(() -> {
			if (calls.incrementAndGet() == 1) {
				throw new IllegalStateException("refresh refused");
			}
			return Map.of("Authorization", "Bearer recovered");
		})).grant(null, List.of(tools()));

		assertThat(post(urlOf(grant, "tools"), Map.of(), "{}").statusCode()).isEqualTo(502);
		assertThat(post(urlOf(grant, "tools"), Map.of(), "{}").statusCode()).isEqualTo(200);
		assertThat(upstream.requests).hasSize(1);
	}

	@Test
	void closingTheAccessStopsTheProxyAndRefusesNewGrants() throws Exception {
		McpAccess access = access(perUser());
		McpAccess.Grant grant = access.grant(null, List.of(tools()));

		access.close();

		assertThatThrownBy(() -> post(urlOf(grant, "tools"), Map.of(), "{}")).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> access.grant(null, List.of(tools()))).isInstanceOf(IllegalStateException.class);
	}

	// --- the forwarding details that fail silently ----------------------------------------------

	@Test
	void anAcceptedNotificationIsAnsweredWithNoBodyAtAll() throws Exception {
		upstream.respond(exchange -> exchange.sendResponseHeaders(202, -1));
		McpAccess.Grant grant = access(perUser()).grant(null, List.of(tools()));

		HttpResponse<String> response = post(urlOf(grant, "tools"), Map.of(), "{\"method\":\"notifications/initialized\"}");

		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(response.body()).isEmpty();
		assertThat(response.headers().firstValue("transfer-encoding")).isEmpty();
	}

	@Test
	void anEventStreamReachesTheAgentBeforeTheUpstreamFinishesIt() throws Exception {
		CountDownLatch firstEventRead = new CountDownLatch(1);
		upstream.respond(exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			OutputStream out = exchange.getResponseBody();
			out.write("data: one\n\n".getBytes(StandardCharsets.UTF_8));
			out.flush();
			try {
				// Holds the stream open until the agent has seen the first event. Buffered anywhere
				// on the way, it never would, and this times out.
				firstEventRead.await(LIMIT.toSeconds(), TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			out.write("data: two\n\n".getBytes(StandardCharsets.UTF_8));
			out.close();
		});
		McpAccess.Grant grant = access(perUser()).grant(null, List.of(tools()));

		HttpResponse<InputStream> response = agent.send(HttpRequest.newBuilder(urlOf(grant, "tools")).timeout(LIMIT)
			.header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
		try (BufferedReader events = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			// A deadline well inside the upstream's hold: buffered, this line would only arrive
			// once the upstream gave up waiting, and the read would time out first.
			String first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
				try {
					return events.readLine();
				}
				catch (IOException ex) {
					throw new java.io.UncheckedIOException(ex);
				}
			}).get(3, TimeUnit.SECONDS);
			assertThat(first).isEqualTo("data: one");
			firstEventRead.countDown();
			assertThat(events.readLine()).isEmpty();
			assertThat(events.readLine()).isEqualTo("data: two");
		}
		assertThat(upstream.requests.get(0).method()).isEqualTo("GET");
	}

	@Test
	void theRequestTimeoutBoundsTheWaitForAnAnswerNotTheLengthOfAStream() throws Exception {
		upstream.respond(exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			OutputStream out = exchange.getResponseBody();
			out.write("data: one\n\n".getBytes(StandardCharsets.UTF_8));
			out.flush();
			try {
				Thread.sleep(1500);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			out.write("data: two\n\n".getBytes(StandardCharsets.UTF_8));
			out.close();
		});
		access = new McpAccess(perUser(), Duration.ofMillis(500));
		McpAccess.Grant grant = access.grant(null, List.of(tools()));

		HttpResponse<String> response = agent.send(HttpRequest.newBuilder(urlOf(grant, "tools")).timeout(LIMIT)
			.GET().build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.body()).isEqualTo("data: one\n\ndata: two\n\n");
	}

	@Test
	void anUpstreamChallengeIsNotPassedOnWhereTheProxyHoldsTheCredentials() throws Exception {
		upstream.respond(exchange -> {
			exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer resource_metadata=\"https://as.example.com\"");
			exchange.sendResponseHeaders(401, -1);
		});
		McpAccess credentialed = access(perUser());
		McpAccess.Grant withToken = credentialed.grant(SessionPrincipal.of("alice"), List.of(tools()));
		try (McpAccess filtersOnly = new McpAccess(McpCredentialsProvider.none(), LIMIT,
				List.of(McpRequestFilter.McpRequest::forward))) {
			McpAccess.Grant withoutToken = filtersOnly.grant(null, List.of(tools()));

			HttpResponse<String> hidden = post(urlOf(withToken, "tools"), Map.of(), "{}");
			HttpResponse<String> passed = post(urlOf(withoutToken, "tools"), Map.of(), "{}");

			assertThat(hidden.statusCode()).isEqualTo(401);
			assertThat(hidden.headers().firstValue("www-authenticate")).isEmpty();
			// With no credentials of its own, the proxy has no business hiding how to get some.
			assertThat(passed.headers().firstValue("www-authenticate")).isPresent();
		}
	}

	// --- the agent's own workarounds -----------------------------------------------------------

	@Test
	void aFilterThatAnswersEndsTheChainAndNothingReachesTheUpstream() throws Exception {
		List<String> ran = new CopyOnWriteArrayList<>();
		McpRequestFilter answers = request -> {
			ran.add("answers:" + request.server());
			return McpRequestFilter.Outcome.Answer.json(200, "{\"answered\":\"locally\"}");
		};
		McpRequestFilter never = request -> {
			ran.add("never");
			return request.forward();
		};
		access = new McpAccess(perUser(), LIMIT, List.of(answers, never));
		McpAccess.Grant grant = access.grant(null, List.of(tools()));

		HttpResponse<String> response = post(urlOf(grant, "tools"), Map.of(), "{}");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"answered\":\"locally\"}");
		assertThat(response.headers().firstValue("content-type")).hasValue("application/json");
		assertThat(ran).containsExactly("answers:tools");
		assertThat(upstream.requests).isEmpty();
	}

	@Test
	void aFilterCanChangeWhatIsForwardedButNotTheCredentials() throws Exception {
		McpRequestFilter dropsVersion = request -> request.withoutHeader("mcp-protocol-version").forward();
		McpRequestFilter triesToForge = request -> new McpRequestFilter.McpRequest(request.server(), request.method(),
				Map.of("Authorization", List.of("Bearer forged")), request.body()).forward();
		access = new McpAccess(perUser(), LIMIT, List.of(dropsVersion, triesToForge));
		McpAccess.Grant grant = access.grant(SessionPrincipal.of("alice"), List.of(tools()));

		post(urlOf(grant, "tools"), Map.of("MCP-Protocol-Version", "2026-07-28"), "{}");

		Seen seen = upstream.requests.get(0);
		assertThat(seen.headers()).doesNotContainKey("Mcp-protocol-version");
		assertThat(seen.headers().get("Authorization")).containsExactly("Bearer token-alice");
	}

	@Test
	void whileThereAreFiltersEveryHttpServerGoesThroughTheProxyCredentialedOrNot() throws Exception {
		access = new McpAccess(McpCredentialsProvider.none(), LIMIT, List.of(McpRequestFilter.McpRequest::forward));
		McpAccess.Grant grant = access.grant(null, List.of(tools()));

		assertThat(urlOf(grant, "tools").getHost()).isEqualTo("127.0.0.1");
		post(urlOf(grant, "tools"), Map.of(), "{}");
		assertThat(upstream.requests.get(0).headers()).doesNotContainKey("Authorization");
		assertThat(upstream.requests.get(0).headers().get("X-configured")).containsExactly("from-spec");
	}

	@Test
	void pseudoHeadersAndCredentialsNeverCrossTheProxy() {
		assertThat(McpProxy.passes(":status")).isFalse();
		assertThat(McpProxy.passes("Authorization")).isFalse();
		assertThat(McpProxy.passes("Transfer-Encoding")).isFalse();
		assertThat(McpProxy.passes("Mcp-Session-Id")).isTrue();
		assertThat(McpProxy.passes("User-Agent")).isTrue();
	}

	// --- the fake upstream ----------------------------------------------------------------------

	record Seen(String method, Map<String, List<String>> headers, String body) {
	}

	interface Handler {

		void handle(HttpExchange exchange) throws IOException;

	}

	/** A real MCP-shaped HTTP endpoint that records what it was sent. */
	static final class Upstream implements AutoCloseable {

		final List<Seen> requests = new CopyOnWriteArrayList<>();

		private final HttpServer server;

		private volatile Handler handler = exchange -> {
			byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.getResponseHeaders().add("Mcp-Session-Id", "upstream-session");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
		};

		Upstream() {
			try {
				server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
			server.createContext("/mcp", exchange -> {
				try (exchange) {
					requests.add(new Seen(exchange.getRequestMethod(), Map.copyOf(exchange.getRequestHeaders()),
							new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
					handler.handle(exchange);
				}
			});
			server.start();
		}

		URI url() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
		}

		void respond(Handler handler) {
			this.handler = handler;
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
