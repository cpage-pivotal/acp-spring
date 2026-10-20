package org.springaicommunity.acp.config;

/**
 * What to do when a portable option cannot be honored by the selected runtime.
 *
 * <p>
 * ACP standardizes the conversation, not the provisioning, so {@code model},
 * {@code provider} and {@code mode} are requests rather than assignments. This is the
 * knob that decides whether an unhonored request is a failure or a footnote.
 */
public enum OnUnsupported {

	/**
	 * Throw. Correct when the option is load-bearing — a cheap model is not a fallback
	 * for an expensive one.
	 */
	FAIL,

	/** Log once per option per runtime and carry on. The default. */
	WARN,

	/** Say nothing. */
	IGNORE

}
