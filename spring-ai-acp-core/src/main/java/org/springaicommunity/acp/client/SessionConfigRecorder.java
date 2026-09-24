package org.springaicommunity.acp.client;

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
 * Recovers the {@code configOptions} an agent returns from {@code session/new}, which the
 * SDK drops before a client can see them.
 *
 * <p>
 * The gap is in acp-core 0.17.0, not in the agents: {@code NewSessionResponse} models
 * {@code sessionId}, {@code modes} and {@code models} and ignores unknown properties,
 * while {@code ForkSessionResponse} and {@code SetSessionConfigOptionResponse} both model
 * {@code configOptions} — an oversight rather than a decision. All three runtimes this
 * library ships adapters for return the field, and {@code ConfigResolver} cannot do its
 * job without it.
 *
 * <p>
 * Rather than parse the wire, this decorates the transport at the one point where the raw
 * payload and its target type meet: {@code unmarshalFrom}. The SDK calls it once per
 * response with the untouched result map, so a decorator sees {@code configOptions} on
 * its way past without knowing anything about framing, ids or correlation. Options are
 * decoded one at a time, so an option of a type this SDK version has no record for costs
 * that one option rather than all of them.
 *
 * <p>
 * {@code LoadSessionResponse} and {@code ResumeSessionResponse} have the same gap and one
 * extra difficulty: neither response carries the session id, so there is nothing to key
 * the options on. Those are stashed unattributed and claimed by the caller that just made
 * the call, which is only sound because {@code AgentSessions} serializes load and resume.
 * Best effort by construction — an agent that returns no options there costs a fallback
 * to {@code modes}/{@code models}, not a failure.
 *
 * <p>
 * Delete this when {@code NewSessionResponse} models the field. The only things outside
 * need to know are {@link #configOptionsFor(String)} and {@link #claimUnattributed()}.
 */
public final class SessionConfigRecorder {

	private static final Logger logger = LoggerFactory.getLogger(SessionConfigRecorder.class);

	private static final TypeRef<AcpSchema.SessionConfigOption> OPTION = new TypeRef<>() {
	};

	private final Map<String, List<AcpSchema.SessionConfigOption>> bySession = new ConcurrentHashMap<>();

	/** Options from a response that did not name its session. See the class javadoc. */
	private final java.util.concurrent.atomic.AtomicReference<List<AcpSchema.SessionConfigOption>> unattributed = new java.util.concurrent.atomic.AtomicReference<>();

	/**
	 * Wraps {@code delegate} so that new sessions' config options are recorded as they
	 * arrive.
	 */
	public AcpClientTransport wrap(AcpClientTransport delegate) {
		return new RecordingTransport(delegate);
	}

	/**
	 * The options the agent advertised when it created {@code sessionId}, or empty if it
	 * advertised none. Keyed by session id rather than held as "the last one seen",
	 * because two callers may open sessions at the same time.
	 */
	public List<AcpSchema.SessionConfigOption> configOptionsFor(String sessionId) {
		return bySession.getOrDefault(sessionId, List.of());
	}

	public void forget(String sessionId) {
		bySession.remove(sessionId);
	}

	/**
	 * Takes the options from the last response that carried some without naming a
	 * session, clearing them so a later caller cannot pick up a stale set.
	 */
	public List<AcpSchema.SessionConfigOption> claimUnattributed() {
		List<AcpSchema.SessionConfigOption> claimed = unattributed.getAndSet(null);
		return claimed == null ? List.of() : claimed;
	}

	private void recordUnattributed(AcpClientTransport transport, Object rawOptions) {
		List<AcpSchema.SessionConfigOption> options = decode(transport, rawOptions);
		if (!options.isEmpty()) {
			unattributed.set(options);
		}
	}

	private void record(AcpClientTransport transport, String sessionId, Object rawOptions) {
		if (sessionId == null) {
			return;
		}
		List<AcpSchema.SessionConfigOption> options = decode(transport, rawOptions);
		if (!options.isEmpty()) {
			bySession.put(sessionId, options);
			logger.debug("Session {} advertised {} config option(s)", sessionId, options.size());
		}
	}

	private List<AcpSchema.SessionConfigOption> decode(AcpClientTransport transport, Object rawOptions) {
		if (!(rawOptions instanceof List<?> raw) || raw.isEmpty()) {
			return List.of();
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
		return List.copyOf(options);
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
			if (raw instanceof Map<?, ?> map) {
				if (value instanceof AcpSchema.NewSessionResponse response) {
					record(delegate, response.sessionId(), map.get("configOptions"));
				}
				else if (value instanceof AcpSchema.LoadSessionResponse
						|| value instanceof AcpSchema.ResumeSessionResponse) {
					recordUnattributed(delegate, map.get("configOptions"));
				}
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
