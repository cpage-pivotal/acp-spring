package org.tanzu.acp.config;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.runtime.AgentRuntime.PortableOption;
import org.tanzu.acp.session.AgentSession;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;

/**
 * Applies the negotiated tier — {@code model}, {@code provider}, {@code mode} — to a new session.
 *
 * <p>These are requests, not assignments. ACP's {@code session/set_config_option} is a standard
 * method, but the option ids and values it accepts are declared by the agent, so a portable
 * {@code model: claude-sonnet-5} cannot simply be pushed. What happens when an agent cannot honor
 * one is {@link OnUnsupported}'s decision, not this class's.
 *
 * <p><strong>Why this sets before it reads.</strong> The obvious design is to read the agent's
 * advertised {@code configOptions} from the {@code session/new} response and match against them.
 * That is not available: {@code NewSessionResponse} in the ACP Java SDK models {@code sessionId},
 * {@code modes} and {@code models} but not {@code configOptions}, and the record ignores unknown
 * properties — so options a live agent does return are dropped before we could see them. The
 * response to {@code session/set_config_option} does carry the full set. So the resolver sets
 * optimistically and learns the agent's real configuration from what comes back, caching it on the
 * session. An agent that rejects the id tells us, by erroring, that the option is unsupported.
 */
public final class ConfigResolver {

	private static final Logger logger = LoggerFactory.getLogger(ConfigResolver.class);

	private final AgentRuntime runtime;

	private final OnUnsupported onUnsupported;

	/** One warning per option per runtime, not one per session. */
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	public ConfigResolver(AgentRuntime runtime, OnUnsupported onUnsupported) {
		this.runtime = runtime;
		this.onUnsupported = onUnsupported == null ? OnUnsupported.WARN : onUnsupported;
	}

	/** Applies every requested option to {@code session}, in a stable order. */
	public Mono<Void> apply(AcpAsyncClient client, AgentSession session, AgentSettings settings) {
		return set(client, session, PortableOption.PROVIDER, settings.provider())
				.then(set(client, session, PortableOption.MODEL, settings.model()))
				.then(set(client, session, PortableOption.MODE, settings.mode()));
	}

	private Mono<Void> set(AcpAsyncClient client, AgentSession session, PortableOption option, String value) {
		if (value == null || value.isBlank()) {
			return Mono.empty();
		}
		List<String> configIds = runtime.configIdsFor(option);
		return attempt(client, session, option, value, configIds, 0);
	}

	/**
	 * Tries each candidate config id in turn. A runtime may name an option differently from the
	 * portable name, and only the agent can say which id it accepts.
	 */
	private Mono<Void> attempt(AcpAsyncClient client, AgentSession session, PortableOption option, String value,
			List<String> configIds, int index) {
		if (index >= configIds.size()) {
			return unsupported(option, value, null);
		}
		String configId = configIds.get(index);
		return client
				.setSessionConfigOption(
						new AcpSchema.SetSessionConfigOptionRequest(session.sessionId(), configId, value, null, null))
				.doOnNext(response -> {
					session.configOptions(response.configOptions());
					logger.debug("Set {}='{}' on session {} via config id '{}'", option, value, session.sessionId(),
							configId);
				})
				.then()
				.onErrorResume(error -> index + 1 < configIds.size()
						? attempt(client, session, option, value, configIds, index + 1)
						: unsupported(option, value, error));
	}

	private Mono<Void> unsupported(PortableOption option, String value, Throwable cause) {
		return switch (onUnsupported) {
			case FAIL -> Mono.error(
					new UnsupportedAgentOptionException(option.name().toLowerCase(java.util.Locale.ROOT), value,
							runtime.id(), cause));
			case WARN -> Mono.fromRunnable(() -> warnOnce(option, value));
			case IGNORE -> Mono.empty();
		};
	}

	private void warnOnce(PortableOption option, String value) {
		if (warned.add(runtime.id() + '/' + option)) {
			logger.warn("Runtime '{}' does not support {}='{}'; continuing with the agent's own default. "
					+ "Set spring.acp.on-unsupported=fail to make this an error.", runtime.id(),
					option.name().toLowerCase(java.util.Locale.ROOT), value);
		}
	}

	/** The agent's configuration as last observed, for callers that want to inspect it. */
	public static Optional<AcpSchema.SessionConfigOption> find(AgentSession session, String configId) {
		return session.configOptions().stream()
				.filter(o -> o instanceof AcpSchema.SessionConfigSelect s && configId.equals(s.id())).findFirst();
	}
}
