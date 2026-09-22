package org.thought.acp.mcp.oauth;

import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The refresh lock for instances that share a database: a row lock on the user's stored token.
 *
 * <p>Runs the refresh in a transaction that first takes {@code SELECT … FOR UPDATE} on the user's row
 * in Spring Security's {@code oauth2_authorized_client}. Another instance arriving at the same moment
 * blocks on that row until the first commits, then re-reads a token that is no longer expired and
 * sends nothing to the authorization server. The token store must use the same data source as the
 * transaction manager, so that its reads and writes happen inside the lock — which is how Spring's
 * {@code JdbcTemplate} behaves by default.
 *
 * <p>Held for the length of one token request — the refresh itself is an HTTP call made while the row
 * is locked — and only when a token has actually expired. A user with no row yet has nothing to race
 * over, so no lock is taken.
 *
 * <p>Standard {@code FOR UPDATE}: PostgreSQL, MySQL, MariaDB, Oracle, H2. A database that spells a
 * row lock differently (SQL Server's {@code WITH (UPDLOCK)}) passes its own statement.
 */
public final class JdbcRefreshLock implements RefreshLock {

	/** The default statement: one row, locked until the transaction ends. */
	public static final String SELECT_FOR_UPDATE = "SELECT principal_name FROM oauth2_authorized_client "
			+ "WHERE client_registration_id = ? AND principal_name = ? FOR UPDATE";

	/**
	 * Threads of this JVM queue here first, so one instance holds at most one connection per
	 * (server, user) waiting on the row, rather than one per concurrent tool call.
	 */
	private final RefreshLock local = RefreshLock.inProcess();

	private final TransactionOperations transactions;

	private final JdbcOperations jdbc;

	private final String lockStatement;

	public JdbcRefreshLock(TransactionOperations transactions, JdbcOperations jdbc) {
		this(transactions, jdbc, SELECT_FOR_UPDATE);
	}

	/**
	 * @param lockStatement locks the row for (client_registration_id, principal_name), bound in that order
	 */
	public JdbcRefreshLock(TransactionOperations transactions, JdbcOperations jdbc, String lockStatement) {
		this.transactions = transactions;
		this.jdbc = jdbc;
		this.lockStatement = lockStatement;
	}

	@Override
	public <T> T whileLocked(String registrationId, String principalName, Supplier<T> action) {
		return local.whileLocked(registrationId, principalName, () -> transactions.execute(status -> {
			jdbc.query(lockStatement, rs -> {
			}, registrationId, principalName);
			return action.get();
		}));
	}
}
