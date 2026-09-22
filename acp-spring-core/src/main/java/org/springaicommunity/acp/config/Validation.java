package org.springaicommunity.acp.config;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Input validation shared by the configuration records.
 *
 * <p>These rules are carried over from the Goose wrapper this library succeeds, where they were
 * arrived at the hard way. They are strict on purpose: everything here ends up in a subprocess
 * environment, a config file on disk, or an outbound HTTP header.
 */
public final class Validation {

	/** Session and MCP server names. Bounded, and safe as a filename or a config-file key. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

	/** POSIX environment variable names. */
	private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	/** RFC 7230 token, the legal character set for a header field name. */
	private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

	/** An API key larger than this is a configuration mistake, not a credential. */
	private static final int MAX_SECRET_LENGTH = 16 * 1024;

	/** Platform-internal routes that are allowed to be plain HTTP. */
	private static final String INTERNAL_SUFFIX = ".apps.internal";

	private Validation() {
	}

	public static String requireText(String value, String what) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(what + " must not be blank");
		}
		return value;
	}

	public static String requireName(String value, String what) {
		requireText(value, what);
		if (!NAME.matcher(value).matches()) {
			throw new IllegalArgumentException(
					what + " must match " + NAME.pattern() + " but was '" + value + "'");
		}
		return value;
	}

	public static String requireEnvName(String key) {
		requireText(key, "environment variable name");
		if (!ENV_NAME.matcher(key).matches()) {
			throw new IllegalArgumentException("environment variable name must match " + ENV_NAME.pattern()
					+ " but was '" + key + "'");
		}
		return key;
	}

	public static String requireHeaderName(String key) {
		requireText(key, "header name");
		if (!HEADER_NAME.matcher(key).matches()) {
			throw new IllegalArgumentException("header name must be an RFC 7230 token but was '" + key + "'");
		}
		return key;
	}

	/**
	 * Rejects CR and LF in a header value. A newline here is header injection, and the value is
	 * usually a credential, so the message names the header but never the value.
	 */
	public static String requireHeaderValue(String name, String value) {
		if (value == null) {
			throw new IllegalArgumentException("header '" + name + "' must have a value");
		}
		if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("header '" + name + "' must not contain CR or LF");
		}
		return value;
	}

	/**
	 * Bounds a credential and rejects line breaks in it. Both matter because the value is about to be
	 * written into a subprocess environment or a config file on disk, and a newline there can end the
	 * line early and change the meaning of what follows. The message never names the value.
	 */
	public static String requireSecret(String value, String what) {
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException(what + " must not be empty");
		}
		if (value.length() > MAX_SECRET_LENGTH) {
			throw new IllegalArgumentException(what + " must not exceed " + MAX_SECRET_LENGTH + " characters");
		}
		if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
			throw new IllegalArgumentException(what + " must not contain CR or LF");
		}
		return value;
	}

	/**
	 * Requires HTTPS, with two exceptions that a platform genuinely needs: loopback, and internal
	 * container-to-container routes. Userinfo is rejected outright — credentials belong in a header,
	 * not a URL that will be logged.
	 */
	public static URI requireSecureUrl(URI url, String what) {
		if (url == null) {
			throw new IllegalArgumentException(what + " must not be null");
		}
		if (url.getHost() == null) {
			throw new IllegalArgumentException(what + " must be absolute with a host but was '" + url + "'");
		}
		if (url.getUserInfo() != null) {
			throw new IllegalArgumentException(what + " must not embed credentials");
		}
		String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(java.util.Locale.ROOT);
		if (scheme.equals("https")) {
			return url;
		}
		if (scheme.equals("http") && isLocalOrInternal(url.getHost())) {
			return url;
		}
		throw new IllegalArgumentException(
				what + " must use https (plain http is allowed only for loopback and " + INTERNAL_SUFFIX
						+ ") but was '" + url + "'");
	}

	private static boolean isLocalOrInternal(String host) {
		String h = host.toLowerCase(java.util.Locale.ROOT);
		return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]")
				|| h.endsWith(INTERNAL_SUFFIX);
	}
}
