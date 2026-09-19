package org.tanzu.acp.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * One ACP conversation: the caller's stable name, the agent's issued id, and the turn permit that
 * keeps them honest.
 *
 * <p>The permit is a {@link Semaphore} rather than a lock because a turn is acquired on the calling
 * thread and released on whichever Reactor thread delivers the terminal event. A
 * {@code ReentrantLock} would throw when the releasing thread is not the owner.
 */
public final class AgentSession {

	private final String name;

	private final String sessionId;

	private final Semaphore turnPermit = new Semaphore(1);

	private final AtomicReference<Instant> lastUsed = new AtomicReference<>(Instant.now());

	private final AtomicReference<List<AcpSchema.SessionConfigOption>> configOptions = new AtomicReference<>(List.of());

	AgentSession(String name, String sessionId) {
		this.name = name;
		this.sessionId = sessionId;
	}

	public String name() {
		return name;
	}

	public String sessionId() {
		return sessionId;
	}

	/**
	 * The agent's advertised configuration, as last observed. Empty until a
	 * {@code session/set_config_option} response or a {@code config_option_update} reveals it —
	 * see {@code ConfigResolver} for why {@code session/new} cannot supply it.
	 */
	public List<AcpSchema.SessionConfigOption> configOptions() {
		return configOptions.get();
	}

	public void configOptions(List<AcpSchema.SessionConfigOption> options) {
		configOptions.set(options == null ? List.of() : List.copyOf(options));
	}

	/**
	 * Claims the right to run a turn.
	 *
	 * @return true if the permit was acquired within the timeout
	 */
	public boolean tryBeginTurn(Duration timeout) throws InterruptedException {
		boolean acquired = turnPermit.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
		if (acquired) {
			touch();
		}
		return acquired;
	}

	/**
	 * Releases the turn permit. Idempotent: a turn can end through several paths — normal
	 * completion, error, timeout, cancellation — and releasing twice would hand out two permits.
	 */
	public void endTurn() {
		touch();
		synchronized (turnPermit) {
			if (turnPermit.availablePermits() == 0) {
				turnPermit.release();
			}
		}
	}

	public boolean idleSince(Instant cutoff) {
		return lastUsed.get().isBefore(cutoff) && turnPermit.availablePermits() == 1;
	}

	private void touch() {
		lastUsed.set(Instant.now());
	}
}
