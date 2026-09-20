package org.tanzu.acp.observation;

import org.tanzu.acp.observation.AcpObservationDocumentation.ToolCallHighCardinalityKeys;
import org.tanzu.acp.observation.AcpObservationDocumentation.ToolCallLowCardinalityKeys;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationConvention;

/**
 * Turns a tool call into tags.
 *
 * <p>The title is high-cardinality and stays that way. ACP guarantees only that it is prose written
 * for a human — "Read the build file" — so it is neither stable across agents nor bounded in value
 * count, and the stable identifier an allowlist uses lives behind {@code AgentRuntime.toolNameOf}
 * rather than in the event a turn sees.
 */
public class DefaultToolCallObservationConvention implements ObservationConvention<ToolCallObservationContext> {

	public static final DefaultToolCallObservationConvention INSTANCE = new DefaultToolCallObservationConvention();

	private static final String UNKNOWN = "unknown";

	@Override
	public String getName() {
		return "acp.tool.call";
	}

	@Override
	public String getContextualName(ToolCallObservationContext context) {
		return "acp tool call " + orUnknown(context.kind()).toLowerCase(java.util.Locale.ROOT);
	}

	@Override
	public KeyValues getLowCardinalityKeyValues(ToolCallObservationContext context) {
		return KeyValues.of(ToolCallLowCardinalityKeys.RUNTIME.withValue(orUnknown(context.runtimeId())),
				ToolCallLowCardinalityKeys.KIND.withValue(orUnknown(context.kind())),
				ToolCallLowCardinalityKeys.STATUS.withValue(orUnknown(context.status())));
	}

	@Override
	public KeyValues getHighCardinalityKeyValues(ToolCallObservationContext context) {
		return KeyValues.of(ToolCallHighCardinalityKeys.TITLE.withValue(orUnknown(context.title())),
				ToolCallHighCardinalityKeys.TOOL_CALL_ID.withValue(orUnknown(context.toolCallId())));
	}

	@Override
	public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
		return context instanceof ToolCallObservationContext;
	}

	private static String orUnknown(String value) {
		return value == null || value.isBlank() ? UNKNOWN : value;
	}
}
