package org.springaicommunity.acp.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.mcp.McpRequestFilter.McpRequest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A loopback HTTP server standing in for the real MCP servers, so the agent never holds a
 * credential.
 *
 * <p>
 * Every session gets its own path prefix, a random 128-bit token, and each of its servers
 * sits under that: {@code http://127.0.0.1:<port>/<token>/<server>}. The token is the
 * access control. A loopback port is reachable by every process on the machine, so a
 * route keyed only by server name would let any of them spend any user's credentials;
 * keyed by a secret that only one agent session was ever told, it lets nobody else in. An
 * unknown or closed route is a 404.
 *
 * <p>
 * Everything else is forwarding, and the details below each cost a silent failure to find
 * — silent because an agent that cannot use an MCP server says nothing over ACP. They
 * were measured against goose 1.51 in front of a Cloudflare-fronted MCP gateway:
 * <ul>
 * <li>HTTP/2 pseudo-headers ({@code :status}) never cross into the HTTP/1.1 response. The
 * JDK hands them back like any other header, and one copied through makes goose accept
 * {@code initialize} and then send nothing further: no tools, no error.</li>
 * <li>A bodiless response is declared bodiless ({@code -1}). A notification answered
 * {@code 202} with a chunked body makes goose hang up and report the server as
 * failed.</li>
 * <li>Response bodies are flushed chunk by chunk, so an SSE stream reaches the agent as
 * it is written rather than when the upstream closes it.</li>
 * <li>The agent's own headers pass through, {@code User-Agent} included: an edge that
 * sees a request with none may refuse it with a 403 that has nothing to do with MCP.</li>
 * </ul>
 */
final class McpProxy implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(McpProxy.class);

	/**
	 * Hop-by-hop headers, the ones the JDK client refuses to set, and
	 * {@code Authorization}: the proxy is the only authority on credentials, so whatever
	 * the agent sent is dropped rather than trusted.
	 */
	private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "content-length", "authorization",
			"accept-encoding", "upgrade", "transfer-encoding", "expect", "keep-alive", "te", "trailer",
			"proxy-authorization", "proxy-connection");

	private static final SecureRandom RANDOM = new SecureRandom();

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	/**
	 * Virtual threads: an SSE stream holds its thread for as long as the agent listens.
	 */
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

	private final Map<String, Map<String, Upstream>> routes = new ConcurrentHashMap<>();

	private final HttpServer server;

	private final Duration requestTimeout;

	private final List<McpRequestFilter> filters;

	/**
	 * One upstream MCP server as one session reaches it.
	 *
	 * @param name the server's configured name, as filters see it
	 * @param headers configured on the server spec; kept here rather than handed to the
	 * agent
	 */
	record Upstream(String name, URI url, Map<String, String> headers, McpCredentials credentials) {
	}

	private McpProxy(HttpServer server, Duration requestTimeout, List<McpRequestFilter> filters) {
		this.server = server;
		this.requestTimeout = requestTimeout;
		this.filters = List.copyOf(filters);
	}

	/**
	 * Binds 127.0.0.1 on a port the OS picks. Nothing off this machine can reach it.
	 * @param requestTimeout how long to wait for an upstream to start answering; a stream
	 * it then sends is not cut off by it
	 * @param filters run in order on every request; see {@link McpRequestFilter}
	 */
	static McpProxy start(Duration requestTimeout, List<McpRequestFilter> filters) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			McpProxy proxy = new McpProxy(server, requestTimeout, filters);
			server.createContext("/", proxy::handle);
			server.setExecutor(proxy.workers);
			startAsDaemon(server);
			logger.debug("MCP loopback proxy listening on {}", server.getAddress());
			return proxy;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not start the loopback MCP proxy", ex);
		}
	}

	/**
	 * Starts the server so that it cannot keep the JVM alive.
	 *
	 * <p>
	 * {@code HttpServer} runs its dispatcher on a thread it creates in {@code start()},
	 * and a new thread is a daemon only if the thread that made it was. Started from an
	 * application's main thread, the dispatcher is not one — and a terminal application
	 * that returned from {@code main} without closing its context was kept alive by the
	 * proxy indefinitely, measured with acp-meridian. The proxy serves an agent this JVM
	 * started; it has no business outliving it.
	 */
	private static void startAsDaemon(HttpServer server) {
		Thread starter = new Thread(server::start, "mcp-proxy-start");
		starter.setDaemon(true);
		starter.start();
		try {
			starter.join();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			server.stop(0);
			throw new IllegalStateException("Interrupted starting the loopback MCP proxy", ex);
		}
	}

	/**
	 * Publishes one session's servers under a fresh token.
	 * @return the token, which {@link #url} turns into the address to hand the agent
	 */
	String register(Map<String, Upstream> upstreams) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		routes.put(token, Map.copyOf(upstreams));
		return token;
	}

	void unregister(String token) {
		routes.remove(token);
	}

	URI url(String token, String serverName) {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + token + "/" + serverName);
	}

	int routeCount() {
		return routes.size();
	}

	private void handle(HttpExchange exchange) {
		try {
			Upstream upstream = lookup(exchange.getRequestURI().getRawPath());
			if (upstream == null) {
				// Deliberately uninformative: a caller without the token learns nothing
				// from the answer.
				respond(exchange, 404, "{\"error\":\"not found\"}");
				return;
			}
			forward(exchange, upstream);
		}
		catch (Exception ex) {
			logger.debug("Proxying {} failed: {}", exchange.getRequestMethod(), ex.toString());
			respondQuietly(exchange, 502,
					"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"MCP proxy error\"}}");
		}
		finally {
			exchange.close();
		}
	}

	private Upstream lookup(String path) {
		if (path == null || path.length() < 2) {
			return null;
		}
		String[] parts = path.substring(1).split("/", 2);
		if (parts.length != 2) {
			return null;
		}
		Map<String, Upstream> session = routes.get(parts[0]);
		return session == null ? null : session.get(parts[1]);
	}

	private void forward(HttpExchange exchange, Upstream upstream) throws IOException {
		Map<String, List<String>> received = new LinkedHashMap<>();
		exchange.getRequestHeaders().forEach((header, values) -> {
			if (passes(header)) {
				received.put(header, values);
			}
		});
		McpRequest incoming = new McpRequest(upstream.name(), exchange.getRequestMethod(), received,
				exchange.getRequestBody().readAllBytes());
		for (McpRequestFilter filter : filters) {
			switch (filter.filter(incoming)) {
				case McpRequestFilter.Outcome.Forward forward -> incoming = forward.request();
				case McpRequestFilter.Outcome.Answer answer -> {
					answer(exchange, answer);
					return;
				}
			}
		}

		byte[] body = incoming.body();
		String query = exchange.getRequestURI().getRawQuery();
		URI target = query == null ? upstream.url() : URI.create(upstream.url() + "?" + query);

		HttpRequest.Builder request = HttpRequest.newBuilder(target)
			.timeout(requestTimeout)
			.method(incoming.method(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofByteArray(body));
		incoming.headers().forEach((header, values) -> {
			if (passes(header)) {
				values.forEach(value -> request.header(header, value));
			}
		});
		upstream.headers().forEach(request::setHeader);
		upstream.credentials().headers().forEach(request::setHeader);

		HttpResponse<InputStream> response;
		try {
			response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted forwarding to " + upstream.url(), ex);
		}

		boolean credentialed = upstream.credentials() != McpCredentials.NONE;
		response.headers().map().forEach((header, values) -> {
			// On a route whose credentials the proxy holds, a challenge is the proxy's
			// business: an
			// agent that saw one could set off on its own sign-in, on a machine with
			// nobody at it.
			if (passes(header) && !(credentialed && "www-authenticate".equalsIgnoreCase(header))) {
				values.forEach(value -> exchange.getResponseHeaders().add(header, value));
			}
		});
		logger.debug("{} {} -> {}", exchange.getRequestMethod(), upstream.url(), response.statusCode());

		exchange.sendResponseHeaders(response.statusCode(), bodyLength(response));
		try (InputStream from = response.body(); OutputStream to = exchange.getResponseBody()) {
			byte[] chunk = new byte[8192];
			for (int read = from.read(chunk); read >= 0; read = from.read(chunk)) {
				to.write(chunk, 0, read);
				to.flush();
			}
		}
		catch (IOException ex) {
			// The agent hung up mid-body — its business, and not an error worth reporting
			// upward.
			logger.debug("Connection closed while {} was being answered: {}", upstream.url(), ex.getMessage());
		}
	}

	/**
	 * What to tell {@code HttpServer} about the body, in its three-valued spelling:
	 * {@code -1} for none, a positive count for a known length, {@code 0} for chunked.
	 */
	static long bodyLength(HttpResponse<?> response) {
		long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
		if (declared == 0 || response.statusCode() == 202 || response.statusCode() == 204
				|| response.statusCode() == 304) {
			return -1;
		}
		return declared > 0 ? declared : 0;
	}

	/** Whether a header may cross the proxy, in either direction. */
	static boolean passes(String header) {
		return !header.startsWith(":") && !NOT_FORWARDED.contains(header.toLowerCase(Locale.ROOT));
	}

	private static void answer(HttpExchange exchange, McpRequestFilter.Outcome.Answer answer) throws IOException {
		logger.debug("Answered a request locally with HTTP {}", answer.status());
		if (answer.contentType() != null) {
			exchange.getResponseHeaders().set("Content-Type", answer.contentType());
		}
		exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(answer.body());
		}
	}

	private static void respond(HttpExchange exchange, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void respondQuietly(HttpExchange exchange, int status, String json) {
		try {
			respond(exchange, status, json);
		}
		catch (IOException | IllegalStateException ignored) {
			// The response had already started; there is nothing left to say.
		}
	}

	@Override
	public void close() {
		routes.clear();
		server.stop(0);
		workers.shutdownNow();
	}

}
