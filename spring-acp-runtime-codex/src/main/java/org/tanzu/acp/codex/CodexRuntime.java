package org.tanzu.acp.codex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.ProviderEnvironment;
import org.tanzu.acp.config.RuntimeOptions;
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.runtime.ToolNames;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Runs OpenAI's Codex behind the ACP adapter published as {@code @agentclientprotocol/codex-acp}.
 *
 * <p>Unlike Goose and OpenCode, Codex ships no ACP mode in its own binary; the registry entry
 * distributes an npm package instead, so the default launch is {@code npx}. That is a real cost —
 * the first start pays a download — and an application that would rather not can point
 * {@code spring.acp.runtimes.codex.command} at a locally installed adapter.
 *
 * <p>It is the only one of the three that advertises the {@code providers} capability, so a
 * configured provider reaches it through {@code providers/set} rather than an environment variable.
 * It also splits what other agents call "mode" in two: {@code mode} is the approval policy
 * (read-only, agent, agent-full-access) and {@code collaboration_mode} is plan-versus-build. Both
 * are offered to {@code spring.acp.mode}, approval policy first, and the resolver takes whichever
 * one actually has the requested value.
 *
 * <p>Tier-3 options, under {@code spring.acp.runtimes.codex}:
 *
 * <pre>{@code
 * package: "@agentclientprotocol/codex-acp@1.12.0"   # what npx runs
 * command: /usr/local/bin/codex-acp                  # instead of npx
 * args: [ --verbose ]
 * home: /var/lib/codex                               # CODEX_HOME
 * config-toml: { model_reasoning_effort: high }      # written into CODEX_HOME/config.toml
 * env: { RUST_LOG: warn }
 * }</pre>
 */
public class CodexRuntime implements AgentRuntime {

	public static final String ID = "codex";

	/** Pinned rather than floating: an agent that changes under a running application is not a feature. */
	public static final String DEFAULT_PACKAGE = "@agentclientprotocol/codex-acp@1.12.0";

	/** Codex reads its configuration, and keeps its credentials, under this directory. */
	public static final String HOME_ENV = "CODEX_HOME";

	private static final String CONFIG_FILE = "config.toml";

	/** Codex's credential store, which lives in the same directory as its config. */
	private static final String AUTH_FILE = "auth.json";

	private static final Logger logger = LoggerFactory.getLogger(CodexRuntime.class);

	@Override
	public String id() {
		return ID;
	}

	@Override
	public AgentLaunchSpec launch(AgentSettings settings) {
		RuntimeOptions options = settings.runtimeOptions();
		Optional<String> command = options.text("command");

		List<String> args = new ArrayList<>();
		if (command.isEmpty()) {
			// -y so a first run on a clean machine does not block on npx's install prompt.
			args.add("-y");
			args.add(options.text("package").orElse(DEFAULT_PACKAGE));
		}
		args.addAll(options.textList("args"));

		return new AgentLaunchSpec.Stdio(command.orElse("npx"), args, environment(settings));
	}

	/**
	 * Writes {@code config.toml}, but only into a home this library owns.
	 *
	 * <p>Codex keeps {@code auth.json} beside {@code config.toml}, so the two decisions are one: a
	 * relocated {@code CODEX_HOME} is also a relocated credential store. The ambient home is therefore
	 * inherited untouched unless the application asked for codex-specific config, and it is never
	 * written to — overwriting somebody's {@code ~/.codex/config.toml} from a server process is not a
	 * trade worth making for a convenience.
	 *
	 * <p>When the home does move, the credentials are linked across. Without that, setting one
	 * innocuous key — {@code config-toml.model_reasoning_effort: low} — makes a working application
	 * fail with "Authentication required", because the new home has no {@code auth.json}; that is a
	 * trap, and it was found by walking into it. A symbolic link rather than a copy, so the secret
	 * stays in one place and stays current if the user logs in again. On a platform there is no ambient
	 * home to link from and the key arrives through the environment instead, so nothing happens.
	 */
	@Override
	public void provision(AgentSettings settings) {
		Map<String, Object> config = settings.runtimeOptions().section("config-toml");
		if (config.isEmpty()) {
			return;
		}
		Path home = managedHome(settings);
		try {
			Files.createDirectories(home);
			Path file = home.resolve(CONFIG_FILE);
			Files.writeString(file, Toml.write(config), StandardCharsets.UTF_8);
			logger.debug("Wrote {} key(s) to {}", config.size(), file);
			linkCredentials(home);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not write " + CONFIG_FILE + " under " + home, ex);
		}
	}

	/** Points the managed home at the credentials the inherited one already holds, if it has any. */
	private void linkCredentials(Path managedHome) {
		Path inherited = inheritedHome();
		Path source = inherited.resolve(AUTH_FILE);
		Path target = managedHome.resolve(AUTH_FILE);
		if (inherited.equals(managedHome) || !Files.isReadable(source) || Files.exists(target)) {
			return;
		}
		try {
			Files.createSymbolicLink(target, source);
			logger.debug("Linked {} to {}", target, source);
		}
		catch (IOException | UnsupportedOperationException ex) {
			// Not fatal: an environment-supplied key does not need auth.json, and a platform that
			// forbids symlinks is exactly the kind that supplies one.
			logger.debug("Could not link {} into {}; Codex will need credentials from the environment", AUTH_FILE,
					managedHome, ex);
		}
	}

	/** Where Codex would look if this adapter did nothing. */
	private static Path inheritedHome() {
		String configured = System.getenv(HOME_ENV);
		return configured == null || configured.isBlank()
				? Path.of(System.getProperty("user.home"), ".codex").toAbsolutePath()
				: Path.of(configured).toAbsolutePath();
	}

	@Override
	public List<String> configIdsFor(PortableOption option) {
		// Verified against codex-acp 1.12.0.
		return switch (option) {
			case MODEL -> List.of("model");
			case MODE -> List.of("mode", "collaboration_mode");
			// No provider config option; the providers capability covers it.
			case PROVIDER -> List.of();
		};
	}

	/**
	 * The provider's credentials are on the process environment whether or not {@code providers/set}
	 * worked, so a provider request is never simply unsupported here.
	 */
	@Override
	public boolean appliedOutOfBand(PortableOption option, AgentSettings settings) {
		return option == PortableOption.PROVIDER && settings.provider().hasCredentials();
	}

	@Override
	public Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
		// Codex titles are human prose ("Explored the repository"), so no title fallback.
		return ToolNames.fromRawInput(toolCall);
	}

	private Map<String, String> environment(AgentSettings settings) {
		Map<String, String> env = new LinkedHashMap<>();
		RuntimeOptions options = settings.runtimeOptions();

		homeFor(settings).ifPresent(home -> env.put(HOME_ENV, home.toString()));
		env.putAll(ProviderEnvironment.of(settings.provider()));
		env.putAll(options.textSection("env"));
		return env;
	}

	/** The home to point Codex at, or empty to inherit whatever the process already has. */
	private Optional<Path> homeFor(AgentSettings settings) {
		RuntimeOptions options = settings.runtimeOptions();
		Optional<Path> explicit = options.text("home").map(Path::of).map(Path::toAbsolutePath);
		if (explicit.isPresent()) {
			return explicit;
		}
		return options.section("config-toml").isEmpty() ? Optional.empty() : Optional.of(managedHome(settings));
	}

	private Path managedHome(AgentSettings settings) {
		return settings.runtimeOptions().text("home").map(Path::of).map(Path::toAbsolutePath)
				.orElseGet(settings::runtimeHome);
	}
}
