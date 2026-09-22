package org.thought.acp.mcp.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code spring.acp.mcp.oauth.*}: how this application registers with MCP servers' authorization
 * servers, and where it keeps what it learns. Which servers are protected is said on the server
 * itself, with {@code spring.acp.mcp-servers[n].auth: oauth}.
 */
@ConfigurationProperties("spring.acp.mcp.oauth")
public class McpOAuthProperties {

	/** Shown to users on the authorization server's consent screen. Defaults to spring.application.name. */
	private String clientName;

	/**
	 * Where users' browsers reach this application, for the redirect URI registered with each
	 * authorization server. Read from the request that triggers the registration when unset.
	 */
	private String baseUrl;

	/** The callback path registered with each authorization server. */
	private String redirectUri = "{baseUrl}/login/oauth2/code/{registrationId}";

	/** Where client registrations and users' tokens are kept. */
	private Store store = Store.MEMORY;

	/** Allow plain http and loopback addresses for discovered OAuth endpoints. For local development only. */
	private boolean allowLoopback;

	public enum Store {

		/** Lost on restart: every user signs in again, and the application registers again. */
		MEMORY,

		/**
		 * The application's database: shared by every instance and kept across restarts. Needs the
		 * {@code acp_mcp_client_registration} and {@code oauth2_authorized_client} tables.
		 */
		JDBC

	}

	public String getClientName() {
		return clientName;
	}

	public void setClientName(String clientName) {
		this.clientName = clientName;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getRedirectUri() {
		return redirectUri;
	}

	public void setRedirectUri(String redirectUri) {
		this.redirectUri = redirectUri;
	}

	public Store getStore() {
		return store;
	}

	public void setStore(Store store) {
		this.store = store;
	}

	public boolean isAllowLoopback() {
		return allowLoopback;
	}

	public void setAllowLoopback(boolean allowLoopback) {
		this.allowLoopback = allowLoopback;
	}
}
