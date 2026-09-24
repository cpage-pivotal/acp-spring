package org.springaicommunity.acp.mcp.oauth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springaicommunity.acp.config.McpServerSpec;
import org.springaicommunity.acp.session.SessionPrincipal;

import com.sun.net.httpserver.HttpServer;

/**
 * The browser sign-in, run on the spot, for a terminal application.
 *
 * <p>
 * RFC 8252's native-app flow: authorization code with PKCE, redirected to a loopback
 * listener on {@code 127.0.0.1}. The pieces are Spring Security's — the authorization
 * request with its PKCE customizer, and the token client that redeems the code — with
 * {@code resource=} on both, as the MCP authorization spec requires. Verified against the
 * Tanzu MCP gateway with exactly these pieces.
 *
 * <p>
 * The listener is the part to be careful with. Its port is part of the redirect URI
 * registered with the authorization server, so it is chosen once, at registration, and
 * reused; it binds only the loopback address, so nothing off this machine can reach it;
 * it is closed the moment the code arrives or the wait gives up; and the {@code state} it
 * receives must be the one it sent. When the registered port has since been taken by
 * something else, the registration is forgotten and {@link McpSignIn.StaleRegistration}
 * tells the provider to make a new one.
 */
public final class LoopbackSignIn implements McpSignIn {

	private static final String CALLBACK = "/callback";

	private final FileMcpOAuthStore.Registrations registrations;

	private final OAuth2AuthorizedClientService authorizedClients;

	private final AuthorizationPrompt prompt;

	private final Duration timeout;

	public LoopbackSignIn(FileMcpOAuthStore.Registrations registrations,
			OAuth2AuthorizedClientService authorizedClients, AuthorizationPrompt prompt, Duration timeout) {
		this.registrations = registrations;
		this.authorizedClients = authorizedClients;
		this.prompt = prompt;
		this.timeout = timeout;
	}

	/** A loopback port the operating system has just confirmed is free. */
	@Override
	public String redirectUri(McpServerSpec.Http server) {
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			return "http://127.0.0.1:" + socket.getLocalPort() + CALLBACK;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not find a loopback port for the OAuth redirect", ex);
		}
	}

	@Override
	public void signIn(McpServerSpec.Http server, SessionPrincipal principal) {
		ClientRegistration registration = registrations.findByRegistrationId(server.name());
		String resource = registrations.findResourceIdByRegistrationId(server.name());
		URI redirect = URI.create(registration.getRedirectUri());

		CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
		HttpServer listener = listen(server, redirect, callback);
		try {
			OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri(registration.getProviderDetails().getAuthorizationUri())
				.clientId(registration.getClientId())
				.redirectUri(registration.getRedirectUri())
				.scopes(registration.getScopes())
				.state(java.util.UUID.randomUUID().toString())
				.attributes(attributes -> attributes.put("registration_id", server.name()));
			if (resource != null) {
				builder.additionalParameters(Map.of("resource", resource));
			}
			OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
			OAuth2AuthorizationRequest request = builder.build();

			prompt.open(server.name(), URI.create(request.getAuthorizationRequestUri()));
			Map<String, String> answer = await(callback, server);
			if (answer.containsKey("error")) {
				throw new IllegalStateException("Signing in to MCP server '" + server.name() + "' was refused: "
						+ answer.get("error") + " " + answer.getOrDefault("error_description", ""));
			}
			if (!request.getState().equals(answer.get("state"))) {
				throw new IllegalStateException("The sign-in redirect for MCP server '" + server.name()
						+ "' carried the wrong state; ignoring it");
			}
			OAuth2AccessTokenResponse tokens = exchange(registration, resource, request, answer.get("code"));
			authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, principal.name(),
					tokens.getAccessToken(), tokens.getRefreshToken()),
					new TestingAuthenticationToken(principal.name(), null));
			prompt.signedIn(server.name());
		}
		finally {
			listener.stop(0);
		}
	}

	private HttpServer listen(McpServerSpec.Http server, URI redirect,
			CompletableFuture<Map<String, String>> callback) {
		try {
			HttpServer listener = HttpServer
				.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), redirect.getPort()), 0);
			listener.createContext(redirect.getPath(), exchange -> {
				try (exchange) {
					Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
					byte[] page = (params.containsKey("error") ? FAILED : SIGNED_IN).getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
					exchange.sendResponseHeaders(200, page.length);
					try (OutputStream out = exchange.getResponseBody()) {
						out.write(page);
					}
					callback.complete(params);
				}
			});
			listener.start();
			return listener;
		}
		catch (BindException ex) {
			registrations.remove(server.name());
			throw new StaleRegistration("its loopback redirect port " + redirect.getPort() + " is in use", ex);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not listen for the OAuth redirect on " + redirect, ex);
		}
	}

	private Map<String, String> await(CompletableFuture<Map<String, String>> callback, McpServerSpec.Http server) {
		try {
			return callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			throw new IllegalStateException("Gave up waiting for the sign-in to MCP server '" + server.name()
					+ "' after " + timeout.toSeconds() + "s", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted waiting for the sign-in to MCP server '" + server.name() + "'",
					ex);
		}
		catch (ExecutionException ex) {
			throw new IllegalStateException("The sign-in redirect failed", ex.getCause());
		}
	}

	private static OAuth2AccessTokenResponse exchange(ClientRegistration registration, String resource,
			OAuth2AuthorizationRequest request, String code) {
		RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
		if (resource != null) {
			client.addParametersConverter(grant -> {
				LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
				parameters.add("resource", resource);
				return parameters;
			});
		}
		OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
			.redirectUri(request.getRedirectUri())
			.state(request.getState())
			.build();
		return client.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
				new OAuth2AuthorizationExchange(request, response)));
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> params = new HashMap<>();
		if (raw == null) {
			return params;
		}
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			params.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
					eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return params;
	}

	private static final String SIGNED_IN = """
			<!doctype html><meta charset="utf-8"><title>Signed in</title>
			<body style="font:16px system-ui;margin:4rem"><h1>Signed in</h1>
			<p>You can close this tab and go back to the terminal.</p>
			""";

	private static final String FAILED = """
			<!doctype html><meta charset="utf-8"><title>Sign-in failed</title>
			<body style="font:16px system-ui;margin:4rem"><h1>Sign-in failed</h1>
			<p>The terminal says why.</p>
			""";

}
