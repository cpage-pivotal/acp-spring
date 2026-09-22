package org.springaicommunity.acp.boot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Reads a standalone {@code agents.yaml} as if every key in it had been written under
 * {@code spring.acp}.
 *
 * <p>One binding model, two entry points. An application that lives in {@code application.yaml}
 * writes {@code spring.acp.runtime: goose} and gets relaxed binding, profiles and {@code ${}}
 * resolution; a buildpack or an operator that has to drop a file next to the jar writes
 * {@code runtime: goose} in {@code agents.yaml} and gets all of the same, because this loader only
 * changes the keys and hands the result back to Boot's own machinery.
 *
 * <pre>{@code
 * # agents.yaml
 * runtime: goose
 * workspace: /home/vcap/app/workspace
 * permissions:
 *   policy: deny
 * runtimes:
 *   goose:
 *     builtins: developer
 * }</pre>
 *
 * <p>A key that already starts with {@code spring.} is left alone, per key rather than per file.
 * That is what lets one of these files carry both the agent configuration it exists for and the
 * occasional unrelated property, without a rule about which kind of file it is.
 */
public class AgentsConfigDataLoader implements ConfigDataLoader<AgentsConfigDataResource> {

	static final String PREFIX = "spring.acp.";

	private final YamlPropertySourceLoader yaml = new YamlPropertySourceLoader();

	@Override
	public ConfigData load(ConfigDataLoaderContext context, AgentsConfigDataResource resource) throws IOException {
		if (!resource.resource().exists()) {
			// Boot swallows this for an optional: location and reports it for a required one.
			throw new ConfigDataResourceNotFoundException(resource);
		}
		List<PropertySource<?>> loaded = yaml.load("agents: " + resource.location(), resource.resource());
		List<PropertySource<?>> prefixed = new ArrayList<>(loaded.size());
		for (PropertySource<?> source : loaded) {
			prefixed.add(prefix(source));
		}
		return new ConfigData(prefixed);
	}

	/**
	 * Re-keys one document.
	 *
	 * <p>The values are copied across untouched, which matters more than it looks: Boot's YAML
	 * loader wraps each one in an {@code OriginTrackedValue} carrying the file and line it came
	 * from, and that is what makes a validation failure in {@code agents.yaml} point at the line
	 * in {@code agents.yaml} rather than at a property nobody can find.
	 */
	@SuppressWarnings("unchecked")
	private static PropertySource<?> prefix(PropertySource<?> source) {
		if (!(source instanceof MapPropertySource map)) {
			return source;
		}
		Map<String, Object> renamed = new LinkedHashMap<>();
		((Map<String, Object>) map.getSource())
				.forEach((key, value) -> renamed.put(key.startsWith("spring.") ? key : PREFIX + key, value));
		return new OriginTrackedMapPropertySource(source.getName(), renamed, true);
	}
}
