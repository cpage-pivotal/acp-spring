package org.tanzu.acp.boot;

import java.util.List;

import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Claims {@code agents.yaml} — and anything written {@code acp:<path>} — for
 * {@link AgentsConfigDataLoader}.
 *
 * <p>Two spellings because they answer two different questions. {@code optional:agents.yaml} is
 * what an operator or a buildpack writes, and it works because Boot always considers custom
 * resolvers before its own: {@code ConfigDataLocationResolvers} moves
 * {@code StandardConfigDataLocationResolver} to the end of the list on purpose, so a file named
 * {@code agents.yaml} reaches this class rather than being loaded as ordinary properties with the
 * {@code spring.acp} prefix missing. {@code optional:acp:whatever.yaml} is the explicit form, for
 * a file that has to be called something else.
 */
public class AgentsConfigDataLocationResolver implements ConfigDataLocationResolver<AgentsConfigDataResource> {

	/** The explicit form: {@code spring.config.import: optional:acp:my-agents.yaml}. */
	static final String PREFIX = "acp:";

	private static final List<String> CLAIMED_FILE_NAMES = List.of("agents.yaml", "agents.yml");

	private final ResourceLoader resourceLoader = new DefaultResourceLoader();

	@Override
	public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
		return location.hasPrefix(PREFIX) || isAgentsFile(location.toString());
	}

	private static boolean isAgentsFile(String value) {
		String withoutOptional = value.startsWith(ConfigDataLocation.OPTIONAL_PREFIX)
				? value.substring(ConfigDataLocation.OPTIONAL_PREFIX.length()) : value;
		int lastSlash = withoutOptional.lastIndexOf('/');
		String fileName = lastSlash < 0 ? withoutOptional : withoutOptional.substring(lastSlash + 1);
		return CLAIMED_FILE_NAMES.contains(fileName.toLowerCase(java.util.Locale.ROOT));
	}

	@Override
	public List<AgentsConfigDataResource> resolve(ConfigDataLocationResolverContext context,
			ConfigDataLocation location) {
		String path = location.getNonPrefixedValue(PREFIX);
		Resource resource = resourceLoader.getResource(withScheme(path));
		return List.of(new AgentsConfigDataResource(path, resource, location.isOptional()));
	}

	/**
	 * A bare path means a file on disk, next to the application.
	 *
	 * <p>{@code DefaultResourceLoader} would read it as a classpath resource, which is the wrong
	 * default here: the point of this file is that something outside the build — a buildpack, an
	 * operator, a mounted volume — can drop it beside the jar.
	 */
	private static String withScheme(String path) {
		return path.contains(":") ? path : "file:" + path;
	}
}
