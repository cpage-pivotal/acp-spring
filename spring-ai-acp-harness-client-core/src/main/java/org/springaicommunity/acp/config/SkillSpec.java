package org.springaicommunity.acp.config;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * A skill to install into the workspace before the agent starts: one directory holding a
 * {@code SKILL.md}, either bundled with the application or taken from a Git repository.
 *
 * <p>
 * Portable, although ACP says nothing about skills. The Agent Skills layout — a directory
 * per skill under {@code .agents/skills/} in the agent's working directory — is read by
 * goose, opencode and codex alike, so installing one needs no adapter.
 *
 * <p>
 * The rules are the ones the goose buildpack's {@code skills_configurator.sh} arrived at,
 * with one relaxation: a Git skill's {@code ref} is optional. See {@link Git}.
 */
public sealed interface SkillSpec {

	/** The directory the skill is installed as. */
	String name();

	/**
	 * The expected SHA-256 of the skill's {@code SKILL.md}, or {@code null} to not check.
	 */
	String sha256();

	/**
	 * A one-line, log-safe description of where this skill comes from. Never includes a
	 * token.
	 */
	String describe();

	/**
	 * A skill packaged with the application, found on the classpath: inside the
	 * application's jar once it is built, under {@code target/classes} when it runs from
	 * an IDE. The default way to ship a skill, because it travels with the build that was
	 * tested with it.
	 *
	 * @param name defaults to the last segment of {@code path}
	 * @param path the skill's directory on the classpath, e.g. {@code skills/mailgun} for
	 * {@code src/main/resources/skills/mailgun}
	 */
	record Bundled(String name, String path, String sha256) implements SkillSpec {

		public Bundled {
			path = SkillRules.requireSafeRelativePath(SkillRules.blankToNull(path));
			sha256 = SkillRules.sha256(sha256);
			name = SkillRules.name(name, path);
		}

		public static Bundled of(String path) {
			return new Bundled(null, path, null);
		}

		@Override
		public String describe() {
			return name + " (classpath:" + path + ")";
		}
	}

	/**
	 * A skill taken from a Git repository over HTTPS.
	 *
	 * <p>
	 * Without a {@code ref}, the repository's default branch is fetched, which means the
	 * skill can change between two starts of the same application; the installer says so.
	 * A full 40-character commit is the pinned form, and is checked against what Git
	 * actually resolved.
	 *
	 * @param name defaults to the last segment of {@code path}, or the repository name
	 * @param url the repository, over HTTPS and without credentials in it
	 * @param ref a commit, branch or tag, or {@code null} for the default branch
	 * @param path the skill's directory inside the repository, or {@code null} for its
	 * root
	 * @param token a token for a private repository, sent as HTTP Basic credentials, or
	 * {@code null}
	 */
	record Git(String name, URI url, String ref, String path, String token, String sha256) implements SkillSpec {

		private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{40}");

		/**
		 * A branch or tag Git will accept, and nothing an argument parser could read as
		 * an option.
		 */
		private static final Pattern REF = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._/-]{0,254}");

		public Git {
			Validation.requireSecureUrl(url, "skill url");
			ref = SkillRules.blankToNull(ref);
			if (ref != null && (!REF.matcher(ref).matches() || ref.contains(".."))) {
				throw new IllegalArgumentException("skill ref must be a commit, branch or tag but was '" + ref + "'");
			}
			path = SkillRules.blankToNull(path);
			if (path != null) {
				path = SkillRules.requireSafeRelativePath(path);
			}
			token = SkillRules.blankToNull(token);
			if (token != null) {
				Validation.requireSecret(token, "skill token");
			}
			sha256 = SkillRules.sha256(sha256);
			name = SkillRules.name(name, path != null ? path : url.getPath());
		}

		public static Git of(URI url, String path) {
			return new Git(null, url, null, path, null, null);
		}

		/** Whether {@code ref} names one commit, rather than something that can move. */
		public boolean isPinned() {
			return ref != null && COMMIT.matcher(ref).matches();
		}

		@Override
		public String describe() {
			return name + " (" + url + (path == null ? "" : " " + path) + (ref == null ? "" : " @ " + ref) + ")";
		}

		/** A record prints every component, and one of these is a credential. */
		@Override
		public String toString() {
			return "Git[" + describe() + (token == null ? "" : ", token=<redacted>") + "]";
		}
	}

}
