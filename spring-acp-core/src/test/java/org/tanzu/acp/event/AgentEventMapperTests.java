package org.tanzu.acp.event;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventMapperTests {

	@Test
	void mapsAgentTextAndThoughtsToDistinctEvents() {
		assertThat(AgentEventMapper.map(new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.TextContent("hello")))).contains(new AgentEvent.Text("hello"));

		assertThat(AgentEventMapper.map(new AcpSchema.AgentThoughtChunk("agent_thought_chunk",
				new AcpSchema.TextContent("pondering")))).contains(new AgentEvent.Thought("pondering"));
	}

	@Test
	void dropsTheEchoOfOurOwnPrompt() {
		assertThat(AgentEventMapper.map(
				new AcpSchema.UserMessageChunk("user_message_chunk", new AcpSchema.TextContent("what I just sent"))))
						.isEmpty();
	}

	@Test
	void dropsReplayedHistorySoALoadedSessionDoesNotResurfaceAsThisTurnsOutput() {
		AcpSchema.AgentMessageChunk replayed = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.TextContent("said last week"), null, Map.of("replay", true));

		assertThat(AgentEventMapper.map(replayed)).isEmpty();
	}

	@Test
	void mapsToolCallLifecycleToStartedThenUpdated() {
		AcpSchema.SessionUpdate started = new AcpSchema.ToolCall("tool_call", "t1", "Read file",
				AcpSchema.ToolKind.READ, AcpSchema.ToolCallStatus.PENDING, List.of(), List.of(), null, null, null);
		AcpSchema.SessionUpdate finished = new AcpSchema.ToolCallUpdateNotification("tool_call_update", "t1", null,
				null, AcpSchema.ToolCallStatus.COMPLETED, List.of(), null, null, null, null);

		assertThat(AgentEventMapper.map(started))
				.contains(new AgentEvent.ToolCallStarted("t1", "Read file", AcpSchema.ToolKind.READ));
		assertThat(AgentEventMapper.map(finished)).get()
				.isEqualTo(new AgentEvent.ToolCallUpdated("t1", AcpSchema.ToolCallStatus.COMPLETED, List.of()));
	}

	@Test
	void skipsContentBlocksThatCarryNoText() {
		AcpSchema.SessionUpdate image = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.ImageContent("image", "data", "image/png", null, null, null));

		assertThat(AgentEventMapper.map(image)).isEmpty();
	}

	@Test
	void toleratesANullUpdate() {
		assertThat(AgentEventMapper.map(null)).isEmpty();
	}
}
