package org.tanzu.acp.boot;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
		requireDistinctIds(available);

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

	/**
	 * Two adapters claiming the same agent is a configuration error, not a tie to be broken.
	 *
	 * <p>It happens when an application contributes its own adapter for an agent this library also
	 * ships one for: the bundled registration only backs off from a bean of the same <em>name</em>, so
	 * a differently named bean leaves both registered. Picking one silently would mean the agent an
	 * application actually gets depends on bean ordering, which is exactly the kind of surprise this
	 * library exists to remove — so the message says how to replace the bundled one instead.
	 */
	private static void requireDistinctIds(List<AgentRuntime> available) {
		Map<String, List<AgentRuntime>> byId = available.stream()
				.collect(Collectors.groupingBy(AgentRuntime::id));
		List<String> duplicated = byId.entrySet().stream().filter(e -> e.getValue().size() > 1).map(Map.Entry::getKey)
				.sorted(Comparator.naturalOrder()).toList();
		if (!duplicated.isEmpty()) {
			throw new IllegalStateException("More than one AgentRuntime is registered for " + duplicated
					+ "; to replace a bundled adapter, declare your bean with the same name as the one it replaces"
					+ " (for example @Bean(\"gooseAgentRuntime\"))");
		}
	}
}
