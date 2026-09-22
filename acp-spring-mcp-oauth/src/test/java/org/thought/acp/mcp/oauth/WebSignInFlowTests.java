package org.thought.acp.mcp.oauth;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.security.client.sync.config.McpClientOAuth2Configurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thought.acp.client.AgentClient;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.mcp.McpAccess;
import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentRuntime;
import org.thought.acp.session.SessionPrincipal;
import org.thought.acp.session.SessionPrincipalResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole web flow, as a multi-user application would see it: a signed-in user's first request
 * that needs an MCP server is redirected to that server's authorization server, the callback stores
 * their token, and the loopback proxy then calls the server with it.
 *
 * <p>Only the application's side is real — Spring Boot, Spring Security, mcp-security's configurer
 * and this module. The authorization server is the fake, and the user's trip through it is the one
 * line that hands back a code.
 */
@SpringBootTest(classes = WebSignInFlowTests.App.class)
@AutoConfigureMockMvc
class WebSignInFlowTests {

	static final FakeMcpAuthorizationServer fake = new FakeMcpAuthorizationServer();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws IOException {
		Path workspace = Files.createTempDirectory("acp-mcp-oauth");
		registry.add("spring.acp.runtime", () -> "test");
		registry.add("spring.acp.workspace", workspace::toString);
		registry.add("spring.acp.mcp-servers[0].name", () -> "finops-mcp");
		registry.add("spring.acp.mcp-servers[0].url", () -> fake.mcpUrl().toString());
		registry.add("spring.acp.mcp-servers[0].auth", () -> "oauth");
		registry.add("spring.acp.mcp.oauth.client-name", () -> "Capacity Agent");
		registry.add("spring.acp.mcp.oauth.allow-loopback", () -> "true");
	}

	@AfterAll
	static void stop() {
		fake.close();
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	OAuth2AuthorizedClientService authorizedClients;

	@Autowired
	AgentSettings settings;

	private static Map<String, String> query(String url) {
		Map<String, String> params = new LinkedHashMap<>();
		for (String pair : URI.create(url).getRawQuery().split("&")) {
			int eq = pair.indexOf('=');
			params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return params;
	}

	@Test
	void aUsersFirstRequestSendsThemToSignInAndTheProxyUsesTheirTokenAfterwards() throws Exception {
		MockHttpSession browser = new MockHttpSession();

		// 1. Alice asks for something that needs the MCP server, and has never signed in to it.
		MvcResult first = mvc.perform(get("/connect").with(user("alice")).session(browser))
			.andExpect(status().is3xxRedirection()).andReturn();
		String authorize = first.getResponse().getRedirectedUrl();

		// 2. She is sent to its authorization server, as the MCP authorization spec asks.
		assertThat(authorize).startsWith(fake.issuer() + "/authorize?");
		Map<String, String> request = query(authorize);
		assertThat(request).containsEntry("response_type", "code").containsEntry("client_id", "client-1")
			.containsEntry("resource", fake.mcpUrl().toString())
			.containsEntry("code_challenge_method", "S256")
			.containsEntry("redirect_uri", "http://localhost/login/oauth2/code/finops-mcp")
			.containsKey("code_challenge").containsKey("state");
		assertThat(fake.registrations).singleElement()
			.satisfies(registration -> assertThat(registration.get("client_name")).isEqualTo("Capacity Agent"));

		// 3. She signs in there and comes back with a code, which is exchanged for her tokens.
		MvcResult callback = mvc.perform(get("/login/oauth2/code/finops-mcp").param("code", fake.code())
			.param("state", request.get("state")).with(user("alice")).session(browser))
			.andExpect(status().is3xxRedirection()).andReturn();
		// Back to where she was going; Spring Security marks a replayed saved request with ?continue.
		assertThat(URI.create(callback.getResponse().getRedirectedUrl()).getPath()).isEqualTo("/connect");
		Map<String, String> exchange = fake.tokenRequests.get(fake.tokenRequests.size() - 1);
		assertThat(exchange).containsEntry("grant_type", "authorization_code")
			.containsEntry("resource", fake.mcpUrl().toString()).containsKey("code_verifier");
		assertThat(authorizedClients.<org.springframework.security.oauth2.client.OAuth2AuthorizedClient>loadAuthorizedClient(
				"finops-mcp", "alice")).isNotNull();

		// 4. Now the request goes through, and Bob — who has not signed in — still would not.
		mvc.perform(get("/connect").with(user("alice")).session(browser)).andExpect(status().isOk())
			.andExpect(content().string("connected"));
		mvc.perform(get("/connect").with(user("bob")).session(new MockHttpSession()))
			.andExpect(status().is3xxRedirection());

		// 5. And the proxy calls the MCP server with Alice's token, which the server accepts.
		try (McpAccess access = new McpAccess(settings.mcp().credentials(), Duration.ofSeconds(10))) {
			McpServerSpec.Http proxied = (McpServerSpec.Http) access
				.grant(SessionPrincipal.of("alice"), settings.mcpServers()).servers().get(0);
			HttpResponse<String> response = HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(proxied.url()).POST(HttpRequest.BodyPublishers.ofString(
							"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(proxied.url().getHost()).isEqualTo("127.0.0.1");
			assertThat(response.statusCode()).isEqualTo(200);
		}
	}

	@Test
	void theSettingsCarryTheProviderAndTheSecurityContextResolver() {
		assertThat(settings.mcp().credentials()).isInstanceOf(OAuth2McpCredentialsProvider.class);
		assertThat(settings.mcp().principals()).isInstanceOf(SecurityContextPrincipalResolver.class);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(Connect.class)
	static class App {

		@Bean
		SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.with(McpClientOAuth2Configurer.mcpClientOAuth2(), mcp -> mcp.cimd(false))
				.build();
		}

		/** No agent is started: the flow under test ends at the proxy. */
		@Bean
		AgentRuntime testRuntime() {
			return new AgentRuntime() {
				@Override
				public String id() {
					return "test";
				}

				@Override
				public AgentLaunchSpec launch(AgentSettings settings) {
					throw new UnsupportedOperationException("no agent in this test");
				}
			};
		}

		@Bean
		AgentClient acpAgentClient() {
			return org.mockito.Mockito.mock(AgentClient.class);
		}
	}

	/** What an application's own endpoint does before it opens a session or starts streaming. */
	@RestController
	static class Connect {

		private final OAuth2McpCredentialsProvider provider;

		private final SessionPrincipalResolver principals;

		Connect(OAuth2McpCredentialsProvider provider, SessionPrincipalResolver principals) {
			this.provider = provider;
			this.principals = principals;
		}

		@GetMapping("/connect")
		String connect() {
			provider.requireAuthorized(principals.current().orElse(null));
			return "connected";
		}
	}
}
