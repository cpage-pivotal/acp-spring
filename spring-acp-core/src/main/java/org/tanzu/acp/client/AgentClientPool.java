package org.tanzu.acp.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.config.AgentOptions;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.PoolSettings;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.session.AgentSession;
import org.tanzu.acp.session.AgentSessions;

import reactor.core.publisher.Flux;

/**
 * Several agent connections behind one {@link AgentClient}.
 *
 * <p>An agent runs one turn at a time per session, and the thing it spends that turn doing is
 * waiting on a model. So the reason to run more than one process is unrelated conversations that
 * want to make progress at once; the reason a pool exists even when there is exactly one is the
 * other two jobs it does — replacing a connection whose agent has died, and closing sessions
 * nobody has touched for an hour.
 *
 * <p><strong>A named session is sticky.</strong> Conversation state lives inside the agent
 * process, so a name assigned to one connection must keep going back to it; a pool that balanced
 * every turn independently would hand the second turn of a conversation to a process that had
 * never heard of it. Assignment happens once, to whichever connection is holding the fewest
 * sessions, and only a session that is closed or evicted gives its place up.
 *
 * <p>Connections are built lazily, so a pool of four does not start four agents at once for an
 * application that only ever has one conversation. A connection that reports itself dead is
 * closed and rebuilt on next use, up to the restart budget — and note the honest limit here: a
 * stdio agent cannot report anything, so for that transport this recovers from a connection that
 * never opened rather than one that died. See {@link AgentClient#isAlive()}.
 */
public final class AgentClientPool implements AgentClient {

	private static final Logger logger = LoggerFactory.getLogger(AgentClientPool.class);

	/** Sweeping four times per TTL means an evicted session is at most a quarter-TTL late. */
	private static final int SWEEPS_PER_TTL = 4;

	private static final Duration MAX_SWEEP_INTERVAL = Duration.ofMinutes(5);

	private final String runtimeId;

	private final PoolSettings pool;

	private final Supplier<AgentClient> connect;

	private final List<Slot> slots;

	/** Which connection owns a given session name. The stickiness rule, made concrete. */
	private final Map<String, Slot> assignment = new ConcurrentHashMap<>();

	/** Names whose session was already missing at the previous sweep. See {@link #sweep()}. */
	private final java.util.Set<String> missedLastSweep = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final ScheduledExecutorService sweeper;

	private final AtomicBoolean closed = new AtomicBoolean();

	/** A pool over the given runtime, connecting through {@link AgentClientFactory}. */
	public AgentClientPool(AgentRuntime runtime, AgentSettings settings) {
		this(runtime, settings, org.tanzu.acp.observation.AgentObservations.NONE);
	}

	/** Same, with every turn on every connection reported to {@code observations}. */
	public AgentClientPool(AgentRuntime runtime, AgentSettings settings,
			org.tanzu.acp.observation.AgentObservations observations) {
		this(runtime.id(), settings, () -> AgentClientFactory.create(runtime, settings, observations));
	}

	/**
	 * A pool over an arbitrary supplier of connections.
	 *
	 * @param connect called once per connection, and again when one is replaced
	 */
	public AgentClientPool(String runtimeId, AgentSettings settings, Supplier<AgentClient> connect) {
		this.runtimeId = runtimeId;
		this.pool = settings.pool();
		this.connect = connect;
		this.slots = new ArrayList<>(pool.maxProcesses());
		for (int i = 0; i < pool.maxProcesses(); i++) {
			slots.add(new Slot(i));
		}
		this.sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "acp-pool-" + runtimeId);
			thread.setDaemon(true);
			return thread;
		});
		long interval = Math.min(settings.sessionTtl().toMillis() / SWEEPS_PER_TTL, MAX_SWEEP_INTERVAL.toMillis());
		this.sweeper.scheduleWithFixedDelay(this::sweep, interval, Math.max(interval, 1000L), TimeUnit.MILLISECONDS);
	}

	@Override
	public String runtimeId() {
		return runtimeId;
	}

	/**
	 * What the agent said about itself, if this pool has spoken to one.
	 *
	 * <p>Empty rather than connecting: an agent's identity comes from the handshake, so answering
	 * would mean starting a process, and the caller most likely to ask is a health endpoint.
	 */
	@Override
	public Optional<AgentInfo> agentInfo() {
		return connected().map(AgentClient::agentInfo).flatMap(Optional::stream).findFirst();
	}

	@Override
	public boolean isAlive() {
		return !closed.get() && slots.stream().anyMatch(slot -> !slot.exhausted());
	}

	/**
	 * The version an open connection negotiated, or v1 when none is open.
	 *
	 * <p>Like {@link #agentInfo()}, this will not start an agent to find out: every connection in a
	 * pool runs the same runtime with the same settings, so the first one to have spoken is the
	 * answer for all of them.
	 */
	@Override
	public int protocolVersion() {
		return connected().findFirst().map(AgentClient::protocolVersion)
				.orElse(org.tanzu.acp.protocol.AcpProtocol.V1);
	}

	@Override
	public PromptSpec prompt() {
		return new PooledPromptSpec();
	}

	@Override
	public AgentSession openSession(String name) {
		return clientFor(name).openSession(name);
	}

	@Override
	public Optional<AgentSession> session(String name) {
		Slot slot = assignment.get(name);
		return slot == null ? Optional.empty() : slot.connected().flatMap(client -> client.session(name));
	}

	@Override
	public AgentSessions sessions() {
		return new PooledSessions();
	}

	@Override
	public void evictIdleSessions() {
		sweep();
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		sweeper.shutdownNow();
		assignment.clear();
		slots.forEach(Slot::discard);
	}

	/** How many connections are currently open. Exposed for tests and diagnostics. */
	public int openConnections() {
		return (int) connected().count();
	}

	/**
	 * Closes idle sessions and forgets the names that held them.
	 *
	 * <p>A name is only forgotten after it has been missing across <em>two</em> sweeps, and that is
	 * not caution for its own sake. Assigning a name and opening its session are two steps, and a
	 * sweep landing between them would see a name whose session does not exist yet, forget the
	 * assignment, and send the very next call to whichever connection is now least loaded — the
	 * duplicate-conversation failure again, arriving only under timing. Two sweeps are minutes
	 * apart, which no session-open is.
	 *
	 * <p>Forgetting at all is about the map's size rather than the pool's capacity: load is counted
	 * from the sessions a connection actually holds, so a stale entry costs one map entry and
	 * nothing else.
	 */
	private void sweep() {
		try {
			slots.forEach(slot -> slot.connected().ifPresent(AgentClient::evictIdleSessions));
			assignment.entrySet().removeIf(entry -> gone(entry) && !missedLastSweep.add(entry.getKey()));
			missedLastSweep.retainAll(assignment.keySet());
		}
		catch (RuntimeException ex) {
			logger.debug("Idle session sweep failed", ex);
		}
	}

	/** Whether the connection that owns this name no longer has its session. */
	private static boolean gone(Map.Entry<String, Slot> entry) {
		return entry.getValue().connected().map(client -> client.session(entry.getKey()).isEmpty()).orElse(true);
	}

	/**
	 * The connection that owns {@code name}, assigning one if this is the first time.
	 *
	 * <p>The re-assertion afterwards is not belt and braces. Replacing a dead connection releases
	 * every name that was assigned to it, including this one, and without putting it back the very
	 * next call would assign the name afresh — to whichever connection is now least loaded, which
	 * is precisely <em>not</em> the one that just opened the session. The conversation would then
	 * exist twice, on two processes, under one name.
	 */
	private AgentClient clientFor(String name) {
		Slot slot = assignment.computeIfAbsent(name, n -> leastLoaded());
		AgentClient client = slot.client();
		assignment.putIfAbsent(name, slot);
		return client;
	}

	/** The connection an unnamed, throwaway turn should run on. */
	private AgentClient clientForEphemeral() {
		return leastLoaded().client();
	}

	/**
	 * The connection holding the fewest sessions.
	 *
	 * <p>Counting open sessions rather than assigned names is what makes throwaway turns count:
	 * they never take a name, but they do occupy the agent for the length of a turn, and a pool
	 * that ignored them would send every one of them to the same process.
	 */
	private Slot leastLoaded() {
		Slot best = null;
		int bestLoad = Integer.MAX_VALUE;
		for (Slot slot : slots) {
			if (slot.exhausted()) {
				continue;
			}
			int load = slot.load();
			if (load < bestLoad) {
				best = slot;
				bestLoad = load;
			}
		}
		if (best == null) {
			throw new AgentClientException("Every connection to runtime '" + runtimeId
					+ "' has exhausted its restart budget of " + pool.maxRestarts());
		}
		if (bestLoad >= pool.maxSessionsPerProcess()) {
			// The least loaded is full, so all of them are. One sweep, then say so plainly.
			sweep();
			if (best.load() >= pool.maxSessionsPerProcess()) {
				throw new AgentClientException("Runtime '" + runtimeId + "' is holding its maximum of "
						+ pool.capacity() + " sessions (" + pool.maxProcesses() + " process(es) × "
						+ pool.maxSessionsPerProcess() + "); close some, or raise spring.acp.pool");
			}
		}
		return best;
	}

	private java.util.stream.Stream<AgentClient> connected() {
		return slots.stream().map(Slot::connected).filter(Optional::isPresent).map(Optional::get);
	}

	/** Any usable connection, for the operations that are not about one session in particular. */
	private AgentClient any() {
		return connected().findFirst().orElseGet(() -> leastLoaded().client());
	}

	/**
	 * One connection's worth of the pool: at most one live {@link AgentClient}, and the budget for
	 * replacing it.
	 */
	private final class Slot {

		private final int index;

		private final Object lock = new Object();

		private AgentClient client;

		private int restarts;

		private Slot(int index) {
			this.index = index;
		}

		private Optional<AgentClient> connected() {
			AgentClient current = client;
			return Optional.ofNullable(current);
		}

		private int load() {
			AgentClient current = client;
			return current == null ? 0 : current.sessions().open().size();
		}

		private boolean exhausted() {
			return restarts > pool.maxRestarts();
		}

		/**
		 * The live connection, building or rebuilding it as needed.
		 *
		 * <p>Under a lock because two threads finding a dead connection at the same moment would
		 * otherwise start two agents and keep one, and the one they discarded would be a process
		 * nobody closes.
		 */
		private AgentClient client() {
			synchronized (lock) {
				if (client != null && client.isAlive()) {
					return client;
				}
				if (client != null) {
					logger.warn("Connection {} to runtime '{}' is no longer alive; replacing it", index, runtimeId);
					discard();
					restarts++;
					if (exhausted()) {
						throw new AgentClientException("Connection " + index + " to runtime '" + runtimeId
								+ "' has been replaced " + pool.maxRestarts() + " times and is being left down");
					}
				}
				client = connect.get();
				return client;
			}
		}

		private void discard() {
			AgentClient current = client;
			client = null;
			if (current == null) {
				return;
			}
			assignment.values().removeIf(slot -> slot == this);
			try {
				current.close();
			}
			catch (RuntimeException ex) {
				logger.debug("Closing connection {} to runtime '{}' failed", index, runtimeId, ex);
			}
		}
	}

	/**
	 * Buffers a prompt until it is run, because the connection cannot be chosen before the session
	 * name is known and {@code session(...)} may be called after {@code prompt()}.
	 */
	private final class PooledPromptSpec implements PromptSpec {

		private final List<String> text = new ArrayList<>();

		private String sessionName;

		private AgentOptions options = AgentOptions.none();

		@Override
		public PromptSpec session(String name) {
			this.sessionName = name;
			return this;
		}

		@Override
		public PromptSpec user(String content) {
			text.add(content);
			return this;
		}

		@Override
		public PromptSpec options(AgentOptions options) {
			this.options = options == null ? AgentOptions.none() : options;
			return this;
		}

		@Override
		public PromptSpec options(Consumer<AgentOptions.Builder> customizer) {
			AgentOptions.Builder builder = AgentOptions.builder();
			customizer.accept(builder);
			return options(builder.build());
		}

		@Override
		public AgentResponse call() {
			return delegate().call();
		}

		@Override
		public AgentStream stream() {
			// Deferred so that choosing the connection, like everything else about the turn, happens
			// on subscription rather than when the stream was described.
			return () -> Flux.defer(() -> delegate().stream().events());
		}

		private PromptSpec delegate() {
			AgentClient client = sessionName == null ? clientForEphemeral() : clientFor(sessionName);
			PromptSpec spec = client.prompt();
			if (sessionName != null) {
				spec = spec.session(sessionName);
			}
			text.forEach(spec::user);
			return spec.options(options);
		}
	}

	/** Session operations across every connection in the pool. */
	private final class PooledSessions implements AgentSessions {

		@Override
		public List<org.tanzu.acp.session.StoredSession> list() {
			return any().sessions().list();
		}

		@Override
		public List<org.tanzu.acp.session.StoredSession> list(java.nio.file.Path cwd) {
			return any().sessions().list(cwd);
		}

		@Override
		public AgentSession load(String name, String sessionId) {
			return clientFor(name).sessions().load(name, sessionId);
		}

		@Override
		public AgentSession resume(String name, String sessionId) {
			return clientFor(name).sessions().resume(name, sessionId);
		}

		@Override
		public void delete(String sessionId) {
			any().sessions().delete(sessionId);
		}

		@Override
		public void close(String name) {
			Slot slot = assignment.remove(name);
			if (slot != null) {
				slot.connected().ifPresent(client -> client.sessions().close(name));
			}
		}

		@Override
		public List<AgentSession> open() {
			return connected().flatMap(client -> client.sessions().open().stream()).toList();
		}

		@Override
		public Optional<AgentSession> find(String name) {
			return session(name);
		}

		@Override
		public boolean supports(Operation operation) {
			return any().sessions().supports(operation);
		}
	}
}
