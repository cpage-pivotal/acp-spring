package org.thought.acp.mcp.oauth;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DefaultMcpOAuth2DcrClientManager;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DynamicClientRegistrationService;
import org.springaicommunity.mcp.security.common.url.DefaultUrlValidator;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.session.SessionPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A terminal application's sign-in: a browser opened on the spot, a loopback redirect, and tokens
 * kept in a private file across restarts.
 *
 * <p>The "browser" here follows the authorization URL with an HTTP client; the fake authorization
 * server signs the user in at once and redirects to the loopback listener, checking the PKCE
 * challenge when the code is redeemed.
 */
class LocalSignInTests {

	@TempDir
	Path home;

	private final FakeMcpAuthorizationServer fake = new FakeMcpAuthorizationServer();

	private final McpServerSpec.Http finops = new McpServerSpec.Http("finops-mcp", fake.mcpUrl(), Map.of());

	private final SessionPrincipal me = new LocalPrincipalResolver().current().orElseThrow();

	private final List<URI> opened = new CopyOnWriteArrayList<>();

	private final List<String> signedIn = new CopyOnWriteArrayList<>();

	@AfterEach
	void close() {
		fake.close();
	}

	/** Follows the sign-in URL as a browser would, landing on the loopback listener. */
	private final AuthorizationPrompt browser = new AuthorizationPrompt() {
		@Override
		public void open(String serverName, URI uri) {
			opened.add(uri);
			Thread.ofVirtual().start(() -> {
				try {
					HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
						.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding());
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});
		}

		@Override
		public void signedIn(String serverName) {
			signedIn.add(serverName);
		}
	};

	private OAuth2McpCredentialsProvider provider(FileMcpOAuthStore store, AuthorizationPrompt prompt, Duration timeout) {
		DefaultUrlValidator urlValidator = new DefaultUrlValidator(true);
		return new OAuth2McpCredentialsProvider(List.of(finops), store.registrations(),
				new DefaultMcpOAuth2DcrClientManager(store.registrations(),
						new DynamicClientRegistrationService(urlValidator), new McpMetadataDiscoveryService(urlValidator),
						urlValidator),
				store.tokens(), McpAuthorizedClientManagers.create(store.registrations(), store.tokens()), "Meridian",
				new LoopbackSignIn(store.registrations(), store.tokens(), prompt, timeout));
	}

	private FileMcpOAuthStore store() {
		return new FileMcpOAuthStore(home.resolve("meridian").resolve("mcp-oauth.json"));
	}

	private String bearer(OAuth2McpCredentialsProvider provider) {
		return provider.credentialsFor(finops, me).orElseThrow().headers().get("Authorization");
	}

	private int callMcp(String bearer) throws Exception {
		return HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(fake.mcpUrl()).header("Authorization", bearer)
				.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding())
			.statusCode();
	}

	@Test
	void theFirstSessionOpensTheBrowserOnceAndThenUsesTheToken() throws Exception {
		OAuth2McpCredentialsProvider provider = provider(store(), browser, Duration.ofSeconds(10));

		String first = bearer(provider);
		String second = bearer(provider);

		assertThat(opened).singleElement().satisfies(uri -> assertThat(uri.toString()).startsWith(fake.issuer() + "/authorize?"));
		Map<String, String> authorization = fake.authorizations.get(0);
		assertThat(authorization).containsEntry("resource", fake.mcpUrl().toString())
			.containsEntry("code_challenge_method", "S256");
		assertThat(authorization.get("redirect_uri")).matches("http://127\\.0\\.0\\.1:\\d+/callback");
		assertThat(fake.tokenRequests.get(0)).containsEntry("grant_type", "authorization_code")
			.containsEntry("resource", fake.mcpUrl().toString());
		assertThat(signedIn).containsExactly("finops-mcp");
		assertThat(first).isEqualTo(second);
		assertThat(callMcp(first)).isEqualTo(200);
	}

	@Test
	void aRestartKeepsTheSignInAndTheRegistrationInAPrivateFile() throws Exception {
		bearer(provider(store(), browser, Duration.ofSeconds(10)));

		FileMcpOAuthStore reopened = store();
		String afterRestart = bearer(provider(reopened, browser, Duration.ofSeconds(10)));

		assertThat(opened).hasSize(1);
		assertThat(fake.registrations).hasSize(1);
		assertThat(callMcp(afterRestart)).isEqualTo(200);
		assertThat(reopened.permissions()).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		assertThat(reopened.location()).isEqualTo(home.resolve("meridian").resolve("mcp-oauth.json"));
	}

	@Test
	void aRegisteredPortSomethingElseHasTakenMeansRegisteringAgainOnce() throws Exception {
		FileMcpOAuthStore store = store();
		OAuth2McpCredentialsProvider provider = provider(store, browser, Duration.ofSeconds(10));
		bearer(provider);
		int registeredPort = URI.create(store.registrations().findByRegistrationId("finops-mcp").getRedirectUri()).getPort();
		store.tokens().removeAuthorizedClient("finops-mcp", me.name());

		try (ServerSocket squatter = new ServerSocket(registeredPort, 0, InetAddress.getLoopbackAddress())) {
			String bearer = bearer(provider);

			assertThat(fake.registrations).hasSize(2);
			assertThat(URI.create(store.registrations().findByRegistrationId("finops-mcp").getRedirectUri()).getPort())
				.isNotEqualTo(registeredPort);
			assertThat(callMcp(bearer)).isEqualTo(200);
		}
	}

	@Test
	void aSignInTheAuthorizationServerRefusesSaysSo() {
		fake.refuseAuthorization("access_denied");

		assertThatThrownBy(() -> bearer(provider(store(), browser, Duration.ofSeconds(10))))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("refused").hasMessageContaining("access_denied");
		assertThat(fake.tokenRequests).isEmpty();
	}

	@Test
	void aSignInNobodyFinishesGivesUpAndFreesThePort() throws Exception {
		FileMcpOAuthStore store = store();
		AuthorizationPrompt ignored = (server, uri) -> opened.add(uri);

		assertThatThrownBy(() -> bearer(provider(store, ignored, Duration.ofMillis(300))))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("Gave up waiting");

		int port = URI.create(store.registrations().findByRegistrationId("finops-mcp").getRedirectUri()).getPort();
		try (ServerSocket rebound = new ServerSocket(port, 0, InetAddress.getLoopbackAddress())) {
			assertThat(rebound.isBound()).isTrue();
		}
	}

	@Test
	void theDefaultFileIsUnderTheUsersConfigDirectoryNamedForTheApplication() {
		assertThat(FileMcpOAuthStore.defaultLocation("meridian").toString()).endsWith("/meridian/acp-mcp-oauth.json")
			.doesNotStartWith(Path.of("").toAbsolutePath().toString());
	}
}
