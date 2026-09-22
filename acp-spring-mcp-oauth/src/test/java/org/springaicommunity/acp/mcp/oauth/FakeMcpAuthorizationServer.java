package org.springaicommunity.acp.mcp.oauth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An MCP server and its authorization server, behaving as the Tanzu MCP gateway was measured to.
 *
 * <p>The MCP endpoint answers an unauthenticated request with a 401 naming its protected-resource
 * metadata; the authorization server registers clients dynamically, requires PKCE, and rotates
 * refresh tokens — a refresh token works once, which is what makes a refresh race visible. Every
 * registration and token request is recorded, form fields and all, for the tests to inspect.
 */
final class FakeMcpAuthorizationServer implements AutoCloseable {

	private static final ObjectMapper JSON = new ObjectMapper();

	final List<Map<String, Object>> registrations = new CopyOnWriteArrayList<>();

	final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

	private final Set<String> validAccessTokens = ConcurrentHashMap.newKeySet();

	private final Set<String> validRefreshTokens = ConcurrentHashMap.newKeySet();

	/** Authorization codes handed out by {@link #code}, redeemable once. */
	private final Set<String> validCodes = ConcurrentHashMap.newKeySet();

	/** The PKCE challenge each code from {@code /authorize} was issued against. */
	private final Map<String, String> challenges = new ConcurrentHashMap<>();

	final List<Map<String, String>> authorizations = new CopyOnWriteArrayList<>();

	/** What {@code /authorize} answers with, as though the user had just done it. */
	private volatile String authorizeError;

	private final AtomicInteger counter = new AtomicInteger();

	private final HttpServer server;

	private volatile Duration tokenDelay = Duration.ZERO;

	FakeMcpAuthorizationServer() {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		server.createContext("/mcp", this::mcp);
		server.createContext("/.well-known/oauth-protected-resource/mcp",
				exchange -> json(exchange, 200, Map.of("resource", mcpUrl().toString(), "authorization_servers",
						List.of(issuer()), "scopes_supported", List.of())));
		server.createContext("/.well-known/oauth-authorization-server/as", exchange -> json(exchange, 200,
				Map.of("issuer", issuer(), "authorization_endpoint", issuer() + "/authorize", "token_endpoint",
						issuer() + "/token", "registration_endpoint", issuer() + "/register", "response_types_supported",
						List.of("code"), "grant_types_supported", List.of("authorization_code", "refresh_token"),
						"code_challenge_methods_supported", List.of("S256"), "token_endpoint_auth_methods_supported",
						List.of("none"))));
		server.createContext("/as/register", this::register);
		server.createContext("/as/token", this::token);
		server.createContext("/as/authorize", this::authorize);
		server.createContext("/", exchange -> json(exchange, 404, Map.of("error", "not_found")));
		server.start();
	}

	URI mcpUrl() {
		return URI.create(base() + "/mcp");
	}

	String issuer() {
		return base() + "/as";
	}

	private String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** An authorization code, as the authorize endpoint would have redirected back with. */
	String code() {
		String code = "code-" + counter.incrementAndGet();
		validCodes.add(code);
		return code;
	}

	/** Tokens as a finished sign-in would have left them. */
	String[] issueTokens() {
		int n = counter.incrementAndGet();
		String access = "access-" + n;
		String refresh = "refresh-" + n;
		validAccessTokens.add(access);
		validRefreshTokens.add(refresh);
		return new String[] { access, refresh };
	}

	/** Makes {@code /authorize} redirect back with this error instead of a code. */
	void refuseAuthorization(String error) {
		this.authorizeError = error;
	}

	/** Revokes every refresh token, as a server does when a user's grant is withdrawn. */
	void revokeRefreshTokens() {
		validRefreshTokens.clear();
	}

	/** Slows the token endpoint, to widen the window two refreshes would race in. */
	void delayTokens(Duration delay) {
		this.tokenDelay = delay;
	}

	long refreshCount() {
		return tokenRequests.stream().filter(r -> "refresh_token".equals(r.get("grant_type"))).count();
	}

	private void mcp(HttpExchange exchange) throws IOException {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization != null && validAccessTokens.contains(authorization.replace("Bearer ", ""))) {
			json(exchange, 200, Map.of("jsonrpc", "2.0", "id", 1, "result", Map.of()));
			return;
		}
		exchange.getResponseHeaders().add("WWW-Authenticate",
				"Bearer resource_metadata=\"" + base() + "/.well-known/oauth-protected-resource/mcp\"");
		exchange.sendResponseHeaders(401, -1);
		exchange.close();
	}

	/** The user signs in instantly and is redirected back, with a code bound to the PKCE challenge. */
	private void authorize(HttpExchange exchange) throws IOException {
		Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
		authorizations.add(query);
		String location;
		if (authorizeError != null) {
			location = query.get("redirect_uri") + "?error=" + authorizeError + "&state=" + query.get("state");
		}
		else {
			String code = code();
			challenges.put(code, query.getOrDefault("code_challenge", ""));
			location = query.get("redirect_uri") + "?code=" + code + "&state="
					+ java.net.URLEncoder.encode(query.get("state"), StandardCharsets.UTF_8);
		}
		exchange.getResponseHeaders().add("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	private boolean verifierMatches(String code, String verifier) {
		String challenge = challenges.remove(code);
		if (challenge == null) {
			return verifier != null;
		}
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
				.digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return challenge.equals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	private void register(HttpExchange exchange) throws IOException {
		Map<String, Object> request = JSON.readValue(exchange.getRequestBody(), Map.class);
		registrations.add(request);
		Map<String, Object> response = new LinkedHashMap<>(request);
		response.put("client_id", "client-" + registrations.size());
		json(exchange, 201, response);
	}

	private void token(HttpExchange exchange) throws IOException {
		Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		tokenRequests.add(form);
		sleep(tokenDelay);
		boolean granted = switch (form.getOrDefault("grant_type", "")) {
			case "authorization_code" -> validCodes.remove(form.get("code")) && form.containsKey("code_verifier")
					&& verifierMatches(form.get("code"), form.get("code_verifier"));
			case "refresh_token" -> validRefreshTokens.remove(form.get("refresh_token"));
			default -> false;
		};
		if (!granted) {
			json(exchange, 400, Map.of("error", "invalid_grant"));
			return;
		}
		String[] tokens = issueTokens();
		json(exchange, 200, Map.of("access_token", tokens[0], "refresh_token", tokens[1], "token_type", "bearer",
				"expires_in", 3600, "scope", "openid"));
	}

	private static Map<String, String> form(String body) {
		Map<String, String> fields = new LinkedHashMap<>();
		if (body == null) {
			return fields;
		}
		for (String pair : body.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return fields;
	}

	private static void json(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = JSON.writeValueAsBytes(body);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void sleep(Duration delay) {
		if (delay.isZero()) {
			return;
		}
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
