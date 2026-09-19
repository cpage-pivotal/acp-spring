package org.tanzu.acp.boot;

import java.util.List;

import org.tanzu.acp.runtime.AgentRuntime;

/**
 * The runtime {@code spring.acp.runtime} chose, resolved and validated before anything is launched.
 *
 * <p>A separate bean so that selecting a runtime and starting one are separate failures. A typo in
 * {@code spring.acp.runtime} should be a context-refresh error naming the alternatives, not a
 * process that fails to spawn.
 */
public record SelectedRuntime(AgentRuntime runtime) {

	static SelectedRuntime from(List<AgentRuntime> available, AcpProperties properties) {
		List<String> ids = available.stream().map(AgentRuntime::id).sorted().toList();

		AgentRuntime selected = available.stream().filter(r -> r.id().equals(properties.getRuntime())).findFirst()
				.orElseThrow(() -> new IllegalStateException("No AgentRuntime registered for spring.acp.runtime='"
						+ properties.getRuntime() + "'; registered runtimes are " + ids));

		// A tier-3 block naming a runtime nobody registered is nearly always a typo, and quietly
		// doing nothing is the failure mode that cost the most time in the format this replaces.
		List<String> unknown = properties.getRuntimes().keySet().stream().filter(id -> !ids.contains(id)).toList();
		if (!unknown.isEmpty()) {
			throw new IllegalStateException("spring.acp.runtimes has options for unregistered runtime(s) " + unknown
					+ "; registered runtimes are " + ids);
		}

		return new SelectedRuntime(selected);
	}
}
