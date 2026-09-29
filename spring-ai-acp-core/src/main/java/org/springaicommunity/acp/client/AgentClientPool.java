package org.springaicommunity.acp.client;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.config.AgentOptions;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.config.PoolSettings;
import org.springaicommunity.acp.runtime.AgentRuntime;
import org.springaicommunity.acp.runtime.AgentRuntime.McpScope;
import org.springaicommunity.acp.session.AgentSession;
import org.springaicommunity.acp.session.AgentSessions;
import org.springaicommunity.acp.session.SessionPrincipal;

import reactor.core.publisher.Flux;

/**
 * Several agent connections behind one {@link AgentClient}.
 *
 * <p>
 * An agent runs one turn at a time per session, and the thing it spends that turn doing
 * is waiting on a model. So the reason to run more than one process is unrelated
 * conversations that want to make progress at once; the reason a pool exists even when
 * there is exactly one is the other two jobs it does — replacing a connection whose agent
 * has died, and closing sessions nobody has touched for an hour.
 *
 * <p>
 * <strong>A named session is sticky.</strong> Conversation state lives inside the agent
 * process, so a name assigned to one connection must keep going back to it; a pool that
 * balanced every turn independently would hand the second turn of a conversation to a
 * process that had never heard of it. Assignment happens once, to whichever connection is
 * holding the fewest sessions, and only a session that is closed or evicted gives its
 * place up.
 *
 * <p>
 * Connections are built lazily, so a pool of four does not start four agents at once for
 * an application that only ever has one conversation. A connection that reports itself
 * dead is closed and rebuilt on next use, up to the restart budget — and note the honest
 * limit here: a stdio agent cannot report anything, so for that transport this recovers
 * from a connection that never opened rather than one that died. See
 * {@link AgentClient#isAlive()}.
 *
 * <p>
 * <strong>A runtime that shares MCP servers across its sessions gets one principal per
 * process.</strong> Such an agent ({@link McpScope#PROCESS}) sends every session's MCP
 * calls through the route declared last, so two users on one process would act as each
 * other. Under that scope a session goes to a process already serving its principal, or
 * to one serving nobody; a process that last served someone else is restarted before it
 * is handed over, so nothing it held carries across. {@code max-processes} is then the
 * number of users who can hold sessions at once.
 */
public final class AgentClientPool implements AgentClient {

	private static final Logger logger = LoggerFactory.getLogger(AgentClientPool.class);

	/**
	 * Sweeping four times per TTL means an evicted session is at most a quarter-TTL late.
	 */
	private static final int SWEEPS_PER_TTL = 4;

	private static final Duration MAX_SWEEP_INTERVAL = Duration.ofMinutes(5);

	private final String runtimeId;

	private final PoolSettings pool;

	private final Supplier<AgentClient> connect;

	/**
	 * Asked eagerly, on the caller's thread, since connections are chosen later on
	 * subscription.
	 */
	private final org.springaicommunity.acp.session.SessionPrincipalResolver principals;

	private final McpScope mcpScope;

	/**
	 * Held while a session is placed under {@link McpScope#PROCESS}, so two principals
	 * cannot both claim the same idle process.
	 */
	private final Object placement = new Object();

	private final List<Slot> slots;

	/** Which connection owns a given session name. The stickiness rule, made concrete. */
	private final Map<String, Slot> assignment = new ConcurrentHashMap<>();

	/**
	 * Names whose session was already missing at the previous sweep. See
	 * {@link #sweep()}.
	 */
	private final java.util.Set<String> missedLastSweep = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final ScheduledExecutorService sweeper;

	private final AtomicBoolean closed = new AtomicBoolean();

	/** A pool over the given runtime, connecting through {@link AgentClientFactory}. */
	public AgentClientPool(AgentRuntime runtime, AgentSettings settings) {
		this(runtime, settings, org.springaicommunity.acp.observation.AgentObservations.NONE);
	}

	/** Same, with every turn on every connection reported to {@code observations}. */
	public AgentClientPool(AgentRuntime runtime, AgentSettings settings,
			org.springaicommunity.acp.observation.AgentObservations observations) {
		this(runtime.id(), settings, () -> AgentClientFactory.create(runtime, settings, observations),
				runtime.mcpScope());
	}

	/**
	 * A pool over an arbitrary supplier of connections.
	 * @param connect called once per connection, and again when one is replaced
	 */
	public AgentClientPool(String runtimeId, AgentSettings settings, Supplier<AgentClient> connect) {
		this(runtimeId, settings, connect, McpScope.SESSION);
	}

	/**
	 * Same, for a runtime whose MCP scope is given.
	 * @param mcpScope where the runtime keeps a session's MCP servers; see
	 * {@link AgentRuntime#mcpScope()}
	 */
	public AgentClientPool(String runtimeId, AgentSettings settings, Supplier<AgentClient> connect, McpScope mcpScope) {
		this.runtimeId = runtimeId;
		this.mcpScope = mcpScope == null ? McpScope.SESSION : mcpScope;
		this.pool = settings.pool();
		this.connect = connect;
		this.principals = settings.mcp().principals();
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
	 * <p>
	 * Empty rather than connecting: an agent's identity comes from the handshake, so
	 * answering would mean starting a process, and the caller most likely to ask is a
	 * health endpoint.
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
	 * <p>
	 * Like {@link #agentInfo()}, this will not start an agent to find out: every
	 * connection in a pool runs the same runtime with the same settings, so the first one
	 * to have spoken is the answer for all of them.
	 */
	@Override
	public int protocolVersion() {
		return connected().findFirst()
			.map(AgentClient::protocolVersion)
			.orElse(org.springaicommunity.acp.protocol.AcpProtocol.V1);
	}

	@Override
	public PromptSpec prompt() {
		return new PooledPromptSpec();
	}

	@Override
	public AgentSession openSession(String name) {
		return openSession(name, currentPrincipal());
	}

	@Override
	public AgentSession openSession(String name, org.springaicommunity.acp.session.SessionPrincipal principal) {
		return clientFor(name, principal).openSession(name, principal);
	}

	private org.springaicommunity.acp.session.SessionPrincipal currentPrincipal() {
		return principals.current().orElse(null);
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
	 * <p>
	 * A name is only forgotten after it has been missing across <em>two</em> sweeps, and
	 * that is not caution for its own sake. Assigning a name and opening its session are
	 * two steps, and a sweep landing between them would see a name whose session does not
	 * exist yet, forget the assignment, and send the very next call to whichever
	 * connection is now least loaded — the duplicate-conversation failure again, arriving
	 * only under timing. Two sweeps are minutes apart, which no session-open is.
	 *
	 * <p>
	 * Forgetting at all is about the map's size rather than the pool's capacity: load is
	 * counted from the sessions a connection actually holds, so a stale entry costs one
	 * map entry and nothing else.
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
	 * <p>
	 * The re-assertion afterwards is not belt and braces. Replacing a dead connection
	 * releases every name that was assigned to it, including this one, and without
	 * putting it back the very next call would assign the name afresh — to whichever
	 * connection is now least loaded, which is precisely <em>not</em> the one that just
	 * opened the session. The conversation would then exist twice, on two processes,
	 * under one name.
	 */
	private AgentClient clientFor(String name, SessionPrincipal principal) {
		if (mcpScope == McpScope.PROCESS) {
			Slot slot;
			synchronized (placement) {
				slot = assignment.get(name);
				if (slot == null) {
					slot = placeFor(principal);
					assignment.put(name, slot);
				}
			}
			AgentClient client = slot.client();
			assignment.putIfAbsent(name, slot);
			return client;
		}
		Slot slot = assignment.computeIfAbsent(name, n -> leastLoaded());
		AgentClient client = slot.client();
		assignment.putIfAbsent(name, slot);
		return client;
	}

	/**
	 * The connection an unnamed, throwaway turn should run on, and what to run when the
	 * turn is over.
	 *
	 * <p>
	 * Under {@link McpScope#PROCESS} the turn holds its process for its whole length: it
	 * takes no name, so without the hold the process would look idle to a second
	 * principal, who would restart it mid-turn.
	 */
	private Lease leaseForEphemeral(SessionPrincipal principal) {
		if (mcpScope != McpScope.PROCESS) {
			return new Lease(leastLoaded().client(), () -> {
			});
		}
		Slot slot;
		synchronized (placement) {
			slot = placeFor(principal);
			slot.ephemeral.incrementAndGet();
		}
		try {
			return new Lease(slot.client(), slot.ephemeral::decrementAndGet);
		}
		catch (RuntimeException ex) {
			slot.ephemeral.decrementAndGet();
			throw ex;
		}
	}

	/**
	 * The connection for a session of {@code principal}'s, under
	 * {@link McpScope#PROCESS}: the least loaded of those already serving them, else an
	 * idle one, restarted first if it last served someone else. Called holding
	 * {@link #placement}.
	 *
	 * <p>
	 * The restart is not counted against {@code max-restarts}: nothing failed.
	 */
	private Slot placeFor(SessionPrincipal principal) {
		Optional<SessionPrincipal> who = Optional.ofNullable(principal);
		Slot slot = placeOrNull(who);
		if (slot == null) {
			sweep();
			slot = placeOrNull(who);
		}
		if (slot == null) {
			throw new AgentClientException("Runtime '" + runtimeId + "' shares MCP servers across the sessions of "
					+ "a process, so each process serves one user at a time, and all " + pool.maxProcesses()
					+ " are serving others; close some sessions, or raise spring.acp.pool.max-processes");
		}
		if (slot.owner != null && !slot.owner.equals(who)) {
			logger.debug("Restarting connection {} to runtime '{}' before it serves another principal", slot.index,
					runtimeId);
			slot.recycle();
		}
		slot.owner = who;
		return slot;
	}

	private Slot placeOrNull(Optional<SessionPrincipal> who) {
		Slot best = null;
		int bestLoad = Integer.MAX_VALUE;
		for (Slot slot : slots) {
			if (slot.exhausted() || slot.owner == null || !slot.owner.equals(who)) {
				continue;
			}
			int load = slot.load();
			if (load < pool.maxSessionsPerProcess() && load < bestLoad) {
				best = slot;
				bestLoad = load;
			}
		}
		if (best != null) {
			return best;
		}
		Slot idle = null;
		for (Slot slot : slots) {
			if (slot.exhausted() || !idle(slot)) {
				continue;
			}
			if (slot.owner == null) {
				return slot;
			}
			if (idle == null) {
				idle = slot;
			}
		}
		return idle;
	}

	/** Holding no session, no name and no turn: free to serve another principal. */
	private boolean idle(Slot slot) {
		return slot.ephemeral.get() == 0 && !assignment.containsValue(slot) && slot.load() == 0;
	}

	/**
	 * The connection holding the fewest sessions.
	 *
	 * <p>
	 * Counting open sessions rather than assigned names is what makes throwaway turns
	 * count: they never take a name, but they do occupy the agent for the length of a
	 * turn, and a pool that ignored them would send every one of them to the same
	 * process.
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
			// The least loaded is full, so all of them are. One sweep, then say so
			// plainly.
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

	/**
	 * Any usable connection, for the operations that are not about one session in
	 * particular.
	 */
	private AgentClient any() {
		return connected().findFirst().orElseGet(() -> leastLoaded().client());
	}

	/**
	 * One connection's worth of the pool: at most one live {@link AgentClient}, and the
	 * budget for replacing it.
	 */
	private final class Slot {

		private final int index;

		private final Object lock = new Object();

		private AgentClient client;

		private int restarts;

		/**
		 * Under {@link McpScope#PROCESS}, the principal this connection serves or last
		 * served; null before its first session. Read and written holding
		 * {@link #placement}.
		 */
		private Optional<SessionPrincipal> owner;

		/** Unnamed turns in flight, under {@link McpScope#PROCESS}. */
		private final AtomicInteger ephemeral = new AtomicInteger();

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
		 * <p>
		 * Under a lock because two threads finding a dead connection at the same moment
		 * would otherwise start two agents and keep one, and the one they discarded would
		 * be a process nobody closes.
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

		/**
		 * Closes the connection so the next use starts a fresh agent, without spending
		 * the restart budget.
		 */
		private void recycle() {
			synchronized (lock) {
				discard();
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
	 * Buffers a prompt until it is run, because the connection cannot be chosen before
	 * the session name is known and {@code session(...)} may be called after
	 * {@code prompt()}.
	 */
	private final class PooledPromptSpec implements PromptSpec {

		private final List<String> text = new ArrayList<>();

		private String sessionName;

		private AgentOptions options = AgentOptions.none();

		private org.springaicommunity.acp.session.SessionPrincipal principal;

		@Override
		public PromptSpec session(String name) {
			this.sessionName = name;
			return this;
		}

		@Override
		public PromptSpec principal(org.springaicommunity.acp.session.SessionPrincipal principal) {
			this.principal = principal;
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
			SessionPrincipal owner = owner();
			Lease lease = lease(owner);
			try {
				return delegate(lease.client(), owner).call();
			}
			finally {
				lease.release().run();
			}
		}

		@Override
		public AgentStream stream() {
			// Deferred so that choosing the connection, like everything else about the
			// turn, happens
			// on subscription rather than when the stream was described. The principal is
			// the
			// exception: it is read now, on the caller's thread, and carried in.
			org.springaicommunity.acp.session.SessionPrincipal owner = owner();
			return () -> Flux.defer(() -> {
				Lease lease = lease(owner);
				Flux<org.springaicommunity.acp.event.AgentEvent> events;
				try {
					events = delegate(lease.client(), owner).stream().events();
				}
				catch (RuntimeException ex) {
					lease.release().run();
					throw ex;
				}
				return events.doFinally(signal -> lease.release().run());
			});
		}

		private org.springaicommunity.acp.session.SessionPrincipal owner() {
			return principal != null ? principal : currentPrincipal();
		}

		private Lease lease(SessionPrincipal owner) {
			return sessionName == null ? leaseForEphemeral(owner) : new Lease(clientFor(sessionName, owner), () -> {
			});
		}

		private PromptSpec delegate(AgentClient client, SessionPrincipal owner) {
			PromptSpec spec = client.prompt().principal(owner);
			if (sessionName != null) {
				spec = spec.session(sessionName);
			}
			text.forEach(spec::user);
			return spec.options(options);
		}

	}

	/** A connection chosen for one turn, and what to run once the turn is over. */
	private record Lease(AgentClient client, Runnable release) {
	}

	/** Session operations across every connection in the pool. */
	private final class PooledSessions implements AgentSessions {

		@Override
		public List<org.springaicommunity.acp.session.StoredSession> list() {
			return any().sessions().list();
		}

		@Override
		public List<org.springaicommunity.acp.session.StoredSession> list(java.nio.file.Path cwd) {
			return any().sessions().list(cwd);
		}

		@Override
		public AgentSession load(String name, String sessionId) {
			return load(name, sessionId, currentPrincipal());
		}

		@Override
		public AgentSession resume(String name, String sessionId) {
			return resume(name, sessionId, currentPrincipal());
		}

		@Override
		public AgentSession load(String name, String sessionId,
				org.springaicommunity.acp.session.SessionPrincipal principal) {
			return clientFor(name, principal).sessions().load(name, sessionId, principal);
		}

		@Override
		public AgentSession resume(String name, String sessionId,
				org.springaicommunity.acp.session.SessionPrincipal principal) {
			return clientFor(name, principal).sessions().resume(name, sessionId, principal);
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
