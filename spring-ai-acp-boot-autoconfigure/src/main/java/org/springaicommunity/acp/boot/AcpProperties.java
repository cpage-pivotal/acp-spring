package org.springaicommunity.acp.boot;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springaicommunity.acp.config.McpServerSpec;
import org.springaicommunity.acp.config.SkillSpec;
import org.springaicommunity.acp.config.McpSettings;
import org.springaicommunity.acp.config.OnUnsupported;
import org.springaicommunity.acp.config.PoolSettings;
import org.springaicommunity.acp.config.ProviderSpec;
import org.springaicommunity.acp.config.RuntimeOptions;
import org.springaicommunity.acp.permission.PermissionPolicy;
import org.springaicommunity.acp.permission.PermissionPrompt;
import org.springaicommunity.acp.protocol.AcpProtocol;
import org.springaicommunity.acp.protocol.ProtocolSettings;
import org.springaicommunity.acp.workspace.FileSystemAccess;
import org.springaicommunity.acp.workspace.TerminalAccess;

/**
 * Binds {@code spring.acp.*}.
 *
 * <p>
 * The three tiers of the configuration model show up here as three shapes. Portable
 * options are plain properties. Negotiated options — {@code model}, {@code provider},
 * {@code mode} — look identical but are requests the agent may decline, which is what
 * {@code on-unsupported} governs. Runtime-specific options live under
 * {@code runtimes.<id>} and are passed to that adapter untouched.
 *
 * @param runtime Which agent to run, e.g. goose. Must match a registered AgentRuntime or
 * an agent a runtime provider supplies. May be left unset when exactly one runtime
 * adapter is on the classpath.
 * @param workspace Absolute path the agent treats as its working directory. Defaults to
 * the JVM's.
 * @param runtimeHome Where an adapter may write the config files its agent reads at
 * startup. Never the workspace. Defaults to a directory under the JVM's temp directory,
 * named for the runtime.
 * @param timeout How long a single turn may take.
 * @param mode Requested session mode, e.g. Goose's auto, approve, chat.
 * @param model Requested model. Honored only if the agent exposes one; see
 * on-unsupported.
 * @param onUnsupported What to do when the runtime cannot honor model, provider or mode.
 * @param mcpServers MCP servers offered to every session. Passed through ACP verbatim.
 * @param skills Skills installed into the workspace's .agents/skills before the agent
 * starts, each one a directory holding a SKILL.md: bundled on the classpath (path only),
 * or in a Git repository (url).
 * @param runtimes Runtime-specific options, keyed by runtime id. Ignored by every other
 * runtime.
 * <p>
 * The value type is {@code Object} rather than {@code String} because tier 3 passes an
 * agent's own configuration through untouched, and that is not always flat —
 * {@code codex.config-toml} is a table and {@code opencode.config} is a JSON document.
 * {@link RuntimeOptions} normalizes whichever shape the binder produces.
 */
// @param mode stays above @param model: the configuration processor finds a parameter's
// description by prefix, so below it mode would be described as model.
@ConfigurationProperties(prefix = "spring.acp")
public record AcpProperties(String runtime, Path workspace, Path runtimeHome, @DefaultValue("5m") Duration timeout,
		String model, @DefaultValue Provider provider, String mode, @DefaultValue("warn") OnUnsupported onUnsupported,
		@DefaultValue List<McpServer> mcpServers, @DefaultValue Mcp mcp, @DefaultValue List<Skill> skills,
		@DefaultValue Permissions permissions, @DefaultValue FileSystem filesystem, @DefaultValue Terminal terminal,
		@DefaultValue Pool pool, @DefaultValue Controller controller, @DefaultValue Protocol protocol,
		@DefaultValue Observations observations, @DefaultValue Registry registry, @DefaultValue TanzuAi tanzuAi,
		@DefaultValue Map<String, Map<String, Object>> runtimes) {

	public List<McpServerSpec> toMcpServerSpecs() {
		return mcpServers.stream().map(McpServer::toSpec).toList();
	}

	public List<SkillSpec> toSkillSpecs() {
		return skills.stream().map(Skill::toSpec).toList();
	}

	public McpSettings toMcpSettings() {
		return new McpSettings(mcp.onServerFailure(), mcp.detectTimeout());
	}

	public PermissionPolicy toPermissionPolicy() {
		return toPermissionPolicy(null);
	}

	/**
	 * @param prompt who {@code policy: ask} asks, or null when nobody can be asked;
	 * required for {@code ask} and ignored by every other policy
	 */
	public PermissionPolicy toPermissionPolicy(PermissionPrompt prompt) {
		return permissions.toPolicy(prompt);
	}

	public ProviderSpec toProviderSpec() {
		return provider.toSpec();
	}

	public RuntimeOptions optionsFor(String runtimeId) {
		return RuntimeOptions.of(runtimes.getOrDefault(runtimeId, Map.of()));
	}

	public FileSystemAccess toFileSystemAccess() {
		return filesystem.toAccess();
	}

	public TerminalAccess toTerminalAccess() {
		return terminal.toAccess();
	}

	public PoolSettings toPoolSettings() {
		return pool.toSettings();
	}

	public ProtocolSettings toProtocolSettings() {
		return protocol.toSettings();
	}

	public enum PermissionMode {

		DENY, ALLOWLIST, AUTO_APPROVE,

		/**
		 * Ask a person, through the application's {@code PermissionPrompt} bean. For an
		 * application someone is sitting at, such as one using spring-ai-acp-console.
		 */
		ASK

	}

	/**
	 * Whether the agent may use this client's filesystem methods.
	 *
	 * <p>
	 * Off by default, and read-only when it is on unless {@code write} says otherwise,
	 * because the two are genuinely different decisions: lending an agent the ability to
	 * read the repository it is reasoning about is ordinary, and lending it the ability
	 * to change that repository through this process is not.
	 *
	 * @param enabled Answer fs/read_text_file.
	 * @param write Also answer fs/write_text_file. Implies enabled.
	 */
	public record FileSystem(boolean enabled, boolean write) {

		FileSystemAccess toAccess() {
			return new FileSystemAccess(enabled || write, write);
		}

	}

	/**
	 * Whether the agent may ask this client to run commands, and which ones.
	 *
	 * @param enabled Answer the terminal/* methods. Arbitrary code execution as this
	 * JVM's user.
	 * @param allowedCommands Command names the agent may run. Empty allows any, which is
	 * the default.
	 * @param outputLimit Output kept per terminal before it is reported truncated.
	 * @param commandTimeout How long one command may run before it is killed.
	 * @param maxConcurrent Terminals one connection may hold at once.
	 */
	public record Terminal(boolean enabled, @DefaultValue Set<String> allowedCommands,
			@DefaultValue("1MB") DataSize outputLimit, @DefaultValue("5m") Duration commandTimeout,
			@DefaultValue("8") int maxConcurrent) {

		TerminalAccess toAccess() {
			return new TerminalAccess(enabled, allowedCommands, outputLimit.toBytes(), commandTimeout, maxConcurrent);
		}

	}

	/**
	 * How many agent processes to run, and how much to ask of each.
	 *
	 * @param maxProcesses Agent connections to keep. Each one is a separate agent
	 * process.
	 * @param maxSessionsPerProcess Named sessions one connection may hold before another
	 * is preferred.
	 * @param sessionTtl How long an idle named session is kept before it is closed.
	 * @param maxRestarts Replacements allowed within a five-minute window before a
	 * connection is left down.
	 */
	public record Pool(@DefaultValue("1") int maxProcesses, @DefaultValue("32") int maxSessionsPerProcess,
			@DefaultValue("60m") Duration sessionTtl, @DefaultValue("5") int maxRestarts) {

		PoolSettings toSettings() {
			return new PoolSettings(maxProcesses, maxSessionsPerProcess, maxRestarts);
		}

	}

	/**
	 * The optional HTTP endpoint.
	 *
	 * <p>
	 * Off by default and, when on, authenticated by default. An agent endpoint is a way
	 * to spend an application's model budget and to make its agent act on its workspace,
	 * so the defaults are the ones an application would have to deliberately weaken
	 * rather than remember to set.
	 *
	 * @param enabled Register the reactive controller.
	 * @param allowUnauthenticated Serve requests that arrive without a Principal.
	 * Development only.
	 * @param allowRequestOverrides Let a request name its own model or provider. Off:
	 * credentials are fixed at startup.
	 * @param path Base path for the endpoint.
	 * @param maxPromptChars Longest prompt a request may carry.
	 * @param maxTimeout Longest timeout a request may ask for.
	 */
	public record Controller(boolean enabled, boolean allowUnauthenticated, boolean allowRequestOverrides,
			@DefaultValue("/api/acp") String path, @DefaultValue("32000") int maxPromptChars,
			@DefaultValue("10m") Duration maxTimeout) {

	}

	/**
	 * Which ACP version to offer, and how much to trust the answer.
	 *
	 * <p>
	 * There is one right value for {@code max-version} today and it is the default. The
	 * property exists because the alternative to a flag is a code change, and ACP v2 is a
	 * published draft whose own announcement says to gate it behind version negotiation
	 * <em>and</em> a feature flag. Raising it offers v2; an agent that accepts is then
	 * refused, loudly, because {@code acp-core} 0.17.0 decodes the v1 wire format and a
	 * v2 turn would report as having ended for no reason. See {@code AcpProtocol}.
	 *
	 * @param maxVersion The highest ACP version to offer on initialize.
	 * @param strict Fail when an agent answers a version nobody offered, rather than
	 * clamping to the offer.
	 * <p>
	 * Off by default because goose 1.51 does exactly that, for every offer, and an
	 * application running goose should not have to choose between a startup failure and
	 * no negotiation at all.
	 */
	public record Protocol(@DefaultValue("" + AcpProtocol.HIGHEST_SPOKEN) int maxVersion, boolean strict) {

		ProtocolSettings toSettings() {
			return new ProtocolSettings(maxVersion, strict);
		}

	}

	/**
	 * A Tanzu AI Models service bound on Cloud Foundry. Read by
	 * {@code TanzuAiEnvironmentPostProcessor} before this record is bound; declared here
	 * so the properties are documented and validated with the rest.
	 *
	 * @param enabled Use a bound Tanzu AI Models service as the provider when
	 * {@code spring.acp.provider} sets no base-url or api-key of its own.
	 * @param serviceName Which bound service to use when there are several; the first by
	 * default.
	 */
	public record TanzuAi(@DefaultValue("true") boolean enabled, String serviceName) {

	}

	/**
	 * Whether turns and tool calls are reported to Micrometer.
	 *
	 * @param enabled Record an observation per turn and per tool call. On when Micrometer
	 * is present.
	 * <p>
	 * Unlike the controller, this defaults on: an observation publishes nothing and
	 * exposes nothing, and an application that has an {@code ObservationRegistry} has
	 * already asked to be measured.
	 */
	public record Observations(@DefaultValue("true") boolean enabled) {

	}

	/**
	 * The ACP agent registry: where the catalogue comes from, and what may be downloaded.
	 *
	 * <p>
	 * Only consulted for a {@code spring.acp.runtime} that no adapter on the classpath
	 * claims. An application running goose, codex or opencode never touches any of this.
	 *
	 * <p>
	 * Nothing here names a type from {@code spring-ai-acp-runtime-registry}, and that is
	 * load-bearing rather than tidy. This record is built whenever {@code AcpProperties}
	 * is bound — so a default of {@code RegistrySettings.DEFAULT_URL} would make an
	 * optional dependency mandatory, and an application without it would die at refresh
	 * on the very jar it chose not to ship. Null here means "the registry module's own
	 * default", and {@code AcpRegistryConfiguration} does the conversion behind a class
	 * condition that can hold when the class is genuinely missing.
	 *
	 * @param enabled Consult the registry for a runtime no adapter claims.
	 * @param url The published catalogue. A file: URL pins it to a snapshot you control.
	 * @param cache Where the snapshot and the downloaded agents are kept between runs.
	 * @param refresh How long a cached snapshot is used before the catalogue is fetched
	 * again.
	 * @param offline Forbid every network call: use the bundled snapshot and whatever is
	 * already installed.
	 * @param requireChecksum Refuse an agent the registry publishes no sha256 for.
	 * <p>
	 * On by default, which makes 9 of the registry's 19 binary agents need one more line
	 * of configuration. That is the intended cost: this downloads an executable and runs
	 * it with the application's credentials in its environment.
	 * @param downloadTimeout How long one agent download may take.
	 */
	public record Registry(@DefaultValue("true") boolean enabled, URI url, Path cache, Duration refresh,
			boolean offline, @DefaultValue("true") boolean requireChecksum, Duration downloadTimeout) {

	}

	/**
	 * Which model provider to use, and how to reach it.
	 *
	 * <p>
	 * {@code id} is a negotiated request the agent may decline. The rest is provisioning:
	 * it goes over the wire when the agent advertises the providers capability, and into
	 * the agent process's environment — or its config file — when it does not. Fixed when
	 * the process starts, never per request.
	 *
	 * <p>
	 * Setting {@code base-url} says the application has an endpoint of its own, and that
	 * endpoint becomes the authority on which models exist: {@code spring.acp.model} is
	 * checked against its {@code /models} listing rather than against the catalogue the
	 * agent shipped with, and is applied even though the agent never advertised it.
	 * Nothing else has to be configured for that.
	 *
	 * @param id The provider's name as the agent knows it, e.g. openai.
	 * @param apiType The API dialect, which also names the environment variables the
	 * credentials travel in.
	 * @param baseUrl An OpenAI-style base URL, up to and including the version segment —
	 * {@code /v1} is added when it is not there, so a platform's {@code …/openai} and a
	 * README's {@code …/v1} mean the same endpoint. HTTPS, or plain HTTP only for
	 * loopback and .apps.internal.
	 * @param apiKey Sent to the provider, never logged.
	 * @param headers Extra headers for the provider endpoint. Rejected if they contain CR
	 * or LF.
	 */
	public record Provider(String id, String apiType, URI baseUrl, String apiKey,
			@DefaultValue Map<String, String> headers) {

		ProviderSpec toSpec() {
			return new ProviderSpec(id, apiType, baseUrl, apiKey, headers);
		}

	}

	/**
	 * How the agent's requests for permission are answered.
	 *
	 * @param policy How to answer an agent asking permission to use a tool.
	 * @param allowedTools Exact tool names to approve when policy is allowlist.
	 */
	public record Permissions(@DefaultValue("deny") PermissionMode policy, @DefaultValue Set<String> allowedTools) {

		PermissionPolicy toPolicy(PermissionPrompt prompt) {
			return switch (policy) {
				case DENY -> PermissionPolicy.deny();
				case AUTO_APPROVE -> PermissionPolicy.autoApprove();
				case ALLOWLIST -> PermissionPolicy.allowlist(allowedTools);
				case ASK -> {
					if (prompt == null) {
						throw new IllegalStateException("spring.acp.permissions.policy=ask needs someone to ask: "
								+ "a PermissionPrompt bean, which spring-ai-acp-console registers whenever its console "
								+ "runs (in an interactive terminal, or with spring.acp.console.enabled=true)");
					}
					yield PermissionPolicy.ask(prompt);
				}
			};
		}

	}

	/**
	 * What to do about an MCP server the agent reports it could not load.
	 *
	 * <p>
	 * Only Goose can report one at all today, and only for an agent this application
	 * started; everywhere else these settings are inert, because nothing ever reports a
	 * failure. See the "MCP Servers" section of {@code docs/user-guide.html}.
	 *
	 * @param onServerFailure {@code warn} logs it and opens the session anyway;
	 * {@code fail} refuses the session.
	 * @param detectTimeout How long session opening waits for such a report. Only paid
	 * when {@code fail}.
	 */
	public record Mcp(@DefaultValue("warn") McpSettings.OnServerFailure onServerFailure, Duration detectTimeout) {

		public Mcp {
			detectTimeout = detectTimeout == null ? McpSettings.DEFAULT_DETECT_TIMEOUT : detectTimeout;
		}

	}

	/** How an MCP server is authorized. */
	public enum McpAuth {

		/**
		 * Whatever the configuration says, headers included, handed to the agent as it
		 * stands.
		 */
		NONE,

		/**
		 * The MCP authorization spec's OAuth, per user; provided by
		 * {@code spring-ai-acp-mcp-oauth}.
		 */
		OAUTH,

		/**
		 * The application's own {@code McpCredentialsProvider} supplies the credentials
		 * and, when {@code url} is left unset, the URL too; a session it does not supply
		 * one for is opened without the server.
		 */
		PROVIDED

	}

	/**
	 * One MCP server. {@code url} selects HTTP transport; {@code command} selects stdio.
	 *
	 * @param auth How this server is authorized. {@code oauth} means the MCP
	 * authorization spec's OAuth flow, with each user's own token, and needs
	 * {@code spring-ai-acp-mcp-oauth} on the classpath. {@code provided} means the
	 * application's own {@code McpCredentialsProvider} bean, which may also supply the
	 * url, in which case {@code url} is left unset.
	 */
	public record McpServer(String name, URI url, @DefaultValue Map<String, String> headers, String command,
			@DefaultValue List<String> args, @DefaultValue Map<String, String> env, McpAuth auth) {

		public McpServer {
			auth = auth == null ? McpAuth.NONE : auth;
		}

		/**
		 * The spec this server binds to, for an extension that needs to know which
		 * servers it owns.
		 */
		public McpServerSpec spec() {
			return toSpec();
		}

		McpServerSpec toSpec() {
			if (url != null && command != null) {
				throw new IllegalArgumentException(
						"mcp server '" + name + "' sets both url and command; pick one transport");
			}
			if (url != null) {
				return new McpServerSpec.Http(name, url, headers);
			}
			if (command != null) {
				if (auth == McpAuth.PROVIDED) {
					throw new IllegalArgumentException("mcp server '" + name
							+ "' sets auth: provided, which applies only to a server reached over http");
				}
				return new McpServerSpec.Stdio(name, command, args, env);
			}
			if (auth == McpAuth.PROVIDED) {
				return McpServerSpec.Http.provided(name, headers);
			}
			throw new IllegalArgumentException("mcp server '" + name + "' must set either url or command, or "
					+ "auth: provided for a server whose url the application supplies");
		}

	}

	/**
	 * One skill. Without {@code url} it is bundled with the application, and {@code path}
	 * is its directory on the classpath — {@code skills/mailgun} for
	 * {@code src/main/resources/skills/mailgun}. With {@code url} it comes from that Git
	 * repository, and {@code path} is its directory there.
	 *
	 * @param name Directory name to install as. Defaults to the last segment of path, or
	 * the repository name.
	 * @param url A Git repository over HTTPS, e.g. https://github.com/owner/repo. Unset
	 * for a bundled skill.
	 * @param ref Git only: commit, branch or tag. Defaults to the default branch; a
	 * 40-character commit pins it.
	 * @param path The skill's directory: on the classpath when bundled, inside the
	 * repository otherwise.
	 * @param token Git only: a token for a private repository, e.g. a GitHub personal
	 * access token.
	 * @param sha256 Expected SHA-256 of the skill's SKILL.md, checked when set.
	 */
	public record Skill(String name, URI url, String ref, String path, String token, String sha256) {

		SkillSpec toSpec() {
			if (url != null) {
				return new SkillSpec.Git(name, url, ref, path, token, sha256);
			}
			if (ref != null || token != null) {
				throw new IllegalArgumentException("skill '" + (name == null ? path : name)
						+ "' sets ref or token without a url; a bundled skill needs only a path");
			}
			return new SkillSpec.Bundled(name, path, sha256);
		}

	}

}
