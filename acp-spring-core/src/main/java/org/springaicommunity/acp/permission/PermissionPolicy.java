package org.springaicommunity.acp.permission;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Decides how to answer an agent's {@code session/request_permission}.
 *
 * <p>An unanswered permission request stalls the turn forever, so every policy must return an
 * answer for every request. The default is {@link #deny()}: this library runs server-side, where
 * there is no human at a keyboard to approve a file write, and an agent that cannot get permission
 * degrades rather than doing something unreviewed.
 */
@FunctionalInterface
public interface PermissionPolicy {

	/**
	 * @param toolName the tool being requested, when the agent made it knowable
	 * @param options the choices the agent offered; never empty in practice
	 * @return the option to select, or empty to cancel the request
	 */
	Optional<AcpSchema.PermissionOption> decide(Optional<String> toolName, List<AcpSchema.PermissionOption> options);

	/** Rejects everything. */
	static PermissionPolicy deny() {
		return (toolName, options) -> pick(options, AcpSchema.PermissionOptionKind.REJECT_ONCE,
				AcpSchema.PermissionOptionKind.REJECT_ALWAYS);
	}

	/** Approves everything. Appropriate only in a sandbox you are willing to lose. */
	static PermissionPolicy autoApprove() {
		return (toolName, options) -> pick(options, AcpSchema.PermissionOptionKind.ALLOW_ONCE,
				AcpSchema.PermissionOptionKind.ALLOW_ALWAYS);
	}

	/**
	 * Approves exactly the named tools and rejects the rest. A request whose tool name the agent did
	 * not make knowable is rejected — an allowlist that cannot identify what it is admitting is not
	 * an allowlist.
	 */
	static PermissionPolicy allowlist(Set<String> allowedTools) {
		Set<String> allowed = allowedTools.stream().map(t -> t.toLowerCase(Locale.ROOT))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return (toolName, options) -> {
			boolean permit = toolName.map(n -> allowed.contains(n.toLowerCase(Locale.ROOT))).orElse(false);
			return permit ? autoApprove().decide(toolName, options) : deny().decide(toolName, options);
		};
	}

	private static Optional<AcpSchema.PermissionOption> pick(List<AcpSchema.PermissionOption> options,
			AcpSchema.PermissionOptionKind preferred, AcpSchema.PermissionOptionKind fallback) {
		if (options == null || options.isEmpty()) {
			return Optional.empty();
		}
		return options.stream().filter(o -> o.kind() == preferred).findFirst()
				.or(() -> options.stream().filter(o -> o.kind() == fallback).findFirst());
	}
}
