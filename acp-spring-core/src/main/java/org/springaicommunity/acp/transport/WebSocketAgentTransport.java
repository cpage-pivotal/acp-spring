package org.springaicommunity.acp.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.config.Validation;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * An ACP transport over a WebSocket that can carry authentication headers.
 *
 * <p>The SDK ships {@code WebSocketAcpClientTransport} and it would have done, but for one thing:
 * it builds its socket from {@code httpClient.newWebSocketBuilder()} inside {@code connect} and
 * never exposes the builder, so there is no way to add a header. Goose's own ACP server refuses
 * every connection that arrives without {@code X-Secret-Key} — the alternative being
 * {@code --dangerously-unauthenticated}, which is named that for a reason — so a header-capable
 * transport is the difference between supporting {@code goose serve} and not. Everything else here
 * follows the SDK's implementation deliberately, so that the day it grows a header hook this class
 * can be deleted rather than reconciled.
 *
 * <p>Two additions that the stdio case does not need. A keepalive ping, because an idle socket
 * between a JVM and a sidecar will be closed by something in between eventually and a turn that
 * starts after that would fail rather than reconnect. And a disconnect callback, because a dropped
 * socket is how this transport learns its agent is gone — a subprocess announces the same thing by
 * closing its pipes, but a socket can go quiet without anyone saying so.
 */
public final class WebSocketAgentTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketAgentTransport.class);

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

	private static final Duration PING_INTERVAL = Duration.ofSeconds(30);

	private final URI uri;

	private final Map<String, String> headers;

	private final AcpJsonMapper jsonMapper;

	private final HttpClient httpClient;

	private final ScheduledExecutorService keepalive;

	private final Sinks.Many<JSONRPCMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

	private final Sinks.Many<JSONRPCMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();

	private final Sinks.One<Void> ready = Sinks.one();

	private final Scheduler outboundScheduler;

	private final AtomicBoolean closing = new AtomicBoolean();

	private final AtomicBoolean connected = new AtomicBoolean();

	private volatile WebSocket webSocket;

	private volatile ScheduledFuture<?> pingTask;

	private volatile Consumer<Throwable> exceptionHandler = error -> logger.error("Transport error", error);

	private volatile Runnable onDisconnect = () -> {
	};

	public WebSocketAgentTransport(URI uri, Map<String, String> headers) {
		this(uri, headers, AcpJsonMapper.createDefault());
	}

	public WebSocketAgentTransport(URI uri, Map<String, String> headers, AcpJsonMapper jsonMapper) {
		this.uri = uri;
		this.headers = headers == null ? Map.of() : Map.copyOf(headers);
		this.headers.forEach((name, value) -> {
			Validation.requireHeaderName(name);
			Validation.requireHeaderValue(name, value);
		});
		this.jsonMapper = jsonMapper;
		this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).executor(
				Executors.newCachedThreadPool(daemon("acp-ws"))).build();
		this.keepalive = Executors.newSingleThreadScheduledExecutor(daemon("acp-ws-keepalive"));
		this.outboundScheduler = Schedulers
				.fromExecutorService(Executors.newSingleThreadExecutor(daemon("acp-ws-outbound")), "ws-outbound");
	}

	/** Called once when the socket drops, however it drops. */
	public void onDisconnect(Runnable callback) {
		this.onDisconnect = callback == null ? () -> {
		} : callback;
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		if (!connected.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already connected to " + uri));
		}
		return Mono.fromFuture(() -> {
			routeInbound(handler);
			WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
			headers.forEach(builder::header);
			return builder.buildAsync(uri, new InboundListener());
		}).doOnSuccess(socket -> {
			this.webSocket = socket;
			startOutbound();
			this.pingTask = keepalive.scheduleWithFixedDelay(this::ping, PING_INTERVAL.toMillis(),
					PING_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
			ready.tryEmitValue(null);
			logger.info("ACP WebSocket connected to {}", uri);
		}).doOnError(error -> {
			connected.set(false);
			exceptionHandler.accept(error);
		}).then();
	}

	private void routeInbound(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		inbound.asFlux().flatMap(message -> Mono.just(message).transform(handler))
				.doOnNext(response -> outbound.tryEmitNext(response))
				.doOnTerminate(outbound::tryEmitComplete).subscribe();
	}

	private void startOutbound() {
		outbound.asFlux().publishOn(outboundScheduler).subscribe(message -> {
			WebSocket socket = webSocket;
			if (socket == null || closing.get()) {
				return;
			}
			try {
				// join() on a single-threaded scheduler is what serializes sends: the JDK's WebSocket
				// forbids a second sendText before the first has completed.
				socket.sendText(jsonMapper.writeValueAsString(message), true).join();
			}
			catch (Exception ex) {
				if (!closing.get()) {
					logger.error("Failed to send an ACP message over {}", uri, ex);
					exceptionHandler.accept(ex);
				}
			}
		});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return ready.asMono().then(Mono.fromRunnable(() -> outbound.emitNext(message,
				Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)))));
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			if (!closing.compareAndSet(false, true)) {
				return;
			}
			ScheduledFuture<?> task = pingTask;
			if (task != null) {
				task.cancel(false);
			}
			inbound.tryEmitComplete();
			outbound.tryEmitComplete();
			WebSocket socket = webSocket;
			if (socket != null && !socket.isOutputClosed()) {
				socket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
			}
			keepalive.shutdownNow();
			outboundScheduler.dispose();
		});
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.exceptionHandler = handler == null ? error -> {
		} : handler;
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return jsonMapper.convertValue(data, typeRef);
	}

	private void ping() {
		WebSocket socket = webSocket;
		if (socket == null || socket.isOutputClosed()) {
			return;
		}
		socket.sendPing(ByteBuffer.allocate(0)).exceptionally(error -> {
			logger.warn("ACP keepalive ping to {} failed: {}", uri, error.toString());
			dropped();
			return null;
		});
	}

	private void dropped() {
		if (closing.compareAndSet(false, true)) {
			onDisconnect.run();
		}
	}

	private static java.util.concurrent.ThreadFactory daemon(String name) {
		return runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		};
	}

	/** Reassembles partial frames and feeds whole messages to the inbound sink. */
	private final class InboundListener implements WebSocket.Listener {

		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket socket) {
			socket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				String message = buffer.toString();
				buffer.setLength(0);
				try {
					inbound.tryEmitNext(AcpSchema.deserializeJsonRpcMessage(jsonMapper, message));
				}
				catch (Exception ex) {
					if (!closing.get()) {
						logger.error("Could not decode an inbound ACP message from {}", uri, ex);
						exceptionHandler.accept(ex);
					}
				}
			}
			socket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
			logger.info("ACP WebSocket to {} closed: {} {}", uri, statusCode, reason);
			inbound.tryEmitComplete();
			dropped();
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket socket, Throwable error) {
			if (!closing.get()) {
				logger.warn("ACP WebSocket to {} failed: {}", uri, error.toString());
				exceptionHandler.accept(error);
			}
			inbound.tryEmitComplete();
			dropped();
		}
	}
}
