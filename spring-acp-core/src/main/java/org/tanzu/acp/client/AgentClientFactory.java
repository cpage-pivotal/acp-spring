package org.tanzu.acp.client;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.config.AgentSettings;
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
 */
public final class AgentClientFactory {

	private static final Logger logger = LoggerFactory.getLogger(AgentClientFactory.class);

	private static final String CLIENT_NAME = "spring-acp";

	private static final int PROTOCOL_VERSION = 1;

	private AgentClientFactory() {
	}

	public static AgentClient create(AgentRuntime runtime, AgentSettings settings) {
		runtime.provision(settings);

		AgentLaunchSpec spec = runtime.launch(settings);
		AcpClientTransport transport = switch (spec) {
			case AgentLaunchSpec.Stdio stdio -> stdioTransport(stdio);
		};

		SessionUpdateRouter router = new SessionUpdateRouter();
		AcpAsyncClient acp = AcpClient.async(transport).requestTimeout(settings.timeout())
				.clientCapabilities(headlessCapabilities()).sessionUpdateConsumer(notification -> {
					router.accept(notification);
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
			closeQuietly(acp);
			throw new AgentClientException("Failed to initialize runtime '" + runtime.id() + "'", ex);
		}

		return new DefaultAgentClient(acp, runtime, settings, new SessionRegistry(), router, null);
	}

	private static AcpClientTransport stdioTransport(AgentLaunchSpec.Stdio stdio) {
		StdioAcpClientTransport transport = new StdioAcpClientTransport(stdio.toAgentParameters());
		// An undrained stderr pipe eventually blocks the child process.
		transport.setStdErrorHandler(line -> logger.debug("[agent] {}", line));
		return transport;
	}

	/**
	 * Answers a {@code session/request_permission}. An unanswered request stalls the turn forever,
	 * so this must produce an outcome for every request, including one the policy declines to
	 * choose for.
	 */
	private static Mono<AcpSchema.RequestPermissionResponse> handlePermission(AgentRuntime runtime,
			PermissionPolicy policy, AcpSchema.RequestPermissionRequest request) {
		return Mono.fromSupplier(() -> {
			var toolName = request.toolCall() == null ? java.util.Optional.<String>empty()
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
