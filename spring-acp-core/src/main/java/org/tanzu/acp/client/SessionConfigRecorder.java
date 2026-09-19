package org.tanzu.acp.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;

/**
 * Recovers the {@code configOptions} an agent returns from {@code session/new}, which the SDK drops
 * before a client can see them.
 *
 * <p>The gap is in acp-core 0.17.0, not in the agents: {@code NewSessionResponse} models
 * {@code sessionId}, {@code modes} and {@code models} and ignores unknown properties, while
 * {@code ForkSessionResponse} and {@code SetSessionConfigOptionResponse} both model
 * {@code configOptions} — an oversight rather than a decision. All three runtimes this library
 * ships adapters for return the field, and {@code ConfigResolver} cannot do its job without it.
 *
 * <p>Rather than parse the wire, this decorates the transport at the one point where the raw payload
 * and its target type meet: {@code unmarshalFrom}. The SDK calls it once per response with the
 * untouched result map, so a decorator sees {@code configOptions} on its way past without knowing
 * anything about framing, ids or correlation. Options are decoded one at a time, so an option of a
 * type this SDK version has no record for costs that one option rather than all of them.
 *
 * <p>Delete this when {@code NewSessionResponse} models the field. The only thing outside needs to
 * know is {@link #configOptionsFor(String)}.
 */
public final class SessionConfigRecorder {

	private static final Logger logger = LoggerFactory.getLogger(SessionConfigRecorder.class);

	private static final TypeRef<AcpSchema.SessionConfigOption> OPTION = new TypeRef<>() {
	};

	private final Map<String, List<AcpSchema.SessionConfigOption>> bySession = new ConcurrentHashMap<>();

	/** Wraps {@code delegate} so that new sessions' config options are recorded as they arrive. */
	public AcpClientTransport wrap(AcpClientTransport delegate) {
		return new RecordingTransport(delegate);
	}

	/**
	 * The options the agent advertised when it created {@code sessionId}, or empty if it advertised
	 * none. Keyed by session id rather than held as "the last one seen", because two callers may open
	 * sessions at the same time.
	 */
	public List<AcpSchema.SessionConfigOption> configOptionsFor(String sessionId) {
		return bySession.getOrDefault(sessionId, List.of());
	}

	public void forget(String sessionId) {
		bySession.remove(sessionId);
	}

	private void record(AcpClientTransport transport, String sessionId, Object rawOptions) {
		if (sessionId == null || !(rawOptions instanceof List<?> raw) || raw.isEmpty()) {
			return;
		}
		List<AcpSchema.SessionConfigOption> options = new ArrayList<>(raw.size());
		for (Object element : raw) {
			try {
				options.add(transport.unmarshalFrom(element, OPTION));
			}
			catch (RuntimeException ex) {
				logger.debug("Ignoring a session config option this SDK version cannot model: {}", ex.getMessage());
			}
		}
		if (!options.isEmpty()) {
			bySession.put(sessionId, List.copyOf(options));
			logger.debug("Session {} advertised {} config option(s)", sessionId, options.size());
		}
	}

	/** Delegates everything; watches one method. */
	private final class RecordingTransport implements AcpClientTransport {

		private final AcpClientTransport delegate;

		private RecordingTransport(AcpClientTransport delegate) {
			this.delegate = delegate;
		}

		@Override
		public <T> T unmarshalFrom(Object raw, TypeRef<T> type) {
			T value = delegate.unmarshalFrom(raw, type);
			if (value instanceof AcpSchema.NewSessionResponse response && raw instanceof Map<?, ?> map) {
				record(delegate, response.sessionId(), map.get("configOptions"));
			}
			return value;
		}

		@Override
		public Mono<Void> connect(
				java.util.function.Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			return delegate.connect(handler);
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return delegate.sendMessage(message);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return delegate.closeGracefully();
		}

		@Override
		public void close() {
			delegate.close();
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			delegate.setExceptionHandler(handler);
		}

		@Override
		public List<Integer> protocolVersions() {
			return delegate.protocolVersions();
		}
	}
}
