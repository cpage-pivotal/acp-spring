package org.springaicommunity.acp.registry;

import java.util.List;
import java.util.Locale;

/**
 * The operating system and architecture pair the registry keys its binaries by.
 *
 * <p>The registry spells these {@code darwin-aarch64}, {@code linux-x86_64},
 * {@code windows-aarch64} and so on — Rust's target vocabulary, not the JVM's. {@code os.arch}
 * answers {@code aarch64} on one JVM and {@code arm64} on another for the same chip, and
 * {@code os.name} answers {@code Mac OS X}, so the translation is a table rather than a
 * {@code toLowerCase}.
 */
public record Platform(String os, String arch) {

	/** Spelled the way a registry key is. */
	public String id() {
		return os + "-" + arch;
	}

	/**
	 * Keys to try for this platform, most exact first.
	 *
	 * <p>An x86_64 entry is offered to an aarch64 mac because Rosetta runs it, and an agent that
	 * ships only an Intel build is still usable. Nothing is offered the other way: an arm binary on
	 * an Intel machine does not run, and failing at the download is better than failing at exec.
	 */
	public List<String> candidateKeys() {
		if (os.equals("darwin") && arch.equals("aarch64")) {
			return List.of(id(), "darwin-x86_64");
		}
		return List.of(id());
	}

	public static Platform current() {
		return new Platform(currentOs(), currentArch());
	}

	private static String currentOs() {
		String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (name.contains("mac") || name.contains("darwin")) {
			return "darwin";
		}
		if (name.contains("win")) {
			return "windows";
		}
		return "linux";
	}

	private static String currentArch() {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return switch (arch) {
			case "aarch64", "arm64" -> "aarch64";
			case "x86_64", "amd64" -> "x86_64";
			default -> arch;
		};
	}

	public boolean isWindows() {
		return os.equals("windows");
	}
}
