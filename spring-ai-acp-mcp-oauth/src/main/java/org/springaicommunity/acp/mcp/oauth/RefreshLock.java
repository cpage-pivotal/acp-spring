package org.springaicommunity.acp.mcp.oauth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lets only one refresh of one user's token for one server run at a time.
 *
 * <p>
 * Needed because the authorization servers MCP gateways sit behind rotate refresh tokens
 * and honour each one once — measured against the Tanzu MCP gateway. Two refreshes racing
 * with the same refresh token end with one of them refused, and a refused refresh removes
 * the user's stored token and sends them to sign in again. So the refresh runs under this
 * lock, and whoever waited re-reads the token once they have it and, finding it fresh,
 * does not refresh at all.
 *
 * <p>
 * Within one JVM a monitor is enough ({@link #inProcess()}). Instances sharing a token
 * store need the store's own lock: {@code JdbcRefreshLock} takes a row lock in the
 * database.
 */
public interface RefreshLock {

	/** Runs {@code action} while holding the lock for this (server, user). */
	<T> T whileLocked(String registrationId, String principalName, Supplier<T> action);

	/**
	 * A lock per (server, user) in this JVM: right for a single instance and for a
	 * terminal application.
	 */
	static RefreshLock inProcess() {
		return new RefreshLock() {

			private final Map<String, Object> monitors = new ConcurrentHashMap<>();

			@Override
			public <T> T whileLocked(String registrationId, String principalName, Supplier<T> action) {
				synchronized (monitors.computeIfAbsent(registrationId + '\u0000' + principalName,
						key -> new Object())) {
					return action.get();
				}
			}
		};
	}

}
