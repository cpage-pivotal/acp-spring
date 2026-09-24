package org.springaicommunity.acp.mcp.oauth;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Client registrations and tokens in one file, for a terminal application run by one
 * person.
 *
 * <p>
 * Under the user's config directory ({@code $XDG_CONFIG_HOME}, else {@code ~/.config})
 * rather than the project, and mode 600: these are this person's credentials for a shared
 * gateway, and a token that lands in the working directory eventually lands in a commit.
 * Written whole, to a temporary file moved into place, so a crash mid-write leaves the
 * previous version rather than half of one. A file that cannot be read is treated as
 * empty — a reason to sign in again, not to fail.
 *
 * <p>
 * Registrations are kept for the same reason tokens are: dynamic registration is a write
 * on the authorization server, and registering on every start leaves a trail of dead
 * client ids.
 */
public final class FileMcpOAuthStore {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final Path file;

	private final Registrations registrations = new Registrations();

	private final Tokens tokens = new Tokens();

	public FileMcpOAuthStore(Path file) {
		this.file = file.toAbsolutePath();
	}

	/** {@code <config dir>/<application>/acp-mcp-oauth.json}. */
	public static Path defaultLocation(String application) {
		String xdg = System.getenv("XDG_CONFIG_HOME");
		Path config = xdg == null || xdg.isBlank() ? Paths.get(System.getProperty("user.home"), ".config")
				: Paths.get(xdg);
		return config.resolve(application).resolve("acp-mcp-oauth.json");
	}

	public Path location() {
		return file;
	}

	/**
	 * The registration half, which is also the application's
	 * {@code ClientRegistrationRepository}.
	 */
	public Registrations registrations() {
		return registrations;
	}

	/** The token half. */
	public OAuth2AuthorizedClientService tokens() {
		return tokens;
	}

	/** Registrations: {@code registrations.<id>}. */
	public final class Registrations implements McpClientRegistrationRepository {

		@Override
		public ClientRegistration findByRegistrationId(String registrationId) {
			JsonNode node = read().path("registrations").path(registrationId);
			return node.isObject() ? registration(registrationId, node) : null;
		}

		@Override
		public String findResourceIdByRegistrationId(String registrationId) {
			return text(read().path("registrations").path(registrationId), "resource_id");
		}

		@Override
		public void addClientRegistration(ClientRegistration registration, String resourceId) {
			update(root -> root.withObjectProperty("registrations")
				.set(registration.getRegistrationId(), write(registration, resourceId)));
		}

		@Override
		public void updateClientRegistration(String registrationId, Consumer<ClientRegistration.Builder> update) {
			ClientRegistration existing = findByRegistrationId(registrationId);
			if (existing == null) {
				return;
			}
			String resource = findResourceIdByRegistrationId(registrationId);
			ClientRegistration.Builder builder = ClientRegistration.withClientRegistration(existing);
			update.accept(builder);
			addClientRegistration(builder.build(), resource);
		}

		/**
		 * Forgets a registration, and every token issued under it — a token is only
		 * refreshable by the client id it was issued to.
		 */
		public void remove(String registrationId) {
			update(root -> {
				root.withObjectProperty("registrations").remove(registrationId);
				root.withObjectProperty("tokens").remove(registrationId);
			});
		}

	}

	/** Tokens: {@code tokens.<registration id>.<principal name>}. */
	private final class Tokens implements OAuth2AuthorizedClientService {

		@Override
		@SuppressWarnings("unchecked")
		public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId, String principalName) {
			JsonNode node = read().path("tokens").path(registrationId).path(principalName);
			ClientRegistration registration = registrations.findByRegistrationId(registrationId);
			if (!node.isObject() || registration == null || text(node, "access_token") == null) {
				return null;
			}
			OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
					text(node, "access_token"), instant(node, "issued_at"), instant(node, "expires_at"),
					scopes(text(node, "scopes")));
			String refresh = text(node, "refresh_token");
			return (T) new OAuth2AuthorizedClient(registration, principalName, access,
					refresh == null ? null : new OAuth2RefreshToken(refresh, instant(node, "refresh_issued_at")));
		}

		@Override
		public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal) {
			ObjectNode node = JSON.createObjectNode();
			OAuth2AccessToken access = client.getAccessToken();
			node.put("access_token", access.getTokenValue());
			node.put("issued_at", string(access.getIssuedAt()));
			node.put("expires_at", string(access.getExpiresAt()));
			node.put("scopes", String.join(" ", access.getScopes()));
			if (client.getRefreshToken() != null) {
				node.put("refresh_token", client.getRefreshToken().getTokenValue());
				node.put("refresh_issued_at", string(client.getRefreshToken().getIssuedAt()));
			}
			update(root -> root.withObjectProperty("tokens")
				.withObjectProperty(client.getClientRegistration().getRegistrationId())
				.set(principal.getName(), node));
		}

		@Override
		public void removeAuthorizedClient(String registrationId, String principalName) {
			update(root -> root.withObjectProperty("tokens").withObjectProperty(registrationId).remove(principalName));
		}

	}

	private synchronized ObjectNode read() {
		try {
			if (!Files.exists(file)) {
				return JSON.createObjectNode();
			}
			JsonNode node = JSON.readTree(file.toFile());
			return node instanceof ObjectNode object ? object : JSON.createObjectNode();
		}
		catch (IOException ex) {
			return JSON.createObjectNode();
		}
	}

	private synchronized void update(Consumer<ObjectNode> change) {
		ObjectNode root = read();
		change.accept(root);
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), ".mcp-oauth", ".tmp");
			ownerOnly(temporary);
			Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
			ownerOnly(file);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not write the MCP sign-in state at " + file, ex);
		}
	}

	private static void ownerOnly(Path path) {
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
		}
		catch (IOException | UnsupportedOperationException ex) {
			// Not a POSIX filesystem: the token is no less valid, the file just less
			// private.
		}
	}

	/**
	 * Exposed for tests: the permissions the file ended up with, where the platform has
	 * them.
	 */
	Set<PosixFilePermission> permissions() throws IOException {
		return Files.getPosixFilePermissions(file);
	}

	private static ObjectNode write(ClientRegistration registration, String resourceId) {
		ClientRegistration.ProviderDetails provider = registration.getProviderDetails();
		ObjectNode node = JSON.createObjectNode();
		node.put("resource_id", resourceId);
		node.put("client_id", registration.getClientId());
		node.put("client_secret",
				StringUtils.hasText(registration.getClientSecret()) ? registration.getClientSecret() : null);
		node.put("client_authentication_method", registration.getClientAuthenticationMethod().getValue());
		node.put("authorization_grant_type", registration.getAuthorizationGrantType().getValue());
		node.put("redirect_uri", registration.getRedirectUri());
		node.put("scopes", String.join(" ", registration.getScopes()));
		node.put("client_name", registration.getClientName());
		node.put("authorization_uri", provider.getAuthorizationUri());
		node.put("token_uri", provider.getTokenUri());
		node.put("issuer_uri", provider.getIssuerUri());
		node.put("jwk_set_uri", provider.getJwkSetUri());
		return node;
	}

	private static ClientRegistration registration(String id, JsonNode node) {
		return ClientRegistration.withRegistrationId(id)
			.clientId(text(node, "client_id"))
			.clientSecret(text(node, "client_secret"))
			.clientAuthenticationMethod(new ClientAuthenticationMethod(text(node, "client_authentication_method")))
			.authorizationGrantType(new AuthorizationGrantType(text(node, "authorization_grant_type")))
			.redirectUri(text(node, "redirect_uri"))
			.scope(scopes(text(node, "scopes")))
			.clientName(text(node, "client_name"))
			.authorizationUri(text(node, "authorization_uri"))
			.tokenUri(text(node, "token_uri"))
			.issuerUri(text(node, "issuer_uri"))
			.jwkSetUri(text(node, "jwk_set_uri"))
			.build();
	}

	private static Set<String> scopes(String value) {
		return StringUtils.hasText(value) ? new LinkedHashSet<>(Arrays.asList(value.split(" "))) : Set.of();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static Instant instant(JsonNode node, String field) {
		String value = text(node, field);
		return value == null ? null : Instant.parse(value);
	}

	private static String string(Instant instant) {
		return instant == null ? null : instant.toString();
	}

}
