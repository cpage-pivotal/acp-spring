package org.thought.acp.registry;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One agent as the ACP registry publishes it.
 *
 * <p>Only the fields that decide how to start it are modelled. The description, icon, authors and
 * licence are catalogue copy for a human choosing an agent, and a server-side library choosing one
 * from a property has no use for them.
 */
public record RegistryEntry(String id, String name, String version, Distribution distribution) {

	public RegistryEntry {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("registry entry has no id");
		}
	}

	/** The three ways the registry says an agent can be obtained. */
	public sealed interface Distribution {

		/** Extra environment the registry says this agent needs, which is usually none. */
		Map<String, String> env();

		/**
		 * A platform-specific archive to download, verify and unpack.
		 *
		 * <p>Keyed by {@link Platform#id()}. An agent publishing no build for the running platform is
		 * unusable here, and says so at launch rather than at startup.
		 */
		record Binary(Map<String, Artifact> artifacts) implements Distribution {

			public Binary {
				artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
			}

			@Override
			public Map<String, String> env() {
				return Map.of();
			}

			/** The build for {@code platform}, trying its fallbacks in order. */
			public Optional<Artifact> forPlatform(Platform platform) {
				return platform.candidateKeys().stream().map(artifacts::get).filter(java.util.Objects::nonNull)
						.findFirst();
			}
		}

		/** {@code npx -y <package> <args>}: the agent is a published npm package. */
		record Npx(String packageSpec, List<String> args, Map<String, String> env) implements Distribution {

			public Npx {
				args = args == null ? List.of() : List.copyOf(args);
				env = env == null ? Map.of() : Map.copyOf(env);
			}
		}

		/** {@code uvx <package> <args>}: the same idea on PyPI. */
		record Uvx(String packageSpec, List<String> args, Map<String, String> env) implements Distribution {

			public Uvx {
				args = args == null ? List.of() : List.copyOf(args);
				env = env == null ? Map.of() : Map.copyOf(env);
			}
		}
	}

	/**
	 * One downloadable build.
	 *
	 * @param archive where to fetch it
	 * @param command the executable inside it, relative to where it unpacks
	 * @param args what to pass the executable to make it speak ACP, usually {@code ["acp"]}
	 * @param sha256 the expected digest, or {@code null} — which 9 of the registry's 19 binary
	 * agents publish, and is the whole reason {@code require-checksum} exists as a property
	 */
	public record Artifact(URI archive, String command, List<String> args, Map<String, String> env, String sha256) {

		public Artifact {
			if (archive == null) {
				throw new IllegalArgumentException("registry artifact has no archive url");
			}
			args = args == null ? List.of() : List.copyOf(args);
			env = env == null ? Map.of() : Map.copyOf(env);
		}

		public Optional<String> findSha256() {
			return Optional.ofNullable(sha256).filter(s -> !s.isBlank());
		}
	}
}
