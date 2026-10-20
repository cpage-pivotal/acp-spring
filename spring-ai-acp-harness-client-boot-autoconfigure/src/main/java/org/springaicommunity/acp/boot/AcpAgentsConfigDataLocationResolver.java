package org.springaicommunity.acp.boot;

import java.util.List;

import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Claims anything written {@code acp:<path>} for {@link AcpAgentsConfigDataLoader}.
 *
 * <p>
 * Only the prefix, never a file name. Boot always considers custom resolvers before its
 * own ({@code ConfigDataLocationResolvers} moves
 * {@code StandardConfigDataLocationResolver} to the end of the list on purpose), so a
 * resolver that claimed a bare {@code agents.yaml} would take that file from every other
 * library that reads one, and re-key it under {@code spring.acp} where that library would
 * never find it. The prefix is this library's own namespace, and it shows in the import
 * line which loader reads the file.
 */
public class AcpAgentsConfigDataLocationResolver implements ConfigDataLocationResolver<AcpAgentsConfigDataResource> {

	/** As in {@code spring.config.import: optional:acp:agents.yaml}. */
	static final String PREFIX = "acp:";

	private final ResourceLoader resourceLoader = new DefaultResourceLoader();

	@Override
	public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
		return location.hasPrefix(PREFIX);
	}

	@Override
	public List<AcpAgentsConfigDataResource> resolve(ConfigDataLocationResolverContext context,
			ConfigDataLocation location) {
		String path = location.getNonPrefixedValue(PREFIX);
		Resource resource = resourceLoader.getResource(withScheme(path));
		return List.of(new AcpAgentsConfigDataResource(path, resource, location.isOptional()));
	}

	/**
	 * A bare path means a file on disk, next to the application.
	 *
	 * <p>
	 * {@code DefaultResourceLoader} would read it as a classpath resource, which is the
	 * wrong default here: the point of this file is that something outside the build — a
	 * buildpack, an operator, a mounted volume — can drop it beside the jar.
	 */
	private static String withScheme(String path) {
		return path.contains(":") ? path : "file:" + path;
	}

}
