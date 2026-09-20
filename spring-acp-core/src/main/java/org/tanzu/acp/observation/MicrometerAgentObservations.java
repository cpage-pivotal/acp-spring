package org.tanzu.acp.observation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;

/**
 * Reports turns and tool calls as Micrometer observations.
 *
 * <p>The only class in this library that names Micrometer, which is what lets
 * {@code micrometer-observation} be an optional dependency: nothing reaches this unless an
 * application has both the jar and an {@link ObservationRegistry} to hand it.
 *
 * <p><strong>Started and stopped rather than observed.</strong> The idiomatic
 * {@code observation.observe(() -> work())} opens a thread-local scope around a block, and a turn is
 * not a block: it is subscribed on one thread and finished on whichever transport thread delivers
 * the agent's last frame, seconds or minutes later. Wrapping it in a scope would close the
 * observation when the subscription <em>call</em> returned, timing the act of asking rather than the
 * turn. So the observation is started on the subscribing thread — where the caller's own observation
 * is still current, which is how the turn ends up a child of it — and stopped from wherever the turn
 * actually ends.
 *
 * <p>Tool call observations get their parent set explicitly for the mirror-image reason: they are
 * created on a transport thread that has no current observation, so there is nothing for Micrometer
 * to infer a parent from.
 *
 * <p>A tool call the turn outlives is stopped anyway, tagged {@code unfinished}. An agent that
 * announces a call and never reports its end is not rare — a cancelled turn produces one every time
 * — and an observation left open is a leaked span and a timer that never fires.
 */
public class MicrometerAgentObservations implements AgentObservations {

	/** Statuses after which no further update is expected, so the observation can be stopped. */
	private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED");

	private final ObservationRegistry registry;

	private final ObservationConvention<AgentTurnObservationContext> turnConvention;

	private final ObservationConvention<ToolCallObservationContext> toolCallConvention;

	public MicrometerAgentObservations(ObservationRegistry registry) {
		this(registry, null, null);
	}

	/**
	 * @param turnConvention overrides the tags put on a turn, or {@code null} for the default
	 * @param toolCallConvention likewise for a tool call
	 */
	public MicrometerAgentObservations(ObservationRegistry registry,
			ObservationConvention<AgentTurnObservationContext> turnConvention,
			ObservationConvention<ToolCallObservationContext> toolCallConvention) {
		this.registry = registry == null ? ObservationRegistry.NOOP : registry;
		this.turnConvention = turnConvention;
		this.toolCallConvention = toolCallConvention;
	}

	@Override
	public TurnRecording turnStarted(TurnContext context) {
		AgentTurnObservationContext observationContext = new AgentTurnObservationContext(context);
		Observation observation = AcpObservationDocumentation.TURN
				.observation(this.turnConvention, DefaultAgentTurnObservationConvention.INSTANCE,
						() -> observationContext, this.registry)
				.start();
		return new MicrometerTurnRecording(observation, observationContext);
	}

	private final class MicrometerTurnRecording implements TurnRecording {

		private final Observation turn;

		private final AgentTurnObservationContext context;

		private final Map<String, Observation> toolCalls = new ConcurrentHashMap<>();

		private final Map<String, ToolCallObservationContext> toolCallContexts = new ConcurrentHashMap<>();

		private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();

		private MicrometerTurnRecording(Observation turn, AgentTurnObservationContext context) {
			this.turn = turn;
			this.context = context;
		}

		@Override
		public void toolCallStarted(String toolCallId, String title, String kind) {
			if (toolCallId == null) {
				return;
			}
			ToolCallObservationContext callContext = new ToolCallObservationContext(
					this.context.turn().runtimeId(), toolCallId, title, kind);
			// No current observation on this thread, so the parent is stated rather than inferred.
			callContext.setParentObservation(this.turn);
			Observation observation = AcpObservationDocumentation.TOOL_CALL
					.observation(MicrometerAgentObservations.this.toolCallConvention,
							DefaultToolCallObservationConvention.INSTANCE, () -> callContext,
							MicrometerAgentObservations.this.registry);
			// An agent may repeat a tool call id; the first one keeps the observation, and the
			// duplicate is dropped rather than leaking the one it would have replaced.
			if (this.toolCalls.putIfAbsent(toolCallId, observation) == null) {
				this.toolCallContexts.put(toolCallId, callContext);
				observation.start();
			}
		}

		@Override
		public void toolCallUpdated(String toolCallId, String status) {
			if (toolCallId == null || status == null) {
				return;
			}
			ToolCallObservationContext callContext = this.toolCallContexts.get(toolCallId);
			if (callContext != null) {
				callContext.status(status);
			}
			if (!TERMINAL_STATUSES.contains(status)) {
				return;
			}
			Observation observation = this.toolCalls.remove(toolCallId);
			this.toolCallContexts.remove(toolCallId);
			if (observation != null) {
				if ("FAILED".equals(status)) {
					observation.error(new ToolCallFailed(toolCallId));
				}
				observation.stop();
			}
		}

		@Override
		public void usage(long contextUsed, long contextSize, Double costAmount, String costCurrency) {
			this.context.usage(contextUsed, contextSize, costAmount, costCurrency);
		}

		@Override
		public void completed(String stopReason) {
			stop(stopReason, null);
		}

		@Override
		public void failed(String reason, Throwable cause) {
			stop(reason, cause);
		}

		private void stop(String outcome, Throwable cause) {
			if (!this.stopped.compareAndSet(false, true)) {
				return;
			}
			closeUnfinishedToolCalls();
			this.context.outcome(outcome);
			if (cause != null) {
				this.turn.error(cause);
			}
			this.turn.stop();
		}

		private void closeUnfinishedToolCalls() {
			this.toolCalls.keySet().forEach(id -> {
				Observation observation = this.toolCalls.remove(id);
				this.toolCallContexts.remove(id);
				if (observation != null) {
					observation.stop();
				}
			});
		}
	}

	/**
	 * Carries a failed tool call's id onto its observation without an agent's message on it.
	 *
	 * <p>A tool failure's text comes from the agent and names paths, commands and occasionally the
	 * content of a file, none of which belongs on a span that leaves the process.
	 */
	public static final class ToolCallFailed extends RuntimeException {

		ToolCallFailed(String toolCallId) {
			super("tool call " + toolCallId + " failed", null, false, false);
		}
	}
}
