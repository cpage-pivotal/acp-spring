package org.springaicommunity.acp.protocol;

/**
 * Which ACP version this client offers, and what it does with the answer.
 *
 * <p>Tier one, and the only tier-one option whose default an application is asked never to change
 * lightly: {@code maxVersion} above {@link AcpProtocol#HIGHEST_SPOKEN} offers a version this
 * library cannot actually speak, which is useful for finding out what an agent claims and for
 * nothing else. See {@link AcpProtocol} for why that is a probe rather than a feature.
 *
 * @param maxVersion the highest version to offer on {@code initialize}
 * @param strict whether an agent answering something impossible is a failure or a clamp
 */
public record ProtocolSettings(int maxVersion, boolean strict) {

	public ProtocolSettings {
		if (maxVersion < AcpProtocol.V1) {
			throw new IllegalArgumentException(
					"spring.acp.protocol.max-version must be at least " + AcpProtocol.V1 + " but was " + maxVersion);
		}
	}

	/** ACP v1, and an agent that answers nonsense is clamped rather than refused. */
	public static ProtocolSettings defaults() {
		return new ProtocolSettings(AcpProtocol.HIGHEST_SPOKEN, false);
	}

	public static ProtocolSettings of(int maxVersion) {
		return new ProtocolSettings(maxVersion, false);
	}

	/** Whether this offer reaches beyond what {@link AcpProtocol#HIGHEST_SPOKEN} can honor. */
	public boolean offersDraft() {
		return maxVersion > AcpProtocol.HIGHEST_SPOKEN;
	}
}
