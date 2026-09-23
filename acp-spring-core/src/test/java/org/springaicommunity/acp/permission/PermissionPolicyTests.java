package org.springaicommunity.acp.permission;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

	@Test
	void theWholeRequestReachesARuleAsJustTheToolAndOptions() {
		assertThat(PermissionPolicy.autoApprove().decide(request("Run ls"), Optional.of("shell"))).contains(ALLOW);
	}

	@Test
	void askShowsThePersonTheAgentsDescriptionOfTheCall() {
		AtomicReference<PermissionQuestion> asked = new AtomicReference<>();
		PermissionPolicy policy = PermissionPolicy.ask(question -> {
			asked.set(question);
			return Optional.of(REJECT);
		});

		assertThat(policy.decide(request("Run `rm -rf build`"), Optional.of("developer__shell"))).contains(REJECT);
		assertThat(asked.get().describe()).isEqualTo("Run `rm -rf build`");
		assertThat(asked.get().toolName()).contains("developer__shell");
		assertThat(asked.get().kind()).isEqualTo(AcpSchema.ToolKind.EXECUTE);
		assertThat(asked.get().options()).containsExactly(ALLOW, REJECT);
	}

	@Test
	void askFallsBackToTheToolNameWhenTheAgentGaveNoTitle() {
		AtomicReference<PermissionQuestion> asked = new AtomicReference<>();
		PermissionPolicy.ask(question -> {
			asked.set(question);
			return Optional.empty();
		}).decide(Optional.of("developer__shell"), BOTH);

		assertThat(asked.get().describe()).isEqualTo("developer__shell");
	}

	@Test
	void askCancelsWhenThePersonDeclinesToChoose() {
		assertThat(PermissionPolicy.ask(question -> Optional.empty()).decide(request("x"), Optional.empty())).isEmpty();
	}

	@Test
	void askNeverSendsBackAnOptionTheAgentDidNotOffer() {
		AcpSchema.PermissionOption invented = new AcpSchema.PermissionOption("z", "Invented",
				AcpSchema.PermissionOptionKind.ALLOW_ALWAYS);

		assertThat(PermissionPolicy.ask(question -> Optional.of(invented)).decide(request("x"), Optional.empty()))
				.isEmpty();
	}

	private static AcpSchema.RequestPermissionRequest request(String title) {
		AcpSchema.ToolCallUpdate call = new AcpSchema.ToolCallUpdate("call-1", title, AcpSchema.ToolKind.EXECUTE,
				AcpSchema.ToolCallStatus.PENDING, null, null, null, null);
		return new AcpSchema.RequestPermissionRequest("session-1", call, BOTH);
	}
}
