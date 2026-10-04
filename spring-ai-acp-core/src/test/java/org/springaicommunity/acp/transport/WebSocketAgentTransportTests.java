package org.springaicommunity.acp.transport;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * This transport against the SDK's own agent server, which serves the WebSocket upgrade
 * that {@code goose serve} does. A real socket, no agent binary.
 */
class WebSocketAgentTransportTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	/** Never reached by a passing test: the point is to fail well before it. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

	private static final String HANG = "/hang";

	private StreamableHttpAcpAgentTransport server;

	private AcpAsyncClient client;

	@AfterEach
	void close() {
		if (client != null) {
			client.close();
		}
		if (server != null) {
			server.closeGracefully().block(TIMEOUT);
		}
	}

	/**
	 * A permission answer is a response the transport emits from its inbound thread,
	 * while prompts go out from callers' threads on the same sink. Before both took the
	 * serialising path a collision dropped the answer and that turn never ended; with
	 * many turns asking at once, every one must still end.
	 *
	 * <p>
	 * Each prompt is subscribed on a worker thread: chained straight off the previous
	 * response it would be sent from the inbound thread itself, which cannot collide with
	 * that thread's own emissions. Even so the window is a few instructions wide, and
	 * this test did not catch the bare {@code tryEmitNext} on a development machine in
	 * eight runs. It is the path under load, not a reproduction; the SDK pins its
	 * contention tests to one CPU to get one.
	 */
	@Test
	void everyConcurrentTurnGetsItsPermissionAnswerThrough() throws IOException {
		server = start();
		client = client(new WebSocketAgentTransport(endpoint(), Map.of("X-Secret-Key", "s3cret")));
		client.initialize().block(TIMEOUT);

		int sessions = 16;
		int turnsEach = 50;
		List<String> ids = Flux.range(0, sessions)
			.concatMap(i -> client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())))
			.map(AcpSchema.NewSessionResponse::sessionId)
			.collectList()
			.block(TIMEOUT);
		Scheduler senders = Schedulers.newParallel("prompt-sender", sessions);
		try {
			List<AcpSchema.PromptResponse> responses = Flux.fromIterable(ids)
				.flatMap(id -> Flux.range(0, turnsEach)
					.concatMap(turn -> client
						.prompt(new AcpSchema.PromptRequest(id,
								List.of(new AcpSchema.TextContent("permission please"))))
						.subscribeOn(senders)), sessions)
				.collectList()
				.block(TIMEOUT);

			assertThat(responses).hasSize(sessions * turnsEach)
				.allSatisfy(response -> assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN));
		}
		finally {
			senders.dispose();
		}
	}

	/**
	 * A request in flight when the agent goes away fails as soon as the socket closes,
	 * not when the request timeout runs out, and the disconnect callback still fires.
	 */
	@Test
	void aRequestInFlightFailsAsSoonAsTheAgentGoesAway() throws Exception {
		server = start();
		CountDownLatch disconnected = new CountDownLatch(1);
		WebSocketAgentTransport transport = new WebSocketAgentTransport(endpoint(), Map.of());
		transport.onDisconnect(disconnected::countDown);
		client = client(transport);
		client.initialize().block(TIMEOUT);

		Mono<AcpSchema.NewSessionResponse> hung = client.newSession(new AcpSchema.NewSessionRequest(HANG, List.of()))
			.cache();
		hung.subscribe(response -> {
		}, error -> {
		});
		server.closeGracefully().block(TIMEOUT);

		// block() would throw too once TIMEOUT ran out, so the failure must be the
		// session's own, and prompt.
		long started = System.nanoTime();
		Throwable failure = catchThrowable(() -> hung.block(TIMEOUT));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
		assertThat(failure).isNotNull().hasMessageNotContaining("Timeout on blocking read");
		assertThat(disconnected.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
		transport.awaitTermination().onErrorComplete().block(TIMEOUT);
	}

	@Test
	void terminationCompletesWhenTheClientClosesTheTransport() throws IOException {
		server = start();
		WebSocketAgentTransport transport = new WebSocketAgentTransport(endpoint(), Map.of());
		client = client(transport);
		client.initialize().block(TIMEOUT);

		client.closeGracefully().block(TIMEOUT);

		transport.awaitTermination().block(TIMEOUT);
	}

	private static AcpAsyncClient client(WebSocketAgentTransport transport) {
		return AcpClient.async(transport)
			.requestTimeout(REQUEST_TIMEOUT)
			.requestPermissionHandler(request -> Mono
				.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow"))))
			.build();
	}

	private StreamableHttpAcpAgentTransport start() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		AtomicInteger sessions = new AtomicInteger();
		AcpAgentFactory agents = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.requestTimeout(REQUEST_TIMEOUT)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(AcpSchema.LATEST_PROTOCOL_VERSION,
					new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> HANG.equals(request.cwd()) ? Mono.never()
					: Mono.just(new AcpSchema.NewSessionResponse("session-" + sessions.incrementAndGet(), null, null)))
			.promptHandler((request, context) -> context.askPermission("fixture permission")
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build());
		StreamableHttpAcpAgentTransport started = new StreamableHttpAcpAgentTransport(port,
				StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH, AcpJsonMapper.createDefault(), agents);
		started.start().block(TIMEOUT);
		return started;
	}

	private URI endpoint() {
		return URI.create("ws://127.0.0.1:" + server.getPort() + StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH);
	}

}
