package org.tanzu.acp.client;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.event.SessionUpdateDecoder;
import org.tanzu.acp.permission.PermissionPolicy;
import org.tanzu.acp.observation.AgentObservations;
import org.tanzu.acp.process.AgentProcessSupervisor;
import org.tanzu.acp.protocol.AcpProtocol;
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.session.SessionRegistry;
import org.tanzu.acp.transport.WebSocketAgentTransport;
import org.tanzu.acp.workspace.WorkspaceFileSystem;
import org.tanzu.acp.workspace.WorkspaceTerminals;
import org.tanzu.acp.turn.SessionUpdateRouter;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;

/**
 * Starts an agent and hands back a connected {@link AgentClient}.
 *
 * <p>The client capabilities declared here follow the settings, and default to lending the agent
 * nothing. This library runs server-side, where an agent asking to read a file or open a terminal
 * is asking a process with no human supervising it. What is not lent is declared unsupported rather
 * than advertised and then refused — an agent that knows it cannot read files plans differently
 * from one that discovers it mid-turn — and the handler for a capability is registered only when
 * that capability is on, so there is one source of truth rather than two that can disagree.
 *
 * <p>Nothing here names a runtime. Everything vendor-specific is behind {@link AgentRuntime}: what
 * to launch, what to write before launching, and what the agent calls the options a client may set.
 */
public final class AgentClientFactory {

	private static final Logger logger = LoggerFactory.getLogger(AgentClientFactory.class);

	private static final String CLIENT_NAME = "spring-acp";

	private static final String SESSION_UPDATE = "session/update";

	private AgentClientFactory() {
	}

	/** Provisions, launches and connects the runtime named by {@code settings}. */
	public static AgentClient create(AgentRuntime runtime, AgentSettings settings) {
		return create(runtime, settings, AgentObservations.NONE);
	}

	/** Same, with every turn this client runs reported to {@code observations}. */
	public static AgentClient create(AgentRuntime runtime, AgentSettings settings, AgentObservations observations) {
		runtime.provision(settings);

		AgentDiagnostics diagnostics = new AgentDiagnostics();
		return switch (runtime.launch(settings)) {
			case AgentLaunchSpec.Stdio stdio ->
				connect(runtime, settings, stdioTransport(stdio, diagnostics), diagnostics, Liveness.unknowable(),
						() -> {
						}, observations);
			case AgentLaunchSpec.WebSocket served -> connectServed(runtime, settings, served, diagnostics,
					observations);
		};
	}

	/**
	 * Starts the agent's server if this library owns it, then connects over a WebSocket.
	 *
	 * <p>The order is the point. A served agent that rejects its configuration exits about a second
	 * after it is spawned, so connecting first would produce "connection refused" for a problem the
	 * process already explained on its own output; {@link AgentProcessSupervisor} waits for
	 * readiness and reports what the agent said instead.
	 */
	private static AgentClient connectServed(AgentRuntime runtime, AgentSettings settings,
			AgentLaunchSpec.WebSocket served, AgentDiagnostics diagnostics, AgentObservations observations) {
		AgentProcessSupervisor supervisor = served.process() == null ? null
				: new AgentProcessSupervisor(runtime.id(), served.process(), settings.pool().maxRestarts());
		if (supervisor != null) {
			supervisor.start();
		}

		WebSocketAgentTransport transport = new WebSocketAgentTransport(served.uri(), served.headers());
		java.util.concurrent.atomic.AtomicBoolean connected = new java.util.concurrent.atomic.AtomicBoolean(true);
		transport.onDisconnect(() -> connected.set(false));
		// A restarted server is a new process behind the same address: this connection is stale
		// whatever the socket thinks, and the pool should replace the client rather than reuse it.
		if (supervisor != null) {
			supervisor.addRestartListener(() -> connected.set(false));
		}

		Liveness liveness = new Liveness(true,
				() -> connected.get() && (supervisor == null || supervisor.isHealthy()));
		Runnable onClose = supervisor == null ? () -> {
		} : supervisor::close;

		try {
			return connect(runtime, settings, transport, diagnostics, liveness, onClose, observations);
		}
		catch (RuntimeException ex) {
			onClose.run();
			throw ex;
		}
	}

	/**
	 * Whether this connection can say if it is still usable, and the answer if it can.
	 *
	 * <p>Split in two because "yes" and "I cannot tell" are different facts and a pool that
	 * confused them would either never replace a dead agent or replace a healthy one. A stdio
	 * connection genuinely cannot tell: {@code StdioAcpClientTransport} owns the child process in a
	 * private field, never reports a transport exception to the handler it accepts, and exposes no
	 * liveness of any kind, so the only evidence of a dead agent is a request that does not come
	 * back. A served agent has a socket that closes and a supervisor that watches the process.
	 */
	private record Liveness(boolean knowable, java.util.function.BooleanSupplier alive) {

		static Liveness unknowable() {
			return new Liveness(false, () -> true);
		}
	}

	/**
	 * Connects to an agent already reachable over {@code launched}.
	 *
	 * <p>The seam between starting an agent and talking to one. A test drives a client over an
	 * in-memory transport through here, and it is where a runtime that attaches to something it did
	 * not spawn — an agent on a WebSocket, a sidecar — will come in.
	 */
	public static AgentClient connect(AgentRuntime runtime, AgentSettings settings, AcpClientTransport launched) {
		return connect(runtime, settings, launched, AgentObservations.NONE);
	}

	/** Same, reporting turns to {@code observations}. */
	public static AgentClient connect(AgentRuntime runtime, AgentSettings settings, AcpClientTransport launched,
			AgentObservations observations) {
		return connect(runtime, settings, launched, new AgentDiagnostics(), Liveness.unknowable(), () -> {
		}, observations);
	}

	private static AgentClient connect(AgentRuntime runtime, AgentSettings settings, AcpClientTransport launched,
			AgentDiagnostics diagnostics, Liveness liveness, Runnable onTransportClose,
			AgentObservations observations) {
		SessionConfigRecorder recorder = new SessionConfigRecorder();
		AcpClientTransport transport = recorder.wrap(launched);
		SessionUpdateRouter router = new SessionUpdateRouter();

		WorkspaceFileSystem files = new WorkspaceFileSystem(settings.workspace(), settings.filesystem());
		WorkspaceTerminals terminals = new WorkspaceTerminals(files.jail(), settings.terminal());

		AcpClient.AsyncSpec spec = AcpClient.async(transport).requestTimeout(settings.timeout())
				.clientCapabilities(capabilities(settings))
				// Deliberately not sessionUpdateConsumer: see SessionUpdateDecoder for why a raw handler.
				.notificationHandler(SESSION_UPDATE, params -> {
					SessionUpdateDecoder.decode(params, transport).ifPresent(router::accept);
					return Mono.empty();
				}).requestPermissionHandler(request -> handlePermission(runtime, settings.permissions(), request));

		if (settings.filesystem().read()) {
			spec = spec.readTextFileHandler(files::read);
		}
		if (settings.filesystem().write()) {
			spec = spec.writeTextFileHandler(files::write);
		}
		if (settings.terminal().enabled()) {
			spec = spec.createTerminalHandler(terminals::create).terminalOutputHandler(terminals::output)
					.waitForTerminalExitHandler(terminals::waitForExit).killTerminalHandler(terminals::kill)
					.releaseTerminalHandler(terminals::release);
		}

		AcpAsyncClient acp = spec.build();

		AcpSchema.InitializeResponse initialized;
		int protocolVersion;
		int offered = settings.protocol().maxVersion();
		try {
			initialized = acp
					.initialize(new AcpSchema.InitializeRequest(offered, capabilities(settings),
							new AcpSchema.Implementation(CLIENT_NAME, version()), null))
					.block(settings.timeout());
			if (initialized == null) {
				throw new AgentClientException("Agent '" + runtime.id() + "' did not complete initialization");
			}
		}
		catch (RuntimeException ex) {
			// Collected before the close, not after: closing disposes the scheduler that delivers the
			// agent's stderr, so a complaint still in flight is lost the moment the transport goes down.
			String reported = diagnostics.settledSummary();
			terminals.close();
			closeQuietly(acp);
			onTransportClose.run();
			throw new AgentClientException("Failed to initialize runtime '" + runtime.id() + "'" + reported, ex);
		}

		// Outside the catch on purpose. A version nobody can speak is a handshake that succeeded and
		// produced something unusable, not a handshake that failed, and wrapping it in "failed to
		// initialize" would bury the one sentence that says what to change. Reconciled rather than
		// read, because goose 1.51 answers whatever version it is offered — see AcpProtocol.
		try {
			protocolVersion = AcpProtocol.negotiated(runtime.id(), offered, initialized.protocolVersion(),
					settings.protocol().strict());
		}
		catch (RuntimeException ex) {
			terminals.close();
			closeQuietly(acp);
			onTransportClose.run();
			throw ex;
		}
		logger.info("Connected to {} {} over ACP v{}",
				initialized.agentInfo() == null ? runtime.id() : initialized.agentInfo().name(),
				initialized.agentInfo() == null ? "" : initialized.agentInfo().version(), protocolVersion);

		return new DefaultAgentClient(acp, runtime, settings, new SessionRegistry(), router, recorder, initialized,
				protocolVersion, observations, liveness.knowable() ? liveness.alive() : null, () -> {
					terminals.close();
					onTransportClose.run();
				});
	}

	private static AcpClientTransport stdioTransport(AgentLaunchSpec.Stdio stdio, AgentDiagnostics diagnostics) {
		StdioAcpClientTransport transport = new StdioAcpClientTransport(stdio.toAgentParameters());
		// An undrained stderr pipe eventually blocks the child process.
		transport.setStdErrorHandler(line -> {
			diagnostics.record(line);
			logger.debug("[agent] {}", line);
		});
		return transport;
	}

	/**
	 * Keeps the agent's last few lines of stderr, so a failed handshake can say why.
	 *
	 * <p>Worth the class. When an agent refuses to start, the reason is on its stderr and nowhere else
	 * — "Configuration is invalid … Expected \"manual\" | \"auto\" | \"disabled\", got false", or
	 * "Authentication required" — while the protocol failure this library sees is a handshake that
	 * never completed. Logging stderr at debug and then reporting only the handshake leaves an operator
	 * with a message that names the runtime and nothing else, for a problem that is entirely
	 * explicable. Bounded because an agent that fails noisily should not put its whole log in an
	 * exception message.
	 */
	private static final class AgentDiagnostics {

		private static final int KEPT_LINES = 10;

		/** How long to let a failing agent finish explaining itself. */
		private static final Duration SETTLE = Duration.ofMillis(500);

		/** Agents colour their errors for a terminal; an exception message is not one. */
		private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern
				.compile("\u001B\\[[0-9;]*[a-zA-Z]");

		private final java.util.Deque<String> lines = new java.util.concurrent.ConcurrentLinkedDeque<>();

		private void record(String line) {
			if (line == null || line.isBlank()) {
				return;
			}
			String plain = ANSI.matcher(line).replaceAll("").strip();
			if (plain.isEmpty()) {
				return;
			}
			lines.addLast(plain);
			while (lines.size() > KEPT_LINES) {
				lines.pollFirst();
			}
		}

		/**
		 * The agent's complaint, waiting briefly for it to arrive.
		 *
		 * <p>The SDK drains the child's stderr on its own scheduler, so whether a line written
		 * microseconds before the process died has been dispatched by the time the handshake gives up
		 * is a race — and one that resolves the wrong way often enough to matter, since the whole point
		 * is to have the explanation when things go wrong. Waiting costs nothing here: this path has
		 * already failed and is about to throw. An agent that said nothing costs the full half second,
		 * once, on the way to an error.
		 */
		private String settledSummary() {
			long deadline = System.nanoTime() + SETTLE.toNanos();
			while (lines.isEmpty() && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			return lines.isEmpty() ? "" : "; the agent reported: " + String.join(" | ", lines);
		}
	}

	/**
	 * Answers a {@code session/request_permission}. An unanswered request stalls the turn forever,
	 * so this must produce an outcome for every request, including one the policy declines to
	 * choose for.
	 */
	private static Mono<AcpSchema.RequestPermissionResponse> handlePermission(AgentRuntime runtime,
			PermissionPolicy policy, AcpSchema.RequestPermissionRequest request) {
		return Mono.fromSupplier(() -> {
			Optional<String> toolName = request.toolCall() == null ? Optional.<String>empty()
					: runtime.toolNameOf(request.toolCall());
			var chosen = policy.decide(toolName, request.options());
			logger.debug("Permission for tool {} -> {}", toolName.orElse("(unknown)"),
					chosen.map(AcpSchema.PermissionOption::optionId).orElse("cancelled"));
			return new AcpSchema.RequestPermissionResponse(chosen
					.<AcpSchema.RequestPermissionOutcome>map(o -> new AcpSchema.PermissionSelected(o.optionId()))
					.orElseGet(AcpSchema.PermissionCancelled::new));
		});
	}

	/**
	 * What this client offers the agent, which is nothing unless the application said otherwise.
	 *
	 * <p>Declared identically on the builder and in the {@code initialize} request because the SDK
	 * uses the first to decide which inbound methods it will route and the second is what the agent
	 * reads; they are two halves of one statement and disagreeing would mean advertising something
	 * no handler answers.
	 */
	private static AcpSchema.ClientCapabilities capabilities(AgentSettings settings) {
		return new AcpSchema.ClientCapabilities(
				new AcpSchema.FileSystemCapability(settings.filesystem().read(), settings.filesystem().write()),
				settings.terminal().enabled(), null, null);
	}

	private static String version() {
		String implementation = AgentClientFactory.class.getPackage().getImplementationVersion();
		return implementation == null ? "0.1.0-SNAPSHOT" : implementation;
	}

	private static void closeQuietly(AcpAsyncClient acp) {
		try {
			acp.closeGracefully().block(Duration.ofSeconds(5));
		}
		catch (RuntimeException ignored) {
			// Already failing; nothing useful to add.
		}
	}
}
