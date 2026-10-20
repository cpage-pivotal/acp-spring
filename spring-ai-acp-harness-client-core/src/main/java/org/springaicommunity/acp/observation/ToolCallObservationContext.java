package org.springaicommunity.acp.observation;

import io.micrometer.observation.Observation;

/**
 * What one tool call was. Its status arrives after it starts, so the field is not final.
 */
public class ToolCallObservationContext extends Observation.Context {

	private final String runtimeId;

	private final String toolCallId;

	private final String title;

	private final String kind;

	private String status = "unfinished";

	public ToolCallObservationContext(String runtimeId, String toolCallId, String title, String kind) {
		this.runtimeId = runtimeId;
		this.toolCallId = toolCallId;
		this.title = title;
		this.kind = kind;
	}

	public String runtimeId() {
		return runtimeId;
	}

	public String toolCallId() {
		return toolCallId;
	}

	public String title() {
		return title;
	}

	public String kind() {
		return kind;
	}

	public String status() {
		return status;
	}

	public void status(String status) {
		this.status = status == null || status.isBlank() ? "unfinished" : status;
	}

}
