package org.springaicommunity.acp.config;

import java.util.Locale;
import java.util.regex.Pattern;

/** The checks both kinds of {@link SkillSpec} share. */
final class SkillRules {

	/**
	 * The buildpack's rule: a directory name every agent and every filesystem is happy
	 * with.
	 */
	private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	private SkillRules() {
	}

	static String name(String name, String source) {
		String chosen = blankToNull(name);
		if (chosen == null) {
			String trimmed = source == null ? "" : source.replaceAll("/+$", "");
			String last = trimmed.substring(trimmed.lastIndexOf('/') + 1);
			chosen = last.endsWith(".git") ? last.substring(0, last.length() - 4) : last;
		}
		if (!NAME.matcher(chosen).matches()) {
			throw new IllegalArgumentException("skill name must match " + NAME.pattern() + " but was '" + chosen + "'");
		}
		return chosen;
	}

	static String sha256(String sha256) {
		String value = blankToNull(sha256);
		if (value == null) {
			return null;
		}
		value = value.toLowerCase(Locale.ROOT);
		if (!SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException("skill sha256 must be 64 hex characters");
		}
		return value;
	}

	/**
	 * Relative, normalized and inside its root. The path is resolved against a checkout
	 * or a classpath, so {@code ..} or a leading slash would be a way out of it.
	 */
	static String requireSafeRelativePath(String path) {
		if (path == null) {
			throw new IllegalArgumentException("skill path must not be blank");
		}
		String trimmed = path.replaceAll("/+$", "");
		if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.contains("\\") || trimmed.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("skill path must be relative but was '" + path + "'");
		}
		for (String segment : trimmed.split("/")) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
				throw new IllegalArgumentException("skill path must be normalized but was '" + path + "'");
			}
		}
		return trimmed;
	}

	static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
