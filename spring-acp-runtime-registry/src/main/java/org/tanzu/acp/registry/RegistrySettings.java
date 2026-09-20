package org.tanzu.acp.registry;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Where the catalogue comes from, where downloads land, and what is allowed to reach the network.
 *
 * @param url the published catalogue
 * @param cache where the snapshot and the unpacked agents are kept between runs
 * @param refresh how long a cached snapshot is used before the catalogue is fetched again
 * @param offline forbid every network call, including agent downloads
 * @param requireChecksum refuse an artifact the registry publishes no digest for
 * @param downloadTimeout how long one fetch may take
 */
public record RegistrySettings(URI url, Path cache, Duration refresh, boolean offline, boolean requireChecksum,
		Duration downloadTimeout) {

	public static final URI DEFAULT_URL = URI
			.create("https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json");

	public static final Duration DEFAULT_REFRESH = Duration.ofHours(24);

	public static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

	public RegistrySettings {
		url = url == null ? DEFAULT_URL : url;
		cache = cache == null ? defaultCache() : cache.toAbsolutePath();
		refresh = refresh == null ? DEFAULT_REFRESH : refresh;
		downloadTimeout = downloadTimeout == null ? DEFAULT_DOWNLOAD_TIMEOUT : downloadTimeout;
		String scheme = url.getScheme() == null ? "" : url.getScheme();
		if (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("file")) {
			// Plain http would mean fetching, over a network, the list of executables to download
			// and the digests to check them against.
			throw new IllegalArgumentException(
					"spring.acp.registry.url must be https or file but was '" + url + "'");
		}
	}

	/**
	 * Defaults: the published catalogue, a cache under the user's home, refreshed daily, checksums
	 * required.
	 *
	 * <p>{@code requireChecksum} defaults to on although it makes 9 of the registry's 19 binary
	 * agents unreachable without a second property. That is the right way round: this feature
	 * downloads an executable and runs it with the application's credentials in its environment, and
	 * an agent whose publisher did not say what the bytes should be is a decision an operator should
	 * have to make in writing.
	 */
	public static RegistrySettings defaults() {
		return new RegistrySettings(DEFAULT_URL, defaultCache(), DEFAULT_REFRESH, false, true,
				DEFAULT_DOWNLOAD_TIMEOUT);
	}

	/**
	 * The cache, which is under the user's home rather than the temp directory.
	 *
	 * <p>Unlike {@code runtime-home}, which holds generated config a run can recreate for nothing,
	 * this holds hundreds of megabytes fetched over the network. A location that a reboot clears
	 * would mean re-downloading an agent on every machine restart.
	 */
	private static Path defaultCache() {
		String home = System.getProperty("user.home");
		Path base = home == null || home.isBlank() ? Paths.get(System.getProperty("java.io.tmpdir")) : Paths.get(home);
		return base.resolve(".spring-acp").resolve("agents").toAbsolutePath();
	}

	public Path snapshotFile() {
		return cache.resolve("registry.json");
	}
}
