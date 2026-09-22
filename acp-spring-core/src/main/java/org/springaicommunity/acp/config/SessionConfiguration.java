package org.springaicommunity.acp.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springaicommunity.acp.runtime.AgentRuntime.PortableOption;

/**
 * The negotiated tier's outcome for one session: one {@link OptionResolution} per portable option.
 */
public record SessionConfiguration(Map<PortableOption, OptionResolution> resolutions) {

	public SessionConfiguration {
		Map<PortableOption, OptionResolution> copy = new EnumMap<>(PortableOption.class);
		if (resolutions != null) {
			copy.putAll(resolutions);
		}
		for (PortableOption option : PortableOption.values()) {
			copy.putIfAbsent(option, OptionResolution.notRequested(option));
		}
		resolutions = java.util.Collections.unmodifiableMap(copy);
	}

	public static SessionConfiguration empty() {
		return new SessionConfiguration(Map.of());
	}

	public static SessionConfiguration from(List<OptionResolution> resolutions) {
		Map<PortableOption, OptionResolution> map = new EnumMap<>(PortableOption.class);
		resolutions.forEach(r -> map.put(r.option(), r));
		return new SessionConfiguration(map);
	}

	public OptionResolution of(PortableOption option) {
		return resolutions.get(option);
	}

	/** The value the agent actually took for {@code option}, when it took one. */
	public Optional<String> applied(PortableOption option) {
		return Optional.of(of(option)).filter(OptionResolution::isApplied).map(OptionResolution::applied);
	}

	/** Options that were requested and could not be honored. */
	public List<OptionResolution> unsupported() {
		return resolutions.values().stream()
				.filter(r -> r.mechanism() == OptionResolution.Mechanism.UNSUPPORTED).toList();
	}
}
