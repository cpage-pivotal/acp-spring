package org.tanzu.acp.observation;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a turn's observation actually ends up looking like in a meter registry.
 *
 * <p>Asserted through a real {@link ObservationRegistry} with the meter handler attached, rather
 * than against a mock, because the questions worth asking are about timers that do or do not get
 * published and tags that do or do not appear on them — and a mock would answer all of them yes.
 */
class MicrometerAgentObservationsTests {

	private MeterRegistry meters;

	private MicrometerAgentObservations observations;

	@BeforeEach
	void setUp() {
		meters = new SimpleMeterRegistry();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
		observations = new MicrometerAgentObservations(registry);
	}

	@Test
	void timesACompletedTurnAndTagsItWithTheModelTheSessionReallyHas() {
		AgentObservations.TurnRecording turn = observations
				.turnStarted(new AgentObservations.TurnContext("goose", "review-123", "gpt-5.4-mini", false));

		turn.completed("END_TURN");

		assertThat(meters.get("acp.turn").tag("acp.runtime", "goose").tag("acp.model", "gpt-5.4-mini")
				.tag("acp.session.kind", "named").tag("acp.outcome", "END_TURN").timer().count()).isEqualTo(1);
	}

	@Test
	void tagsAThrowawayTurnAsEphemeral() {
		observations.turnStarted(new AgentObservations.TurnContext("codex", "turn-abc", null, true))
				.completed("END_TURN");

		assertThat(meters.get("acp.turn").tag("acp.session.kind", "ephemeral").tag("acp.model", "unknown").timer()
				.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a turn that failed is tagged with the reason, not with the exception's message")
	void recordsTheFailureReasonWithoutLeakingItsMessage() {
		AgentObservations.TurnRecording turn = observations
				.turnStarted(new AgentObservations.TurnContext("goose", "s", "m", false));

		turn.failed("TimeoutException", new java.util.concurrent.TimeoutException(
				"session 20260920_224 in /Users/someone/secrets timed out"));

		assertThat(meters.get("acp.turn").tag("acp.outcome", "TimeoutException").timer().count()).isEqualTo(1);
		assertThat(meters.getMeters().stream().map(m -> m.getId().getTags().toString()))
				.noneMatch(tags -> tags.contains("secrets"));
	}

	@Test
	void timesEachToolCallSeparatelyFromItsTurn() {
		AgentObservations.TurnRecording turn = observations
				.turnStarted(new AgentObservations.TurnContext("goose", "s", "m", false));

		turn.toolCallStarted("t1", "Read the build file", "READ");
		turn.toolCallUpdated("t1", "IN_PROGRESS");
		turn.toolCallUpdated("t1", "COMPLETED");
		turn.completed("END_TURN");

		assertThat(meters.get("acp.tool.call").tag("acp.tool.kind", "READ").tag("acp.tool.status", "COMPLETED")
				.timer().count()).isEqualTo(1);
		assertThat(meters.get("acp.turn").timer().count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a tool call the turn outlived is still stopped, tagged unfinished")
	void closesToolCallsTheAgentNeverFinished() {
		// A cancelled turn produces one of these every time: the agent announced a call and the turn
		// ended before any status arrived. An observation left open is a leaked span.
		AgentObservations.TurnRecording turn = observations
				.turnStarted(new AgentObservations.TurnContext("goose", "s", "m", false));

		turn.toolCallStarted("t1", "Search the repository", "SEARCH");
		turn.failed("cancelled", null);

		assertThat(meters.get("acp.tool.call").tag("acp.tool.status", "unfinished").timer().count()).isEqualTo(1);
	}

	@Test
	void finishesATurnOnlyOnce() {
		AgentObservations.TurnRecording turn = observations
				.turnStarted(new AgentObservations.TurnContext("goose", "s", "m", false));

		turn.completed("END_TURN");
		turn.failed("TimeoutException", new RuntimeException());

		assertThat(meters.get("acp.turn").timers().stream().mapToLong(t -> t.count()).sum()).isEqualTo(1);
	}

	@Test
	void keepsTheLatestUsageTheAgentReported() {
		AgentTurnObservationContext context = new AgentTurnObservationContext(
				new AgentObservations.TurnContext("goose", "s", "m", false));

		context.usage(0, 1_050_000, null, null);
		context.usage(4441, 1_050_000, 0.008932, "USD");

		assertThat(new DefaultAgentTurnObservationConvention().getHighCardinalityKeyValues(context))
				.anyMatch(kv -> kv.getKey().equals("acp.context.used") && kv.getValue().equals("4441"))
				.anyMatch(kv -> kv.getKey().equals("acp.cost") && kv.getValue().equals("0.008932 USD"));
	}

	@Test
	@DisplayName("usage is left off entirely by an agent that never reported any")
	void omitsUsageRatherThanReportingZero() {
		AgentTurnObservationContext context = new AgentTurnObservationContext(
				new AgentObservations.TurnContext("codex", "s", "m", false));

		assertThat(new DefaultAgentTurnObservationConvention().getHighCardinalityKeyValues(context))
				.extracting(kv -> kv.getKey()).containsExactly("acp.session.name");
	}

	@Test
	void doNothingObservationsCostNothingAndBreakNothing() {
		AgentObservations.TurnRecording turn = AgentObservations.NONE
				.turnStarted(new AgentObservations.TurnContext("goose", "s", "m", false));

		turn.toolCallStarted("t1", "anything", "READ");
		turn.usage(1, 2, 3.0, "USD");
		turn.completed("END_TURN");

		assertThat(meters.getMeters()).isEmpty();
	}

	@Test
	void aDuplicateToolCallIdDoesNotLeakTheObservationItWouldHaveReplaced() {
		AgentObservations.TurnRecording turn = observations
				.turnStarted(new AgentObservations.TurnContext("goose", "s", "m", false));

		turn.toolCallStarted("t1", "first", "READ");
		turn.toolCallStarted("t1", "second", "EDIT");
		turn.toolCallUpdated("t1", "COMPLETED");
		turn.completed("END_TURN");

		assertThat(meters.get("acp.tool.call").timers().stream().mapToLong(t -> t.count()).sum()).isEqualTo(1);
		assertThat(List.of(meters.get("acp.tool.call").tag("acp.tool.kind", "READ").timer().count())).containsExactly(1L);
	}
}
