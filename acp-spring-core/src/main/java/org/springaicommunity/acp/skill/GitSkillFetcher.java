package org.springaicommunity.acp.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springaicommunity.acp.config.SkillSpec;

/**
 * Fetches a skill with the {@code git} on the PATH: one commit, no history, and — when the skill is
 * a subdirectory — only that subdirectory's files.
 *
 * <p>The same sequence as the goose buildpack's {@code install_git_skill}, which is the reason for
 * {@code init} + {@code fetch} rather than {@code clone}: {@code clone --branch} cannot name a
 * commit, and a commit is the form a skill should be pinned in. Hooks are switched off and prompts
 * are refused, because this runs unattended against a repository somebody else controls.
 */
public class GitSkillFetcher {

	private static final Duration TIMEOUT = Duration.ofMinutes(2);

	/**
	 * The user name sent with a token. GitHub requires this one for an installation token and ignores
	 * it for a personal one; GitLab accepts any name with a personal access token.
	 */
	private static final String TOKEN_USER = "x-access-token";

	private final String git;

	private final Map<String, String> inherited;

	public GitSkillFetcher() {
		this("git");
	}

	public GitSkillFetcher(String git) {
		this(git, System.getenv());
	}

	GitSkillFetcher(String git, Map<String, String> inherited) {
		this.git = git;
		this.inherited = inherited;
	}

	public SkillFetcher.Fetched fetch(SkillSpec.Git skill, Path into) {
		Map<String, String> env = credentials(skill, inherited);
		run(into, env, "init", "-q");
		run(into, env, "remote", "add", "origin", skill.url().toString());
		run(into, env, "fetch", "-q", "--depth", "1", "--filter=blob:none", "origin",
				skill.ref() == null ? "HEAD" : skill.ref());
		if (skill.path() != null) {
			run(into, env, "sparse-checkout", "set", "--no-cone", "/" + skill.path() + "/");
		}
		run(into, env, "-c", "advice.detachedHead=false", "checkout", "-q", "--detach", "FETCH_HEAD");
		String commit = run(into, env, "rev-parse", "HEAD").strip();
		return new SkillFetcher.Fetched(skill.path() == null ? into : into.resolve(skill.path()), commit);
	}

	/**
	 * The token as an {@code http.<url>.extraHeader}, passed through git's environment-borne config.
	 *
	 * <p>That route, and not the obvious ones, because of where each leaves the token: in the URL it
	 * is logged and stored in {@code .git/config}; as a {@code -c} argument it is in the process list;
	 * as a credential helper it is written somewhere. The environment of one short-lived child process
	 * is none of those. Scoped to this repository's URL, so a redirect elsewhere does not carry it,
	 * and appended after whatever {@code GIT_CONFIG_*} entries this process already has, so an
	 * operator's own survive.
	 */
	static Map<String, String> credentials(SkillSpec.Git skill, Map<String, String> inherited) {
		if (skill.token() == null) {
			return Map.of();
		}
		int index = parseCount(inherited.get("GIT_CONFIG_COUNT"));
		String basic = Base64.getEncoder()
			.encodeToString((TOKEN_USER + ":" + skill.token()).getBytes(StandardCharsets.UTF_8));
		Map<String, String> env = new LinkedHashMap<>();
		env.put("GIT_CONFIG_COUNT", String.valueOf(index + 1));
		env.put("GIT_CONFIG_KEY_" + index, "http." + skill.url() + ".extraHeader");
		env.put("GIT_CONFIG_VALUE_" + index, "Authorization: Basic " + basic);
		return env;
	}

	private static int parseCount(String count) {
		try {
			return count == null ? 0 : Math.max(0, Integer.parseInt(count.strip()));
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

	private String run(Path directory, Map<String, String> env, String... arguments) {
		List<String> command = new ArrayList<>(List.of(git, "-c", "core.hooksPath=/dev/null"));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
		builder.environment().put("GIT_TERMINAL_PROMPT", "0");
		builder.environment().putAll(env);
		try {
			Process process = builder.start();
			// Drained before waiting: a git that fills the pipe would otherwise never exit.
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new SkillInstallException("git " + arguments[0] + " did not finish within " + TIMEOUT);
			}
			if (process.exitValue() != 0) {
				throw new SkillInstallException("git " + arguments[0] + " failed: " + output.strip());
			}
			return output;
		}
		catch (IOException ex) {
			throw new SkillInstallException("Could not run '" + git + "'; installing a skill needs git on the PATH",
					ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SkillInstallException("Interrupted while running git " + arguments[0], ex);
		}
	}
}
