package org.springaicommunity.acp.event;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpTransport;

/**
 * Turns a raw {@code session/update} notification into a typed one, skipping variants
 * this SDK version has no record for instead of failing on them.
 *
 * <p>
 * This is why the client registers a raw notification handler rather than the SDK's
 * {@code sessionUpdateConsumer}. That consumer deserializes strictly: goose emits
 * {@code session_info_update} several times a turn, acp-core 0.17.0's
 * {@code SessionUpdate} hierarchy has no such subtype, and Jackson's failure to resolve
 * the type id is logged at ERROR by the SDK once per occurrence. The turn completes
 * normally — the notification carries session metadata a turn does not need — but an
 * ERROR per turn for a benign case is worse than useless: it teaches an operator to
 * ignore that logger, which is where a real notification failure would appear.
 *
 * <p>
 * Suppressing the SDK's logger would have hidden the real failures too. Decoding
 * leniently here keeps them: anything that fails is reported, at a level that matches
 * what it costs.
 */
public final class SessionUpdateDecoder {

	private static final Logger logger = LoggerFactory.getLogger(SessionUpdateDecoder.class);

	private static final TypeRef<AcpSchema.SessionNotification> NOTIFICATION = new TypeRef<>() {
	};

	private SessionUpdateDecoder() {
	}

	/**
	 * @param params the notification's raw {@code params}, as the transport delivered
	 * them
	 * @return the notification, or empty when it is one this SDK version cannot represent
	 */
	public static Optional<AcpSchema.SessionNotification> decode(Object params, AcpTransport transport) {
		if (params instanceof AcpSchema.SessionNotification typed) {
			return Optional.of(typed);
		}
		if (params == null) {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(transport.unmarshalFrom(params, NOTIFICATION));
		}
		catch (RuntimeException ex) {
			logger.debug("Skipping a session/update of kind '{}' that this ACP SDK cannot model: {}", kindOf(params),
					ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * The {@code sessionUpdate} discriminator, so the log line names what was skipped.
	 */
	private static String kindOf(Object params) {
		if (params instanceof Map<?, ?> map && map.get("update") instanceof Map<?, ?> update) {
			Object kind = update.get("sessionUpdate");
			return kind == null ? "unknown" : String.valueOf(kind);
		}
		return "unknown";
	}

}
