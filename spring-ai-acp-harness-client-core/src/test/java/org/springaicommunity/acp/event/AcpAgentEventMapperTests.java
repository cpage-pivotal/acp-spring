package org.springaicommunity.acp.event;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

class AcpAgentEventMapperTests {

	@Test
	void mapsAgentTextAndThoughtsToDistinctEvents() {
		assertThat(AcpAgentEventMapper
			.map(new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent("hello"))))
			.contains(new AcpAgentEvent.Text("hello"));

		assertThat(AcpAgentEventMapper
			.map(new AcpSchema.AgentThoughtChunk("agent_thought_chunk", new AcpSchema.TextContent("pondering"))))
			.contains(new AcpAgentEvent.Thought("pondering"));
	}

	@Test
	void dropsTheEchoOfOurOwnPrompt() {
		assertThat(AcpAgentEventMapper
			.map(new AcpSchema.UserMessageChunk("user_message_chunk", new AcpSchema.TextContent("what I just sent"))))
			.isEmpty();
	}

	@Test
	void dropsReplayedHistorySoALoadedSessionDoesNotResurfaceAsThisTurnsOutput() {
		AcpSchema.AgentMessageChunk replayed = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.TextContent("said last week"), null, Map.of("replay", true));

		assertThat(AcpAgentEventMapper.map(replayed)).isEmpty();
	}

	@Test
	void mapsToolCallLifecycleToStartedThenUpdated() {
		AcpSchema.SessionUpdate started = new AcpSchema.ToolCall("tool_call", "t1", "Read file",
				AcpSchema.ToolKind.READ, AcpSchema.ToolCallStatus.PENDING, List.of(), List.of(), null, null, null);
		AcpSchema.SessionUpdate finished = new AcpSchema.ToolCallUpdateNotification("tool_call_update", "t1", null,
				null, AcpSchema.ToolCallStatus.COMPLETED, List.of(), null, null, null, null);

		assertThat(AcpAgentEventMapper.map(started))
			.contains(new AcpAgentEvent.ToolCallStarted("t1", "Read file", AcpSchema.ToolKind.READ));
		assertThat(AcpAgentEventMapper.map(finished)).get()
			.isEqualTo(new AcpAgentEvent.ToolCallUpdated("t1", AcpSchema.ToolCallStatus.COMPLETED, List.of()));
	}

	@Test
	void skipsContentBlocksThatCarryNoText() {
		AcpSchema.SessionUpdate image = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.ImageContent("image", "data", "image/png", null, null, null));

		assertThat(AcpAgentEventMapper.map(image)).isEmpty();
	}

	@Test
	void mapsUsageUpdatesIncludingTheCostAnAgentMayAttach() {
		// Measured against goose 1.51: it sends one of these with used=0 before the turn
		// starts and
		// another with the real numbers when it ends, the second one carrying a cost.
		AcpSchema.SessionUpdate update = new AcpSchema.UsageUpdate("usage_update", 4441L, 1_050_000L,
				new AcpSchema.Cost(0.008932, "USD"), null);

		assertThat(AcpAgentEventMapper.map(update))
			.contains(new AcpAgentEvent.UsageUpdated(4441L, 1_050_000L, 0.008932, "USD"));
	}

	@Test
	void reportsHowMuchOfTheContextWindowIsGone() {
		AcpAgentEvent.UsageUpdated usage = new AcpAgentEvent.UsageUpdated(500L, 1000L, null, null);

		assertThat(usage.contextFraction()).hasValue(0.5);
	}

	@Test
	void hasNoOpinionOnTheContextWindowWhenTheAgentDidNotSayHowBigItIs() {
		assertThat(new AcpAgentEvent.UsageUpdated(500L, 0L, null, null).contextFraction()).isEmpty();
	}

	@Test
	void dropsAUsageUpdateThatMeasuredNothing() {
		// "0 of 0" would read as an empty context window rather than as no measurement,
		// and a gauge
		// cannot tell the difference.
		assertThat(AcpAgentEventMapper.map(new AcpSchema.UsageUpdate("usage_update", null, null, null, null)))
			.isEmpty();
	}

	@Test
	void toleratesANullUpdate() {
		assertThat(AcpAgentEventMapper.map(null)).isEmpty();
	}

}
