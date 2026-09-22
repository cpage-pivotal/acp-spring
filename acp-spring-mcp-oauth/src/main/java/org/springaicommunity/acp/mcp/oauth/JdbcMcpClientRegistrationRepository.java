package org.springaicommunity.acp.mcp.oauth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;

/**
 * Client registrations that survive a restart and are shared by every instance of the application.
 *
 * <p>Needed because dynamic client registration is a write on the authorization server: an
 * application that forgot its registrations on every restart would register again each time and
 * leave a trail of dead client ids behind, and two instances that each registered their own would
 * each hold tokens the other's client id cannot refresh.
 *
 * <p>Registrations are read on every token refresh, so they are cached here after the first read.
 * They change only when this application registers or re-scopes a client, which goes through this
 * class and updates the cache as it writes; another instance's scope step-up reaches this one's
 * cache only on restart, which matters only for servers that advertise scopes. The table is {@code acp_mcp_client_registration}; see
 * {@code org/springaicommunity/acp/mcp/oauth/acp-mcp-oauth-schema.sql}.
 *
 * <p>A public client — what this module registers — has no secret. Anything else stored here
 * (client ids, endpoint URLs, the resource) is not a credential.
 */
public final class JdbcMcpClientRegistrationRepository implements McpClientRegistrationRepository {

	private static final String COLUMNS = "registration_id, resource_id, client_id, client_secret, "
			+ "client_authentication_method, authorization_grant_type, redirect_uri, scopes, client_name, "
			+ "authorization_uri, token_uri, issuer_uri, jwk_set_uri";

	private final JdbcOperations jdbc;

	private final Map<String, Row> cache = new ConcurrentHashMap<>();

	private record Row(ClientRegistration registration, String resourceId) {
	}

	public JdbcMcpClientRegistrationRepository(JdbcOperations jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public ClientRegistration findByRegistrationId(String registrationId) {
		Row row = find(registrationId);
		return row == null ? null : row.registration();
	}

	@Override
	public String findResourceIdByRegistrationId(String registrationId) {
		Row row = find(registrationId);
		return row == null ? null : row.resourceId();
	}

	/**
	 * Keeps the first registration made for a server, across every instance.
	 *
	 * <p>Two instances meeting a server for the first time at once will both register with its
	 * authorization server, and only one client id can win: a user who signed in through one
	 * instance must be refreshable from the other. So this inserts, and if another instance got there
	 * first, adopts that row and drops its own — the losing client id is left unused at the
	 * authorization server, which is the cheaper failure.
	 */
	@Override
	public void addClientRegistration(ClientRegistration registration, String resourceId) {
		String id = registration.getRegistrationId();
		try {
			jdbc.update("INSERT INTO acp_mcp_client_registration (" + COLUMNS
					+ ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", values(registration, resourceId, true));
			cache.put(id, new Row(registration, resourceId));
		}
		catch (DuplicateKeyException ex) {
			cache.remove(id);
			find(id);
		}
	}

	/** Changes a registration in place: scope step-up, which must reach every instance. */
	@Override
	public void updateClientRegistration(String registrationId, Consumer<ClientRegistration.Builder> update) {
		cache.remove(registrationId);
		Row row = find(registrationId);
		if (row == null) {
			return;
		}
		ClientRegistration.Builder builder = ClientRegistration.withClientRegistration(row.registration());
		update.accept(builder);
		ClientRegistration updated = builder.build();
		jdbc.update("UPDATE acp_mcp_client_registration SET resource_id = ?, client_id = ?, client_secret = ?, "
				+ "client_authentication_method = ?, authorization_grant_type = ?, redirect_uri = ?, scopes = ?, "
				+ "client_name = ?, authorization_uri = ?, token_uri = ?, issuer_uri = ?, jwk_set_uri = ? "
				+ "WHERE registration_id = ?", values(updated, row.resourceId(), false));
		cache.put(registrationId, new Row(updated, row.resourceId()));
	}

	private Row find(String registrationId) {
		Row cached = cache.get(registrationId);
		if (cached != null) {
			return cached;
		}
		List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM acp_mcp_client_registration WHERE registration_id = ?",
				JdbcMcpClientRegistrationRepository::row, registrationId);
		if (rows.isEmpty()) {
			return null;
		}
		cache.put(registrationId, rows.get(0));
		return rows.get(0);
	}

	private static Object[] values(ClientRegistration registration, String resourceId, boolean idFirst) {
		ClientRegistration.ProviderDetails provider = registration.getProviderDetails();
		Object[] fields = { resourceId, registration.getClientId(), emptyToNull(registration.getClientSecret()),
				registration.getClientAuthenticationMethod().getValue(), registration.getAuthorizationGrantType().getValue(),
				registration.getRedirectUri(), StringUtils.collectionToDelimitedString(registration.getScopes(), " "),
				registration.getClientName(), provider.getAuthorizationUri(), provider.getTokenUri(),
				provider.getIssuerUri(), provider.getJwkSetUri() };
		Object[] all = new Object[fields.length + 1];
		if (idFirst) {
			all[0] = registration.getRegistrationId();
			System.arraycopy(fields, 0, all, 1, fields.length);
		}
		else {
			System.arraycopy(fields, 0, all, 0, fields.length);
			all[fields.length] = registration.getRegistrationId();
		}
		return all;
	}

	private static Row row(ResultSet rs, int rowNum) throws SQLException {
		String scopes = rs.getString("scopes");
		ClientRegistration registration = ClientRegistration.withRegistrationId(rs.getString("registration_id"))
			.clientId(rs.getString("client_id"))
			.clientSecret(rs.getString("client_secret"))
			.clientAuthenticationMethod(new ClientAuthenticationMethod(rs.getString("client_authentication_method")))
			.authorizationGrantType(new AuthorizationGrantType(rs.getString("authorization_grant_type")))
			.redirectUri(rs.getString("redirect_uri"))
			.scope(StringUtils.hasText(scopes) ? StringUtils.delimitedListToStringArray(scopes, " ") : new String[0])
			.clientName(rs.getString("client_name"))
			.authorizationUri(rs.getString("authorization_uri"))
			.tokenUri(rs.getString("token_uri"))
			.issuerUri(rs.getString("issuer_uri"))
			.jwkSetUri(rs.getString("jwk_set_uri"))
			.build();
		return new Row(registration, rs.getString("resource_id"));
	}

	private static String emptyToNull(String value) {
		return StringUtils.hasText(value) ? value : null;
	}
}
