package org.springaicommunity.acp.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.client.AgentClientException;
import org.springaicommunity.acp.runtime.AgentLaunchSpec;

/**
 * Owns a long-lived agent server process: spawns it, waits for it to be ready, keeps its output out
 * of the way, restarts it when it dies, and kills it on shutdown.
 *
 * <p>Only the WebSocket launch path needs this. A stdio agent's lifetime is the transport's,
 * because the SDK spawns it and closing the transport closes its pipes; a served agent outlives any
 * one connection and so needs an owner.
 *
 * <p>Four details are load-bearing, all of them learned the hard way in the wrapper this succeeds:
 *
 * <ul>
 * <li>The output pipe is drained on a virtual thread. A long-running child whose output nobody
 * reads blocks the moment the OS buffer fills, and the symptom is an agent that simply stops
 * answering.</li>
 * <li>Restarts back off exponentially and are counted inside a rolling window, so a server that
 * cannot start — a bad config, a port already taken — is given up on rather than respawned
 * forever.</li>
 * <li>A shutdown hook kills the child, because a JVM killed without running its context's
 * lifecycle would otherwise leave an agent process behind holding a port.</li>
 * <li>Every line of output goes through {@link SecretRedactor} first.</li>
 * </ul>
 */
public final class AgentProcessSupervisor implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(AgentProcessSupervisor.class);

	/** Restarts are counted inside this window; surviving it resets the budget. */
	private static final Duration RESTART_WINDOW = Duration.ofMinutes(5);

	private static final Duration HEALTH_POLL_INTERVAL = Duration.ofSeconds(5);

	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

	/** With no health endpoint, a process that is still alive after this is treated as ready. */
	private static final Duration LIVENESS_GRACE = Duration.ofMillis(500);

	/** Enough of the agent's last words to explain a failed startup, and no more. */
	private static final int KEPT_LINES = 5;

	private final String runtimeId;

	private final AgentLaunchSpec.ManagedProcess spec;

	private final int maxRestarts;

	private final Logger processLogger;

	private final ScheduledExecutorService scheduler;

	private final HttpClient httpClient;

	private final List<Runnable> restartListeners = new CopyOnWriteArrayList<>();

	private final AtomicBoolean closed = new AtomicBoolean();

	private final AtomicInteger restartsInWindow = new AtomicInteger();

	private final java.util.Deque<String> recent = new java.util.concurrent.ConcurrentLinkedDeque<>();

	private volatile long windowStartedAt = System.nanoTime();

	private volatile Process process;

	private volatile boolean healthy;

	private volatile boolean givenUp;

	private Thread shutdownHook;

	public AgentProcessSupervisor(String runtimeId, AgentLaunchSpec.ManagedProcess spec, int maxRestarts) {
		this.runtimeId = runtimeId;
		this.spec = spec;
		this.maxRestarts = Math.max(maxRestarts, 0);
		this.processLogger = LoggerFactory.getLogger("org.springaicommunity.acp.agent." + runtimeId);
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "acp-supervisor-" + runtimeId);
			thread.setDaemon(true);
			return thread;
		});
		this.httpClient = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
	}

	/** Spawns the process and blocks until it is ready. */
	public synchronized void start() {
		if (closed.get()) {
			throw new AgentClientException("Supervisor for '" + runtimeId + "' has been closed");
		}
		spawn();
		installShutdownHook();
		if (spec.healthUri() != null) {
			scheduler.scheduleWithFixedDelay(this::pollHealth, HEALTH_POLL_INTERVAL.toMillis(),
					HEALTH_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	/** Whether the process is believed to be up. A volatile read, no I/O. */
	public boolean isHealthy() {
		return healthy && !closed.get();
	}

	/** Whether the restart budget has run out. Such a supervisor will never be healthy again. */
	public boolean hasGivenUp() {
		return givenUp;
	}

	/** Called after a restart brings the process back, so a caller can rebuild its connection. */
	public void addRestartListener(Runnable listener) {
		restartListeners.add(listener);
	}

	private void spawn() {
		List<String> command = new ArrayList<>();
		command.add(spec.command());
		command.addAll(spec.args());

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.environment().putAll(spec.env());
		// One stream: an agent's diagnostics are interleaved prose, not two independent logs.
		builder.redirectErrorStream(true);

		logger.info("Starting {} as a supervised process: {}", runtimeId, spec.command());
		Process started;
		try {
			started = builder.start();
		}
		catch (IOException ex) {
			throw new AgentClientException("Could not start '" + spec.command() + "' for runtime '" + runtimeId + "'",
					ex);
		}
		this.process = started;
		drainOutput(started);
		watchForExit(started);

		if (!awaitReady(started)) {
			started.destroyForcibly();
			throw new AgentClientException("Runtime '" + runtimeId + "' did not become ready within "
					+ spec.startupTimeout() + lastWords());
		}
		healthy = true;
		logger.info("Runtime '{}' is ready (pid {})", runtimeId, started.pid());
	}

	/**
	 * An undrained pipe eventually blocks the child, so this thread is mandatory rather than a
	 * logging convenience. The last few lines are also kept, so a startup failure can say why.
	 */
	private void drainOutput(Process started) {
		Thread.ofVirtual().name("acp-" + runtimeId + "-output").start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String redacted = SecretRedactor.redact(line);
					remember(redacted);
					processLogger.info("{}", redacted);
				}
			}
			catch (IOException ex) {
				logger.debug("Output stream for '{}' closed: {}", runtimeId, ex.getMessage());
			}
		});
	}

	private void remember(String line) {
		if (line == null || line.isBlank()) {
			return;
		}
		recent.addLast(line.strip());
		while (recent.size() > KEPT_LINES) {
			recent.pollFirst();
		}
	}

	private String lastWords() {
		return recent.isEmpty() ? "" : "; it said: " + String.join(" | ", recent);
	}

	private void watchForExit(Process started) {
		started.onExit().thenAccept(exited -> {
			if (closed.get()) {
				return;
			}
			healthy = false;
			logger.warn("Runtime '{}' exited unexpectedly with code {}", runtimeId, exited.exitValue());
			scheduleRestart();
		});
	}

	private void scheduleRestart() {
		if (Duration.ofNanos(System.nanoTime() - windowStartedAt).compareTo(RESTART_WINDOW) > 0) {
			windowStartedAt = System.nanoTime();
			restartsInWindow.set(0);
		}
		int attempt = restartsInWindow.incrementAndGet();
		if (attempt > maxRestarts) {
			givenUp = true;
			logger.error("Runtime '{}' has restarted {} times within {} — giving up", runtimeId, attempt - 1,
					RESTART_WINDOW);
			return;
		}
		long delayMillis = Math.min(30_000L, 1000L * (1L << Math.min(attempt - 1, 5)));
		logger.info("Restarting runtime '{}' in {} ms (attempt {} of {})", runtimeId, delayMillis, attempt,
				maxRestarts);
		scheduler.schedule(() -> {
			if (closed.get()) {
				return;
			}
			try {
				spawn();
				restartListeners.forEach(this::runQuietly);
			}
			catch (RuntimeException ex) {
				logger.error("Restart of runtime '{}' failed: {}", runtimeId, ex.getMessage());
				scheduleRestart();
			}
		}, delayMillis, TimeUnit.MILLISECONDS);
	}

	private void runQuietly(Runnable listener) {
		try {
			listener.run();
		}
		catch (RuntimeException ex) {
			logger.warn("A restart listener for '{}' failed: {}", runtimeId, ex.getMessage());
		}
	}

	/**
	 * Readiness, which is not the same as having been spawned: an agent that rejects its
	 * configuration exits a second later, and connecting to a port it never bound produces a much
	 * worse error message than waiting here does.
	 */
	private boolean awaitReady(Process started) {
		long deadline = System.nanoTime() + spec.startupTimeout().toNanos();
		while (System.nanoTime() < deadline) {
			if (!started.isAlive()) {
				logger.error("Runtime '{}' exited during startup with code {}", runtimeId, started.exitValue());
				return false;
			}
			if (spec.healthUri() == null) {
				// Nothing to probe: give it long enough to fail, then take being alive as ready.
				sleep(LIVENESS_GRACE);
				return started.isAlive();
			}
			if (probeHealth()) {
				return true;
			}
			sleep(Duration.ofMillis(200));
		}
		return false;
	}

	private void pollHealth() {
		Process current = process;
		if (closed.get() || givenUp || current == null || !current.isAlive()) {
			return;
		}
		healthy = probeHealth();
	}

	private boolean probeHealth() {
		try {
			HttpRequest request = HttpRequest.newBuilder(spec.healthUri()).timeout(PROBE_TIMEOUT).GET().build();
			return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
		catch (IOException ex) {
			return false;
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private void installShutdownHook() {
		if (shutdownHook != null) {
			return;
		}
		shutdownHook = new Thread(this::terminate, "acp-" + runtimeId + "-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		healthy = false;
		terminate();
		scheduler.shutdownNow();
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			}
			catch (IllegalStateException alreadyShuttingDown) {
				// The JVM is on its way out and is running the hook itself.
			}
		}
	}

	private void terminate() {
		Process current = process;
		if (current == null || !current.isAlive()) {
			return;
		}
		logger.info("Stopping runtime '{}' (pid {})", runtimeId, current.pid());
		current.destroy();
		try {
			if (!current.waitFor(5, TimeUnit.SECONDS)) {
				current.destroyForcibly();
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			current.destroyForcibly();
		}
	}
}
