package org.tanzu.acp.client;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.event.SessionUpdateDecoder;
import org.tanzu.acp.permission.PermissionPolicy;
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.session.SessionRegistry;
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
 * <p>The client capabilities declared here are restrictive by design. This library runs
 * server-side, where an agent asking to read a file or open a terminal is asking a process with no
 * human supervising it. Filesystem and terminal access are therefore declared unsupported outright
 * rather than advertised and then refused — an agent that knows it cannot read files plans
 * differently from one that discovers it mid-turn.
 *
 * <p>Nothing here names a runtime. Everything vendor-specific is behind {@link AgentRuntime}: what
 * to launch, what to write before launching, and what the agent calls the options a client may set.
 */
public final class AgentClientFactory {

	private static final Logger logger = LoggerFactory.getLogger(AgentClientFactory.class);

	private static final String CLIENT_NAME = "spring-acp";

	private static final String SESSION_UPDATE = "session/update";

	private static final int PROTOCOL_VERSION = 1;

	private AgentClientFactory() {
	}

	/** Provisions, launches and connects the runtime named by {@code settings}. */
	public static AgentClient create(AgentRuntime runtime, AgentSettings settings) {
		runtime.provision(settings);

		AgentLaunchSpec spec = runtime.launch(settings);
		AgentDiagnostics diagnostics = new AgentDiagnostics();
		AcpClientTransport launched = switch (spec) {
			case AgentLaunchSpec.Stdio stdio -> stdioTransport(stdio, diagnostics);
		};
		return connect(runtime, settings, launched, diagnostics);
	}

	/**
	 * Connects to an agent already reachable over {@code launched}.
	 *
	 * <p>The seam between starting an agent and talking to one. A test drives a client over an
	 * in-memory transport through here, and it is where a runtime that attaches to something it did
	 * not spawn — an agent on a WebSocket, a sidecar — will come in.
	 */
	public static AgentClient connect(AgentRuntime runtime, AgentSettings settings, AcpClientTransport launched) {
		return connect(runtime, settings, launched, new AgentDiagnostics());
	}

	private static AgentClient connect(AgentRuntime runtime, AgentSettings settings, AcpClientTransport launched,
			AgentDiagnostics diagnostics) {
		SessionConfigRecorder recorder = new SessionConfigRecorder();
		AcpClientTransport transport = recorder.wrap(launched);
		SessionUpdateRouter router = new SessionUpdateRouter();

		AcpAsyncClient acp = AcpClient.async(transport).requestTimeout(settings.timeout())
				.clientCapabilities(headlessCapabilities())
				// Deliberately not sessionUpdateConsumer: see SessionUpdateDecoder for why a raw handler.
				.notificationHandler(SESSION_UPDATE, params -> {
					SessionUpdateDecoder.decode(params, transport).ifPresent(router::accept);
					return Mono.empty();
				}).requestPermissionHandler(request -> handlePermission(runtime, settings.permissions(), request))
				.build();

		try {
			AcpSchema.InitializeResponse initialized = acp
					.initialize(new AcpSchema.InitializeRequest(PROTOCOL_VERSION, headlessCapabilities(),
							new AcpSchema.Implementation(CLIENT_NAME, version()), null))
					.block(settings.timeout());
			if (initialized == null) {
				throw new AgentClientException("Agent '" + runtime.id() + "' did not complete initialization");
			}
			logger.info("Connected to {} {} over ACP v{}",
					initialized.agentInfo() == null ? runtime.id() : initialized.agentInfo().name(),
					initialized.agentInfo() == null ? "" : initialized.agentInfo().version(),
					initialized.protocolVersion());
		}
		catch (RuntimeException ex) {
			// Collected before the close, not after: closing disposes the scheduler that delivers the
			// agent's stderr, so a complaint still in flight is lost the moment the transport goes down.
			String reported = diagnostics.settledSummary();
			closeQuietly(acp);
			throw new AgentClientException("Failed to initialize runtime '" + runtime.id() + "'" + reported, ex);
		}

		return new DefaultAgentClient(acp, runtime, settings, new SessionRegistry(), router, recorder, null);
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

	private static AcpSchema.ClientCapabilities headlessCapabilities() {
		return new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(false, false), false, null, null);
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
