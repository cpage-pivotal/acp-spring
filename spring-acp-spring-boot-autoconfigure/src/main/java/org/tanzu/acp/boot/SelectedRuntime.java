package org.tanzu.acp.boot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.runtime.AgentRuntimeProvider;

/**
 * The runtime {@code spring.acp.runtime} chose, resolved and validated before anything is launched.
 *
 * <p>A separate bean so that selecting a runtime and starting one are separate failures. A typo in
 * {@code spring.acp.runtime} should be a context-refresh error naming the alternatives, not a
 * process that fails to spawn.
 *
 * <p>Registered adapters are searched first and {@link AgentRuntimeProvider}s only after, so an
 * application with both a compiled adapter and the registry on its classpath gets the adapter for
 * the agent it wrote one for — with its option ids, its {@code _meta} tool names and, for Goose, its
 * served transport — rather than a generic download of the same binary.
 */
public record SelectedRuntime(AgentRuntime runtime) {

	private static final Logger logger = LoggerFactory.getLogger(SelectedRuntime.class);

	static SelectedRuntime from(List<AgentRuntime> available, AcpProperties properties) {
		return from(available, List.of(), properties);
	}

	static SelectedRuntime from(List<AgentRuntime> available, List<AgentRuntimeProvider> providers,
			AcpProperties properties) {
		requireDistinctIds(available);
		List<String> adapterIds = available.stream().map(AgentRuntime::id).sorted().toList();

		Optional<AgentRuntime> adapter = available.stream().filter(r -> r.id().equals(properties.getRuntime()))
				.findFirst();
		AgentRuntime selected = adapter.orElseGet(() -> fromProviders(providers, properties, adapterIds));
		if (adapter.isEmpty()) {
			logger.info("No adapter claims runtime '{}'; it was resolved from a runtime provider",
					properties.getRuntime());
		}

		requireKnownTierThreeRuntimes(properties, adapterIds, providers, selected);
		return new SelectedRuntime(selected);
	}

	private static AgentRuntime fromProviders(List<AgentRuntimeProvider> providers, AcpProperties properties,
			List<String> adapterIds) {
		for (AgentRuntimeProvider provider : providers) {
			Optional<AgentRuntime> resolved = provider.forId(properties.getRuntime());
			if (resolved.isPresent()) {
				return resolved.get();
			}
		}
		throw new IllegalStateException("No AgentRuntime registered for spring.acp.runtime='"
				+ properties.getRuntime() + "'; registered runtimes are " + adapterIds + suggestion(providers));
	}

	/**
	 * Names a few of the agents a provider could have supplied, without printing a catalogue.
	 *
	 * <p>The registry has 41 entries. An error message that listed all of them would push the part an
	 * operator needs — their own typo, and the adapters they actually have — off the top of the
	 * terminal.
	 */
	private static String suggestion(List<AgentRuntimeProvider> providers) {
		List<String> known = providers.stream().flatMap(provider -> provider.knownIds().stream()).sorted().toList();
		if (known.isEmpty()) {
			return "";
		}
		return ", and " + known.size() + " more are available from the ACP registry (" + String.join(", ",
				known.subList(0, Math.min(5, known.size()))) + ", …)";
	}

	/**
	 * A tier-3 block naming a runtime nobody can supply is nearly always a typo, and quietly doing
	 * nothing is the failure mode that cost the most time in the format this replaces.
	 *
	 * <p>What counts as known widened in M4 and had to be bounded carefully. Accepting any id a
	 * provider enumerates would let {@code spring.acp.runtimes.gemini} pass on a machine with the
	 * registry jar and fail on one without it, which is a difference no application wants to
	 * discover in production. So the id of the runtime actually selected always passes, whatever
	 * supplied it, and the rest must be an adapter or an id a provider will vouch for now.
	 */
	private static void requireKnownTierThreeRuntimes(AcpProperties properties, List<String> adapterIds,
			List<AgentRuntimeProvider> providers, AgentRuntime selected) {
		Set<String> known = new LinkedHashSet<>(adapterIds);
		known.add(selected.id());
		List<String> unknown = new ArrayList<>();
		for (String id : properties.getRuntimes().keySet()) {
			if (known.contains(id)) {
				continue;
			}
			if (providers.stream().anyMatch(provider -> provider.forId(id).isPresent())) {
				continue;
			}
			unknown.add(id);
		}
		if (!unknown.isEmpty()) {
			throw new IllegalStateException("spring.acp.runtimes has options for unregistered runtime(s) " + unknown
					+ "; registered runtimes are " + adapterIds + suggestion(providers));
		}
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
