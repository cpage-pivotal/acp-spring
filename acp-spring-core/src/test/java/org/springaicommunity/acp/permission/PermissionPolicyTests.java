package org.springaicommunity.acp.permission;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionPolicyTests {

	private static final AcpSchema.PermissionOption ALLOW = new AcpSchema.PermissionOption("a", "Allow",
			AcpSchema.PermissionOptionKind.ALLOW_ONCE);

	private static final AcpSchema.PermissionOption REJECT = new AcpSchema.PermissionOption("r", "Reject",
			AcpSchema.PermissionOptionKind.REJECT_ONCE);

	private static final List<AcpSchema.PermissionOption> BOTH = List.of(ALLOW, REJECT);

	@Test
	void denyRejectsEvenWhenTheToolLooksHarmless() {
		assertThat(PermissionPolicy.deny().decide(Optional.of("developer__read"), BOTH)).contains(REJECT);
	}

	@Test
	void autoApproveAllows() {
		assertThat(PermissionPolicy.autoApprove().decide(Optional.of("anything"), BOTH)).contains(ALLOW);
	}

	@Test
	void allowlistAdmitsOnlyItsOwnTools() {
		PermissionPolicy policy = PermissionPolicy.allowlist(Set.of("developer__text_editor"));

		assertThat(policy.decide(Optional.of("developer__text_editor"), BOTH)).contains(ALLOW);
		assertThat(policy.decide(Optional.of("developer__shell"), BOTH)).contains(REJECT);
	}

	@Test
	void allowlistMatchesRegardlessOfCase() {
		PermissionPolicy policy = PermissionPolicy.allowlist(Set.of("Developer__Text_Editor"));

		assertThat(policy.decide(Optional.of("developer__text_editor"), BOTH)).contains(ALLOW);
	}

	@Test
	void allowlistRejectsWhenTheAgentWouldNotNameTheTool() {
		PermissionPolicy policy = PermissionPolicy.allowlist(Set.of("developer__text_editor"));

		assertThat(policy.decide(Optional.empty(), BOTH)).contains(REJECT);
	}

	@Test
	void fallsBackToTheAlwaysVariantWhenOnlyItIsOffered() {
		AcpSchema.PermissionOption rejectAlways = new AcpSchema.PermissionOption("ra", "Always reject",
				AcpSchema.PermissionOptionKind.REJECT_ALWAYS);

		assertThat(PermissionPolicy.deny().decide(Optional.of("t"), List.of(ALLOW, rejectAlways)))
				.contains(rejectAlways);
	}

	@Test
	void returnsEmptyWhenTheAgentOffersNothingToChoose() {
		assertThat(PermissionPolicy.deny().decide(Optional.of("t"), List.of())).isEmpty();
	}
}
