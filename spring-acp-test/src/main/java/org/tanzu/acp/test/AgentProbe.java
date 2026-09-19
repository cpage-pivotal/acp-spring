package org.tanzu.acp.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentClientFactory;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.session.AgentSession;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Asks a runtime, once, whether it can actually be used here — and what it has.
 *
 * <p>Gating a live suite on {@code agent --version} is not enough. All three agents are installed on
 * a developer machine long before they are usable from one: an agent with no credentials answers the
 * handshake and then fails inside a turn, which reads as a bug in this library rather than a machine
 * that needs a {@code login}. So the gate is a real session, and a suite skips rather than fails.
 *
 * <p>It also answers the question that makes a portable conformance suite possible at all: which
 * model to ask for. Hardcoding one per agent would put three model catalogs into the test source and
 * break whenever a vendor retires a name. Instead this reads the models the agent advertises and
 * picks one that is <em>not</em> the one it already has, so setting it proves something.
 *
 * <p>Probes are cached per runtime id for the life of the JVM: three handshakes per build, not one
 * per test.
 */
public final class AgentProbe {

	private static final Logger logger = LoggerFactory.getLogger(AgentProbe.class);

	private static final Map<String, AgentProbe> CACHE = new ConcurrentHashMap<>();

	private static final Duration HANDSHAKE_TIMEOUT = Duration.ofMinutes(2);

	private final boolean usable;

	private final String failure;

	private final List<String> models;

	private final String currentModel;

	private final List<String> modes;

	private AgentProbe(boolean usable, String failure, List<String> models, String currentModel, List<String> modes) {
		this.usable = usable;
		this.failure = failure;
		this.models = List.copyOf(models);
		this.currentModel = currentModel;
		this.modes = List.copyOf(modes);
	}

	/** Probes {@code runtime}, or returns the answer from the first time it was asked. */
	public static AgentProbe of(AgentRuntime runtime) {
		return CACHE.computeIfAbsent(runtime.id(), id -> probe(runtime));
	}

	/** Whether a live suite for {@code runtime} should run on this machine. */
	public static boolean isUsable(AgentRuntime runtime) {
		return of(runtime).usable();
	}

	private static AgentProbe probe(AgentRuntime runtime) {
		Path workspace = null;
		try {
			workspace = Files.createTempDirectory("acp-probe-" + runtime.id());
			AgentSettings settings = AgentSettings.builder(runtime.id(), workspace).timeout(HANDSHAKE_TIMEOUT).build();
			try (AgentClient client = AgentClientFactory.create(runtime, settings)) {
				AgentSession session = client.openSession("probe");
				Optional<AcpSchema.SessionConfigSelect> model = session.advertised().select("model", "model");
				List<String> models = model.map(AgentProbe::values).orElseGet(List::of);
				List<String> legacyModels = session.advertised().findModels()
						.map(state -> state.availableModels().stream().map(AcpSchema.ModelInfo::modelId).toList())
						.orElseGet(List::of);
				List<String> modes = session.advertised().select("mode", "mode").map(AgentProbe::values)
						.or(() -> session.advertised().findModes().map(state -> state.availableModes().stream()
								.map(AcpSchema.SessionMode::id).toList()))
						.orElseGet(List::of);

				return new AgentProbe(true, null, models.isEmpty() ? legacyModels : models,
						model.map(AcpSchema.SessionConfigSelect::currentValue).orElse(null), modes);
			}
		}
		catch (RuntimeException | IOException ex) {
			logger.info("Runtime '{}' is not usable here, so its live suite will be skipped: {}", runtime.id(),
					ex.getMessage());
			return new AgentProbe(false, ex.getMessage(), List.of(), null, List.of());
		}
		finally {
			deleteQuietly(workspace);
		}
	}

	private static List<String> values(AcpSchema.SessionConfigSelect select) {
		return select.options() == null ? List.of()
				: select.options().stream().map(AcpSchema.SessionConfigSelectOption::value).toList();
	}

	private static void deleteQuietly(Path directory) {
		if (directory == null) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ignored) {
					// A temp directory left behind is not worth failing a probe over.
				}
			});
		}
		catch (IOException ignored) {
			// Likewise.
		}
	}

	public boolean usable() {
		return usable;
	}

	/** Every model the agent advertised, however it advertised them. */
	public List<String> models() {
		return models;
	}

	public List<String> modes() {
		return modes;
	}

	/**
	 * The model the agent is already configured with — the one its credentials demonstrably reach, and
	 * so the only safe choice for a test that runs a turn.
	 */
	public Optional<String> currentModel() {
		return Optional.ofNullable(currentModel).filter(m -> !m.isBlank())
				.or(() -> models.stream().findFirst());
	}

	/**
	 * Some other model the agent advertises, for asserting that a change can be applied. Whether this
	 * machine could actually prompt with it is unknown and beside the point.
	 */
	public Optional<String> alternativeModel() {
		return models.stream().filter(m -> !m.equals(currentModel)).findFirst();
	}
}
