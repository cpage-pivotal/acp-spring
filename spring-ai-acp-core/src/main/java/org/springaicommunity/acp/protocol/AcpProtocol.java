package org.springaicommunity.acp.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ACP versions this library offers, and what it makes of the version an agent answers
 * with.
 *
 * <p>
 * ACP negotiates in one exchange: the client names the highest version it speaks in
 * {@code initialize}, and the agent answers with that version if it supports it, or with
 * its own latest if it does not. Both halves are one integer, which makes the whole thing
 * look like a formality. It is not, for two reasons measured here.
 *
 * <p>
 * <strong>An agent's answer cannot be taken at its word.</strong> Offered {@code 3} — a
 * version that does not exist — goose 1.51.0 answers {@code 3}. Offered {@code 2} it
 * answers {@code 2}, having shipped no part of v2. OpenCode 1.18.31 and codex-acp 1.12.0
 * both clamp correctly to {@code 1}. This is the same shape as goose accepting a model id
 * it has never heard of, and it has the same consequence: a client that trusted the echo
 * would believe it was in a conversation whose wire format neither side is using. So the
 * negotiated version is {@code min(offered, answered)}, never the answer alone.
 *
 * <p>
 * <strong>v2 is a draft, and this library cannot speak it.</strong> ACP v2 replaces the
 * turn-based model — a prompt response carries the {@code messageId} of the inserted
 * message rather than a {@code stopReason} — and restructures diffs, permission subjects
 * and message patching. {@code acp-core} 0.17.0 models the v1 shapes and declares
 * {@code LATEST_PROTOCOL_VERSION = 1}, so a client that found itself in a v2 conversation
 * would decode a {@code PromptResponse} with a null stop reason and report every turn as
 * having ended for no reason. The protocol's own announcement says to gate v2 behind both
 * version negotiation and a feature flag; this gates it behind both and then refuses to
 * proceed, because the third thing it asks for — an implementation of v2 — is not this
 * library's to write while the SDK's records are v1.
 *
 * <p>
 * Which leaves {@link ProtocolSettings#maxVersion()} above {@link #HIGHEST_SPOKEN} doing
 * one honest job: finding out what an agent claims, with a guaranteed loud failure
 * instead of a silent misreading. The day {@code acp-core} models v2,
 * {@link #HIGHEST_SPOKEN} is the constant that moves.
 */
public final class AcpProtocol {

	private static final Logger logger = LoggerFactory.getLogger(AcpProtocol.class);

	/** The version this library implements end to end. */
	public static final int V1 = 1;

	/**
	 * Published as a draft; its wire format is not what {@code acp-core} 0.17.0 decodes.
	 */
	public static final int DRAFT_V2 = 2;

	/** The highest version a turn run by this library is actually correct under. */
	public static final int HIGHEST_SPOKEN = V1;

	private AcpProtocol() {
	}

	/**
	 * Reconciles what was offered with what the agent answered.
	 * @param runtimeId named in every message, because the misbehaviour is per agent
	 * @param offered the version sent in {@code initialize}
	 * @param answered the version on the {@code initialize} response
	 * @param strict whether an impossible answer fails rather than being clamped
	 * @return the version both sides are really speaking
	 * @throws UnsupportedProtocolVersionException if the result is one this library
	 * cannot speak
	 */
	public static int negotiated(String runtimeId, int offered, Integer answered, boolean strict) {
		if (answered == null) {
			// Required by the schema. Absent means an agent that predates the field or a
			// transport
			// that dropped it; either way v1 is the only version it can be speaking.
			logger.debug("Runtime '{}' answered initialize without a protocolVersion; assuming v{}", runtimeId, V1);
			return requireSpoken(runtimeId, V1);
		}
		if (answered > offered) {
			String message = "Runtime '" + runtimeId + "' answered ACP v" + answered + " to an offer of v" + offered
					+ ", which is not a version either side negotiated; ";
			if (strict) {
				throw new UnsupportedProtocolVersionException(
						message + "set spring.acp.protocol.strict=false to continue at v" + offered + " anyway");
			}
			logger.warn("{}continuing at v{}, the highest that was offered", message, offered);
			return requireSpoken(runtimeId, offered);
		}
		return requireSpoken(runtimeId, answered);
	}

	private static int requireSpoken(String runtimeId, int version) {
		if (version > HIGHEST_SPOKEN) {
			throw new UnsupportedProtocolVersionException("Runtime '" + runtimeId + "' negotiated ACP v" + version
					+ ", which this library does not speak: acp-core " + sdkVersion()
					+ " models the v1 wire format, so a v" + version
					+ " turn would decode with no stop reason and report as having ended for no reason."
					+ " Set spring.acp.protocol.max-version=" + HIGHEST_SPOKEN + " (the default)");
		}
		if (version < V1) {
			throw new UnsupportedProtocolVersionException(
					"Runtime '" + runtimeId + "' negotiated ACP v" + version + ", which predates this library");
		}
		return version;
	}

	private static String sdkVersion() {
		String version = com.agentclientprotocol.sdk.spec.AcpSchema.class.getPackage().getImplementationVersion();
		return version == null ? "0.17.0" : version;
	}

}
