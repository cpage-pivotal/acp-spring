package org.tanzu.acp.observation;

import io.micrometer.observation.Observation;

/**
 * What one turn was, for the convention that tags it.
 *
 * <p>Mutable after creation on purpose: the outcome and the usage numbers arrive during the turn,
 * and a convention reads them when the observation stops.
 */
public class AgentTurnObservationContext extends Observation.Context {

	private final AgentObservations.TurnContext turn;

	private String outcome = "unknown";

	private Long contextUsed;

	private Long contextSize;

	private String cost;

	public AgentTurnObservationContext(AgentObservations.TurnContext turn) {
		this.turn = turn;
	}

	public AgentObservations.TurnContext turn() {
		return turn;
	}

	public String outcome() {
		return outcome;
	}

	public void outcome(String outcome) {
		this.outcome = outcome == null || outcome.isBlank() ? "unknown" : outcome;
	}

	public Long contextUsed() {
		return contextUsed;
	}

	public Long contextSize() {
		return contextSize;
	}

	public String cost() {
		return cost;
	}

	/** The latest {@code usage_update}; each one replaces the last, because they are cumulative. */
	public void usage(long used, long size, Double amount, String currency) {
		this.contextUsed = used;
		this.contextSize = size;
		this.cost = amount == null ? null : amount + (currency == null ? "" : " " + currency);
	}
}
