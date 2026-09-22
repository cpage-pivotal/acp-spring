package org.springaicommunity.acp.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.config.Validation;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Answers the {@code terminal/*} methods by running commands inside the workspace.
 *
 * <p>Four things keep this from being a remote shell with extra steps. The command must be
 * permitted by {@link TerminalAccess}; its working directory goes through {@link WorkspaceJail},
 * so an agent cannot run {@code rm} one directory up; output is captured into a bounded buffer and
 * reported {@code truncated} rather than growing without limit; and every process this class
 * started is killed when it is closed, so a turn that abandons a terminal does not leak one.
 *
 * <p>Output is drained on a virtual thread per terminal, which is not optional: a child whose
 * stdout pipe nobody reads blocks as soon as the OS buffer fills, and the symptom is an agent
 * waiting forever on a command that looked like it should have finished.
 */
public final class WorkspaceTerminals implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(WorkspaceTerminals.class);

	private final WorkspaceJail jail;

	private final TerminalAccess access;

	private final Map<String, Terminal> terminals = new ConcurrentHashMap<>();

	private final AtomicLong ids = new AtomicLong();

	public WorkspaceTerminals(WorkspaceJail jail, TerminalAccess access) {
		this.jail = jail;
		this.access = access == null ? TerminalAccess.disabled() : access;
	}

	public Mono<AcpSchema.CreateTerminalResponse> create(AcpSchema.CreateTerminalRequest request) {
		return Mono.fromCallable(() -> {
			if (!access.enabled()) {
				throw new WorkspaceAccessException("This client does not lend the agent a terminal");
			}
			if (!access.permits(request.command())) {
				throw new WorkspaceAccessException(
						"Command '" + request.command() + "' is not in this client's terminal allowlist");
			}
			if (terminals.size() >= access.maxConcurrent()) {
				throw new WorkspaceAccessException(
						"This client already has " + terminals.size() + " terminals open");
			}
			Path cwd = jail.directory(request.cwd());
			long limit = request.outputByteLimit() == null ? access.outputByteLimit()
					: Math.min(request.outputByteLimit(), access.outputByteLimit());

			String id = "term-" + ids.incrementAndGet();
			Terminal terminal = Terminal.start(id, request, cwd, limit);
			terminals.put(id, terminal);
			logger.debug("Started terminal {} running '{}' in {}", id, request.command(), cwd);
			return new AcpSchema.CreateTerminalResponse(id);
		}).subscribeOn(Schedulers.boundedElastic());
	}

	public Mono<AcpSchema.TerminalOutputResponse> output(AcpSchema.TerminalOutputRequest request) {
		return Mono.fromCallable(() -> require(request.terminalId()).snapshot());
	}

	/**
	 * Waits for the command to finish.
	 *
	 * <p>Reactive rather than blocking on purpose: the agent may keep talking while a build runs,
	 * and a blocked handler thread would hold up everything else on the connection. The timeout is
	 * the client's, not the agent's — an agent that asked to wait forever would otherwise pin a
	 * process for the life of the JVM.
	 */
	public Mono<AcpSchema.WaitForTerminalExitResponse> waitForExit(AcpSchema.WaitForTerminalExitRequest request) {
		Terminal terminal = require(request.terminalId());
		return Mono.fromFuture(terminal.process.onExit()).timeout(access.commandTimeout())
				.map(exited -> new AcpSchema.WaitForTerminalExitResponse(exited.exitValue(), null))
				.onErrorResume(java.util.concurrent.TimeoutException.class, ex -> {
					logger.warn("Terminal {} exceeded {}; killing it", terminal.id, access.commandTimeout());
					terminal.kill();
					return Mono.just(new AcpSchema.WaitForTerminalExitResponse(null, "SIGKILL"));
				});
	}

	public Mono<AcpSchema.KillTerminalCommandResponse> kill(AcpSchema.KillTerminalCommandRequest request) {
		return Mono.fromCallable(() -> {
			require(request.terminalId()).kill();
			return new AcpSchema.KillTerminalCommandResponse();
		});
	}

	public Mono<AcpSchema.ReleaseTerminalResponse> release(AcpSchema.ReleaseTerminalRequest request) {
		return Mono.fromCallable(() -> {
			Terminal terminal = terminals.remove(request.terminalId());
			if (terminal != null) {
				terminal.kill();
			}
			return new AcpSchema.ReleaseTerminalResponse();
		});
	}

	/** How many terminals are currently held. Exposed for tests and diagnostics. */
	public int openCount() {
		return terminals.size();
	}

	@Override
	public void close() {
		terminals.values().forEach(Terminal::kill);
		terminals.clear();
	}

	private Terminal require(String terminalId) {
		Terminal terminal = terminals.get(terminalId);
		if (terminal == null) {
			throw new WorkspaceAccessException("No such terminal '" + terminalId + "'");
		}
		return terminal;
	}

	/** One running command and the bounded buffer its output goes into. */
	private static final class Terminal {

		private final String id;

		private final Process process;

		private final long byteLimit;

		private final StringBuilder output = new StringBuilder();

		private volatile boolean truncated;

		private Terminal(String id, Process process, long byteLimit) {
			this.id = id;
			this.process = process;
			this.byteLimit = byteLimit;
		}

		private static Terminal start(String id, AcpSchema.CreateTerminalRequest request, Path cwd, long byteLimit) {
			List<String> command = new java.util.ArrayList<>();
			command.add(request.command());
			if (request.args() != null) {
				command.addAll(request.args());
			}

			ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
			if (request.env() != null) {
				request.env().forEach(variable -> {
					Validation.requireEnvName(variable.name());
					builder.environment().put(variable.name(), variable.value() == null ? "" : variable.value());
				});
			}
			// The agent asked for one stream of output, and interleaving is what a terminal does.
			builder.redirectErrorStream(true);

			Process started;
			try {
				started = builder.start();
			}
			catch (IOException ex) {
				throw new WorkspaceAccessException("Could not start '" + request.command() + "'", ex);
			}
			Terminal terminal = new Terminal(id, started, byteLimit);
			terminal.drain();
			return terminal;
		}

		private void drain() {
			Thread.ofVirtual().name("acp-terminal-" + id).start(() -> {
				try (InputStream in = process.getInputStream()) {
					byte[] buffer = new byte[8192];
					int read;
					while ((read = in.read(buffer)) >= 0) {
						append(new String(buffer, 0, read, StandardCharsets.UTF_8));
					}
				}
				catch (IOException ex) {
					logger.debug("Terminal {} output stream closed: {}", id, ex.getMessage());
				}
			});
		}

		private synchronized void append(String chunk) {
			long remaining = byteLimit - output.length();
			if (remaining <= 0) {
				truncated = true;
				return;
			}
			if (chunk.length() > remaining) {
				output.append(chunk, 0, (int) remaining);
				truncated = true;
				return;
			}
			output.append(chunk);
		}

		private synchronized AcpSchema.TerminalOutputResponse snapshot() {
			AcpSchema.TerminalExitStatus status = process.isAlive() ? null
					: new AcpSchema.TerminalExitStatus(process.exitValue(), null);
			return new AcpSchema.TerminalOutputResponse(output.toString(), truncated, status);
		}

		private void kill() {
			if (!process.isAlive()) {
				return;
			}
			process.destroy();
			try {
				if (!process.waitFor(2, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				process.destroyForcibly();
			}
		}
	}
}
