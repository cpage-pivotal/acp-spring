package org.tanzu.acp.registry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads an agent named by the registry, checks it is the bytes the registry said, and unpacks
 * it somewhere it can be run from.
 *
 * <p><strong>The digest is the point of this class, not a detail of it.</strong> Everything else
 * here is plumbing around one decision: this library is about to run a downloaded executable in a
 * process holding the application's model credentials, with the application's workspace as its
 * working directory. Verifying it against the digest the registry publishes is the only thing
 * standing between that and trusting a CDN and a release host.
 *
 * <p>Which is why {@code requireChecksum} defaults to on even though 9 of the registry's 19 binary
 * agents publish no digest at all. Those nine are reachable, by an operator writing
 * {@code require-checksum: false} and meaning it. The failure names the agent and says exactly that,
 * because a refusal an operator cannot act on is just an outage.
 *
 * <p>Installed agents are cached by id, version and digest, so a second application on the same
 * machine — or the same application after a restart — starts without a download. A directory
 * already holding the command is used as it is; nothing revalidates bytes it verified before
 * writing.
 */
public class AgentInstaller {

	private static final Logger logger = LoggerFactory.getLogger(AgentInstaller.class);

	private final RegistrySettings settings;

	/** One install per artifact per JVM, so two threads starting an agent do not race on the files. */
	private final Map<String, Path> installed = new ConcurrentHashMap<>();

	public AgentInstaller(RegistrySettings settings) {
		this.settings = settings == null ? RegistrySettings.defaults() : settings;
	}

	/**
	 * The executable for {@code artifact}, downloading and unpacking it if this machine has not got
	 * it already.
	 *
	 * @return an absolute path to a runnable file
	 */
	public Path install(RegistryEntry entry, RegistryEntry.Artifact artifact) {
		String key = entry.id() + "@" + version(entry, artifact);
		return installed.computeIfAbsent(key, k -> doInstall(entry, artifact, k));
	}

	/**
	 * What to call this install on disk.
	 *
	 * <p>The digest when there is one, so a republished archive under an unchanged version number
	 * is a different directory rather than a stale cache hit. The version alone when there is not,
	 * which is the cost of the entries that publish no digest.
	 */
	private String version(RegistryEntry entry, RegistryEntry.Artifact artifact) {
		return artifact.findSha256().map(sha -> sha.substring(0, Math.min(16, sha.length())))
				.orElseGet(() -> entry.version() == null ? "unversioned" : entry.version());
	}

	private Path doInstall(RegistryEntry entry, RegistryEntry.Artifact artifact, String key) {
		Path home = settings.cache().resolve(key.replace('@', '-').replaceAll("[^A-Za-z0-9._-]", "_"));
		Path command = home.resolve(normalize(artifact.command()));

		if (Files.isRegularFile(command)) {
			logger.debug("Agent '{}' is already installed at {}", entry.id(), command);
			Archives.makeExecutable(command);
			return command.toAbsolutePath();
		}

		String sha256 = artifact.findSha256().orElse(null);
		if (sha256 == null && settings.requireChecksum()) {
			throw new AgentInstallException("The ACP registry publishes no sha256 for '" + entry.id() + "' on "
					+ Platform.current().id() + ", and spring.acp.registry.require-checksum is on."
					+ " Set spring.acp.registry.require-checksum=false to install it unverified,"
					+ " or install the agent yourself and select it with a runtime adapter");
		}
		if (settings.offline()) {
			throw new AgentInstallException("Agent '" + entry.id() + "' is not in the cache at " + settings.cache()
					+ " and spring.acp.registry.offline is on, so it cannot be downloaded");
		}

		Path staging = null;
		try {
			Files.createDirectories(settings.cache());
			staging = Files.createTempDirectory(settings.cache(), "installing-");
			Path archive = staging.resolve("download");

			logger.info("Installing ACP agent '{}' {} from {}", entry.id(), entry.version(), artifact.archive());
			String actual = download(artifact.archive(), archive);
			if (sha256 != null && !sha256.equalsIgnoreCase(actual)) {
				throw new AgentInstallException("The download for '" + entry.id() + "' from " + artifact.archive()
						+ " hashes to " + actual + " but the registry says " + sha256
						+ "; refusing to run it");
			}
			if (sha256 == null) {
				logger.warn("Installed '{}' from {} without verifying it: the registry publishes no sha256"
						+ " and require-checksum is off. Its sha256 is {}", entry.id(), artifact.archive(), actual);
			}

			Path unpacked = staging.resolve("unpacked");
			Files.createDirectories(unpacked);
			Archives.extract(archive, unpacked, fileName(artifact.archive()));
			Files.deleteIfExists(archive);

			Path unpackedCommand = unpacked.resolve(normalize(artifact.command()));
			if (!Files.isRegularFile(unpackedCommand)) {
				throw new AgentInstallException("The archive for '" + entry.id() + "' does not contain '"
						+ artifact.command() + "'; it holds " + listing(unpacked));
			}
			Archives.makeExecutable(unpackedCommand);

			// Moved into place as one step, so a half-written install is never a usable one.
			deleteRecursively(home);
			Files.createDirectories(home.getParent());
			Files.move(unpacked, home, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			return command.toAbsolutePath();
		}
		catch (IOException ex) {
			throw new AgentInstallException("Could not install ACP agent '" + entry.id() + "'", ex);
		}
		finally {
			deleteRecursively(staging);
		}
	}

	/** Streams the download to disk, hashing as it goes. Returns the digest of what arrived. */
	private String download(URI url, Path target) throws IOException {
		if ("file".equalsIgnoreCase(url.getScheme())) {
			Files.copy(Path.of(url), target);
			return sha256Of(target);
		}
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL).build()) {
			HttpResponse<InputStream> response = client.send(
					HttpRequest.newBuilder(url).timeout(settings.downloadTimeout()).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				throw new AgentInstallException("Downloading " + url + " answered HTTP " + response.statusCode());
			}
			MessageDigest digest = sha256();
			try (InputStream in = response.body(); var out = Files.newOutputStream(target)) {
				byte[] buffer = new byte[16384];
				int read;
				while ((read = in.read(buffer)) > 0) {
					digest.update(buffer, 0, read);
					out.write(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AgentInstallException("Interrupted downloading " + url, ex);
		}
	}

	private static String sha256Of(Path file) throws IOException {
		MessageDigest digest = sha256();
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[16384];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every JVM", ex);
		}
	}

	/**
	 * The registry writes commands as {@code ./goose} and, on Windows,
	 * {@code ./goose-package\goose.exe}. Both are relative to where the archive unpacked.
	 */
	private static String normalize(String command) {
		if (command == null || command.isBlank()) {
			throw new AgentInstallException("The registry entry names no command to run");
		}
		String normalized = command.replace('\\', '/');
		while (normalized.startsWith("./")) {
			normalized = normalized.substring(2);
		}
		if (normalized.startsWith("/") || normalized.contains("..")) {
			throw new AgentInstallException("The registry entry's command '" + command + "' is not a relative path");
		}
		return normalized;
	}

	private static String fileName(URI archive) {
		String path = archive.getPath() == null ? "" : archive.getPath();
		int slash = path.lastIndexOf('/');
		String name = slash < 0 ? path : path.substring(slash + 1);
		return name.toLowerCase(Locale.ROOT);
	}

	/** What the archive actually held, for an entry whose {@code cmd} does not match its contents. */
	private static String listing(Path root) {
		try (var paths = Files.walk(root, 2)) {
			return paths.filter(p -> !p.equals(root)).map(root::relativize).map(Path::toString).sorted().limit(20)
					.toList().toString();
		}
		catch (IOException ex) {
			return "(unreadable)";
		}
	}

	private static void deleteRecursively(Path directory) {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ignored) {
					// Best effort: a leftover staging directory costs disk, not correctness.
				}
			});
		}
		catch (IOException ignored) {
			// Likewise.
		}
	}

	/** An agent that could not be obtained, or could not be trusted once it was. */
	public static class AgentInstallException extends RuntimeException {

		public AgentInstallException(String message) {
			super(message);
		}

		public AgentInstallException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
