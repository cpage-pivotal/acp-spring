package org.springaicommunity.acp.mcp.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code spring.acp.mcp.oauth.*}: how this application registers with MCP servers' authorization
 * servers, and where it keeps what it learns. Which servers are protected is said on the server
 * itself, with {@code spring.acp.mcp-servers[n].auth: oauth}.
 */
@ConfigurationProperties("spring.acp.mcp.oauth")
public class McpOAuthProperties {

	/**
	 * What kind of application this is. {@code web}: users sign in through Spring Security's redirect,
	 * and each is the principal of their own sessions. {@code local}: a terminal application run by one
	 * person, who signs in through a browser opened on the spot and is the principal of every session.
	 */
	private Mode mode = Mode.WEB;

	/** Shown to users on the authorization server's consent screen. Defaults to spring.application.name. */
	private String clientName;

	/**
	 * Where users' browsers reach this application, for the redirect URI registered with each
	 * authorization server. Read from the request that triggers the registration when unset.
	 */
	private String baseUrl;

	/** The callback path registered with each authorization server. */
	private String redirectUri = "{baseUrl}/login/oauth2/code/{registrationId}";

	/** Where client registrations and users' tokens are kept. Defaults to memory for web, file for local. */
	private Store store;

	/** The file for {@code store: file}. Defaults to {@code <config dir>/<spring.application.name>/acp-mcp-oauth.json}. */
	private java.nio.file.Path file;

	/** How long a {@code local} sign-in waits for the user to finish in the browser. */
	private java.time.Duration signInTimeout = java.time.Duration.ofMinutes(5);

	/** Allow plain http and loopback addresses for discovered OAuth endpoints. For local development only. */
	private boolean allowLoopback;

	public enum Mode {

		WEB, LOCAL

	}

	public enum Store {

		/** Lost on restart: every user signs in again, and the application registers again. */
		MEMORY,

		/**
		 * The application's database: shared by every instance and kept across restarts. Needs the
		 * {@code acp_mcp_client_registration} and {@code oauth2_authorized_client} tables.
		 */
		JDBC,

		/**
		 * One file under the user's config directory, mode 600. For a terminal application: kept
		 * across restarts, private to the person running it.
		 */
		FILE

	}

	/** The store in effect: the one configured, or the mode's default. */
	public Store effectiveStore() {
		return store != null ? store : mode == Mode.LOCAL ? Store.FILE : Store.MEMORY;
	}

	public Mode getMode() {
		return mode;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	public java.nio.file.Path getFile() {
		return file;
	}

	public void setFile(java.nio.file.Path file) {
		this.file = file;
	}

	public java.time.Duration getSignInTimeout() {
		return signInTimeout;
	}

	public void setSignInTimeout(java.time.Duration signInTimeout) {
		this.signInTimeout = signInTimeout;
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
