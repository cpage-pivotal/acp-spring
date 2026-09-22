package org.thought.acp.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.thought.acp.config.AdvertisedSessionConfig;
import org.thought.acp.config.SessionConfiguration;

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

	private final AtomicReference<AdvertisedSessionConfig> advertised = new AtomicReference<>(
			AdvertisedSessionConfig.empty());

	private final AtomicReference<SessionConfiguration> configuration = new AtomicReference<>(
			SessionConfiguration.empty());

	private final SessionPrincipal principal;

	/** Whatever must end with the session: today, its MCP proxy routes. Run at most once. */
	private final AtomicReference<Runnable> release;

	AgentSession(String name, String sessionId) {
		this(name, sessionId, null, null);
	}

	AgentSession(String name, String sessionId, SessionPrincipal principal, Runnable release) {
		this.name = name;
		this.sessionId = sessionId;
		this.principal = principal;
		this.release = new AtomicReference<>(release);
	}

	public String name() {
		return name;
	}

	public String sessionId() {
		return sessionId;
	}

	/** Who this session was opened for; empty for a session opened on nobody's behalf. */
	public Optional<SessionPrincipal> principal() {
		return Optional.ofNullable(principal);
	}

	/** Whether a caller acting for {@code candidate} (null for nobody) may use this session. */
	boolean belongsTo(SessionPrincipal candidate) {
		return Objects.equals(principal, candidate);
	}

	/**
	 * Lets go of what this session held beyond the agent: its MCP proxy routes, so the URLs the
	 * agent was given stop working. Called by the registry whenever it forgets the session, by
	 * whichever path; idempotent because there are several.
	 */
	void release() {
		Runnable pending = release.getAndSet(null);
		if (pending != null) {
			pending.run();
		}
	}

	/**
	 * What the agent advertised when this session was created: the options it will accept, and the
	 * values it has for each. Read by {@code ConfigResolver} before it sets anything.
	 */
	public AdvertisedSessionConfig advertised() {
		return advertised.get();
	}

	public void advertised(AdvertisedSessionConfig config) {
		AdvertisedSessionConfig resolved = config == null ? AdvertisedSessionConfig.empty() : config;
		advertised.set(resolved);
		configOptions.set(resolved.configOptions());
	}

	/**
	 * What the negotiated tier actually managed to apply. An application that asked for a model and
	 * got {@code on-unsupported: warn} can find out here which model it is really talking to.
	 */
	public SessionConfiguration configuration() {
		return configuration.get();
	}

	public void configuration(SessionConfiguration resolved) {
		configuration.set(resolved == null ? SessionConfiguration.empty() : resolved);
	}

	/**
	 * The agent's current configuration, as last observed. Seeded from {@link #advertised()} and
	 * refreshed by every {@code session/set_config_option} response and {@code config_option_update}.
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
