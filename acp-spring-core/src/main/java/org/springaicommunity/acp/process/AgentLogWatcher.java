package org.springaicommunity.acp.process;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.runtime.AgentNotice;

/**
 * Tails the agent's own log and reports the few lines a client has any business reading.
 *
 * <p>This exists for one measured gap. An agent that fails to connect to an MCP server tells its
 * client nothing: {@code session/new} succeeds, no {@code session/update} follows, and goose 1.51.0
 * writes not one line to stdout or stderr under either of its transports. It does write
 * {@code Failed to load extension <name>} to a file. Nothing in ACP can ask after the fact — there
 * is no {@code tools/list} — so the file is the only source, and an application that does not read
 * it finds out from the model's prose that it has no tools.
 *
 * <p>The division of labour is the same as everywhere else in this library: the adapter declares
 * where the log is and what a line means, and this class does the reading. It knows nothing about
 * any agent.
 *
 * <p>Deliberately modest about what it will read. Only files modified after this watcher started
 * are followed, because a log directory is usually shared with every other run on the machine and
 * replaying yesterday's failures as today's would be worse than saying nothing. Each file is read
 * forward from where the last poll stopped, so a rotated or newly created file is picked up on the
 * next tick and an appended one is not re-read from the top.
 */
public final class AgentLogWatcher implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(AgentLogWatcher.class);

	private static final Duration POLL = Duration.ofSeconds(1);

	/** As many as {@code AgentDiagnostics} keeps, and for the same reason: this is not a log sink. */
	private static final int KEPT_NOTICES = 20;

	/** A log directory with more files than this in play is not one worth tailing line by line. */
	private static final int MAX_FILES = 32;

	private final Path directory;

	private final Function<String, java.util.Optional<AgentNotice>> interpret;

	private final Consumer<AgentNotice> onNotice;

	private final Deque<AgentNotice> notices = new ConcurrentLinkedDeque<>();

	/** Where the last poll stopped in each file, so an append is read once. */
	private final Map<Path, Long> offsets = new HashMap<>();

	private final AtomicBoolean running = new AtomicBoolean(true);

	private final long startedAt = System.currentTimeMillis();

	private volatile Thread thread;

	public AgentLogWatcher(Path directory, Function<String, java.util.Optional<AgentNotice>> interpret) {
		this(directory, interpret, notice -> {
		});
	}

	/**
	 * @param onNotice called for each new notice, from the watcher's own thread, so an implementation
	 * that blocks delays only further reading of a log file
	 */
	public AgentLogWatcher(Path directory, Function<String, java.util.Optional<AgentNotice>> interpret,
			Consumer<AgentNotice> onNotice) {
		this.directory = directory;
		this.interpret = interpret;
		this.onNotice = onNotice;
	}

	/** Starts polling. The directory need not exist yet; agents create it when they first write. */
	public void start() {
		thread = Thread.ofVirtual().name("acp-log-watch").start(() -> {
			while (running.get()) {
				try {
					poll();
					Thread.sleep(POLL);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return;
				}
				catch (RuntimeException ex) {
					// A log that cannot be read is not a reason to fail anything the client is doing.
					logger.debug("Could not read the agent's log under {}", directory, ex);
				}
			}
		});
	}

	/**
	 * Reads whatever has been appended since the last call.
	 *
	 * <p>Public so a caller that needs an answer now — {@code session/new} deciding whether to fail —
	 * can force a read rather than wait out a poll interval.
	 */
	public void poll() {
		if (!Files.isDirectory(directory)) {
			return;
		}
		for (Path file : recentFiles()) {
			readNewLines(file);
		}
	}

	/**
	 * The notices seen so far, oldest first.
	 *
	 * <p>Bounded, and a snapshot: an application reading this while the agent is writing sees a
	 * consistent list rather than one that changes underneath it.
	 */
	public List<AgentNotice> notices() {
		return List.copyOf(notices);
	}

	/**
	 * Waits for a notice about {@code subject}, up to {@code timeout}.
	 *
	 * <p>The notice and the {@code session/new} reply are written at almost the same moment and in no
	 * guaranteed order, so a caller that wants to act on one has to be willing to wait briefly. Polls
	 * rather than waits on a signal because the underlying source is a file: there is nothing to
	 * signal on.
	 */
	public java.util.Optional<AgentNotice> awaitNotice(String subject, Duration timeout) {
		long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
		while (true) {
			java.util.Optional<AgentNotice> found = notices.stream().filter(n -> n.concerns(subject)).findFirst();
			if (found.isPresent() || System.nanoTime() >= deadline) {
				return found;
			}
			poll();
			if (notices.stream().anyMatch(n -> n.concerns(subject))) {
				continue;
			}
			try {
				Thread.sleep(Math.min(100, Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis())));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return java.util.Optional.empty();
			}
		}
	}

	private List<Path> recentFiles() {
		try (Stream<Path> found = Files.walk(directory, 4)) {
			return found.filter(Files::isRegularFile).filter(this::writtenSinceStart).limit(MAX_FILES).toList();
		}
		catch (IOException ex) {
			logger.debug("Could not list the agent's log directory {}", directory, ex);
			return List.of();
		}
	}

	/**
	 * Whether this file belongs to the run being watched.
	 *
	 * <p>A file already being appended to when this started counts: an agent that is restarted into
	 * an existing log would otherwise be watched in silence. The modification time is coarse, so the
	 * window is generous by a second on purpose — a missed warning is the failure this class exists
	 * to prevent, and an extra line from a neighbouring process is merely noise.
	 */
	private boolean writtenSinceStart(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis() >= startedAt - 1000;
		}
		catch (IOException ex) {
			return false;
		}
	}

	private void readNewLines(Path file) {
		List<String> lines = new ArrayList<>();
		synchronized (offsets) {
			long from = offsets.getOrDefault(file, 0L);
			try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
				if (handle.length() < from) {
					// Truncated or replaced under us; start again rather than read from a stale offset.
					from = 0;
				}
				handle.seek(from);
				String line;
				while ((line = handle.readLine()) != null) {
					// RandomAccessFile.readLine is bytes-as-latin1 by contract; the logs are UTF-8.
					lines.add(new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
				}
				offsets.put(file, handle.getFilePointer());
			}
			catch (IOException ex) {
				logger.debug("Could not read the agent's log file {}", file, ex);
				return;
			}
		}
		lines.forEach(this::interpret);
	}

	private void interpret(String line) {
		if (line == null || line.isBlank()) {
			return;
		}
		// Redacted before an adapter sees it: an agent's log quotes the URLs it was given, and one of
		// those can carry a token in its query string.
		interpret.apply(SecretRedactor.redact(line)).ifPresent(this::record);
	}

	private void record(AgentNotice notice) {
		if (notices.stream().anyMatch(seen -> seen.equals(notice))) {
			return;
		}
		notices.addLast(notice);
		while (notices.size() > KEPT_NOTICES) {
			notices.pollFirst();
		}
		if (notice.severity() == AgentNotice.Severity.ERROR) {
			logger.error("The agent reported: {}", notice);
		}
		else {
			logger.warn("The agent reported: {}", notice);
		}
		try {
			onNotice.accept(notice);
		}
		catch (RuntimeException ex) {
			logger.debug("A notice listener failed", ex);
		}
	}

	@Override
	public void close() {
		running.set(false);
		Thread current = thread;
		if (current != null) {
			current.interrupt();
		}
	}
}
