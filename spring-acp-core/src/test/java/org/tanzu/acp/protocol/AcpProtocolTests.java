package org.tanzu.acp.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Version negotiation, and the agent misbehaviour that makes it worth having.
 *
 * <p>Every case here was measured against a live agent before it was written down. goose 1.51.0
 * answers whatever version it is offered; opencode 1.18.31 and codex-acp 1.12.0 both clamp to their
 * own latest.
 */
class AcpProtocolTests {

	@Test
	@DisplayName("v1 offered, v1 answered: the ordinary case, and the only one that happens today")
	void agreesOnVersionOne() {
		assertThat(AcpProtocol.negotiated("opencode", 1, 1, false)).isEqualTo(1);
	}

	@Test
	@DisplayName("an agent answering above the offer is clamped to the offer")
	void clampsAnAnswerNobodyOffered() {
		// Measured: goose 1.51 offered 3 answers 3, for a version that does not exist. Believing it
		// would mean running a v1 turn while thinking it is something else.
		assertThat(AcpProtocol.negotiated("goose", 1, 3, false)).isEqualTo(1);
	}

	@Test
	@DisplayName("strict turns that clamp into a refusal")
	void refusesAnImpossibleAnswerWhenStrict() {
		assertThatThrownBy(() -> AcpProtocol.negotiated("goose", 1, 2, true))
				.isInstanceOf(UnsupportedProtocolVersionException.class)
				.hasMessageContaining("answered ACP v2 to an offer of v1")
				.hasMessageContaining("spring.acp.protocol.strict=false");
	}

	@Test
	@DisplayName("an agent that clamps downwards is taken at its word")
	void acceptsAnAgentNegotiatingDown() {
		// codex-acp offered 2 answers 1. That is the protocol working, and the answer is the truth.
		assertThat(AcpProtocol.negotiated("codex", 2, 1, false)).isEqualTo(1);
	}

	@Test
	@DisplayName("a v2 agent is refused rather than misread")
	void refusesAVersionThisLibraryCannotSpeak() {
		assertThatThrownBy(() -> AcpProtocol.negotiated("goose", 2, 2, false))
				.isInstanceOf(UnsupportedProtocolVersionException.class)
				.hasMessageContaining("negotiated ACP v2")
				.hasMessageContaining("spring.acp.protocol.max-version=1");
	}

	@Test
	@DisplayName("no version on the response means v1")
	void treatsAMissingVersionAsOne() {
		assertThat(AcpProtocol.negotiated("goose", 1, null, false)).isEqualTo(1);
	}

	@Test
	void rejectsAVersionBelowOne() {
		assertThatThrownBy(() -> AcpProtocol.negotiated("goose", 1, 0, false))
				.isInstanceOf(UnsupportedProtocolVersionException.class).hasMessageContaining("predates");
	}

	@Test
	void settingsDefaultToTheVersionThisLibrarySpeaks() {
		ProtocolSettings settings = ProtocolSettings.defaults();
		assertThat(settings.maxVersion()).isEqualTo(AcpProtocol.HIGHEST_SPOKEN);
		assertThat(settings.strict()).isFalse();
		assertThat(settings.offersDraft()).isFalse();
	}

	@Test
	void settingsKnowWhenTheyReachBeyondWhatCanBeSpoken() {
		assertThat(ProtocolSettings.of(AcpProtocol.DRAFT_V2).offersDraft()).isTrue();
	}

	@Test
	void settingsRejectAVersionBelowOne() {
		assertThatThrownBy(() -> new ProtocolSettings(0, false)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-version");
	}
}
