package org.thought.acp.turn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Fans the client's single stream of {@code session/update} notifications out to the turn that is
 * listening for each session.
 *
 * <p>This exists because of a shape in the ACP SDK that is easy to miss: {@code prompt()} returns a
 * {@code Mono<PromptResponse>} carrying only a stop reason, and every piece of streamed content
 * arrives instead through <em>one</em> {@code sessionUpdateConsumer} registered once at client
 * build time, shared by every session. Something has to demultiplex it. This is that something.
 *
 * <p>Dispatch is synchronous on purpose. The SDK delivers notifications in arrival order but
 * completes request futures on a separate path, so a handler that deferred its work could let a
 * turn's terminal event overtake the last content chunk it was supposed to follow. Emitting inline
 * keeps that window closed.
 */
public final class SessionUpdateRouter {

	private static final Logger logger = LoggerFactory.getLogger(SessionUpdateRouter.class);

	private final Map<String, Consumer<AcpSchema.SessionUpdate>> listeners = new ConcurrentHashMap<>();

	/**
	 * Directs updates for {@code sessionId} to {@code listener} until {@link #unregister} is called.
	 *
	 * @throws IllegalStateException if a turn is already listening to this session
	 */
	public void register(String sessionId, Consumer<AcpSchema.SessionUpdate> listener) {
		Consumer<AcpSchema.SessionUpdate> previous = listeners.putIfAbsent(sessionId, listener);
		if (previous != null) {
			throw new IllegalStateException("session " + sessionId + " already has an active turn");
		}
	}

	public void unregister(String sessionId) {
		listeners.remove(sessionId);
	}

	/** The callback handed to {@code AcpClient.async(...).sessionUpdateConsumer(...)}. */
	public void accept(AcpSchema.SessionNotification notification) {
		if (notification == null || notification.sessionId() == null) {
			return;
		}
		Consumer<AcpSchema.SessionUpdate> listener = listeners.get(notification.sessionId());
		if (listener == null) {
			// Expected between turns: an agent may emit a trailing update after the turn it belonged
			// to has already ended. Dropping it is correct; the turn is closed.
			logger.trace("No active turn for session {}; dropping update", notification.sessionId());
			return;
		}
		try {
			listener.accept(notification.update());
		}
		catch (RuntimeException ex) {
			// Never let one turn's listener break the shared notification pump.
			logger.warn("Listener for session {} threw; continuing", notification.sessionId(), ex);
		}
	}

	int activeTurns() {
		return listeners.size();
	}
}
