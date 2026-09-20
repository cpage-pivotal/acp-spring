package org.tanzu.acp.boot;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentClientException;
import org.tanzu.acp.config.AgentOptions;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.session.StoredSession;
import org.tanzu.acp.session.UnsupportedAgentOperationException;

import reactor.core.publisher.Flux;

/**
 * An optional HTTP face for the agent, off unless {@code spring.acp.controller.enabled} says
 * otherwise.
 *
 * <p>Opt-in because exposing an agent over HTTP is not a small decision: a caller who reaches this
 * endpoint can spend the application's model budget and can make the agent act on the application's
 * workspace, with the application's credentials. So the defaults are the restrictive ones —
 * authentication required, per-request model and provider overrides refused, prompt length and
 * timeout bounded — and each can only be relaxed deliberately.
 *
 * <p>Credentials and endpoints are fixed when the agent process starts and are not reachable from
 * here at all. What a request may vary is the session it runs in and, if the application allows it,
 * the negotiated options; there is no path by which a request supplies an API key.
 *
 * <p>Put it behind Spring Security or the platform's own SSO. {@code allow-unauthenticated} exists
 * for a developer running it on a laptop and says so in its name.
 */
@RestController
public class AcpController {

	private static final Logger logger = LoggerFactory.getLogger(AcpController.class);

	private final AgentClient agent;

	private final AcpProperties.Controller properties;

	public AcpController(AgentClient agent, AcpProperties properties) {
		this.agent = agent;
		this.properties = properties.getController();
	}

	/** Runs a turn and returns the whole answer. */
	@PostMapping("${spring.acp.controller.path:/api/acp}/prompt")
	public ResponseEntity<PromptResponse> prompt(@RequestBody PromptRequest request, Principal principal) {
		requireAuthorized(principal);
		validate(request);
		String requestId = UUID.randomUUID().toString();
		try {
			AgentClient.AgentResponse response = spec(request).call();
			return ResponseEntity.ok(new PromptResponse(response.content(), response.completion().reason().toString(),
					null, requestId));
		}
		catch (AgentClientException ex) {
			// The agent's own words can carry anything it read; the caller gets the request id.
			logger.warn("Agent request {} failed", requestId, ex);
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(new PromptResponse(null, null, "The agent did not complete the turn", requestId));
		}
	}

	/**
	 * Streams a turn as server-sent events.
	 *
	 * <p>Text only, deliberately: tool calls and plans name paths and commands inside the
	 * workspace, and an endpoint that may be exposed to a browser should not be the thing that
	 * decides those are safe to publish. An application that wants them has {@code AgentClient}.
	 */
	@PostMapping(value = "${spring.acp.controller.path:/api/acp}/stream",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> stream(@RequestBody PromptRequest request, Principal principal) {
		requireAuthorized(principal);
		validate(request);
		return spec(request).stream().events().filter(AgentEvent.Text.class::isInstance)
				.map(AgentEvent.Text.class::cast).map(AgentEvent.Text::text);
	}

	/** The conversations the agent has stored, when it implements {@code session/list}. */
	@GetMapping("${spring.acp.controller.path:/api/acp}/sessions")
	public ResponseEntity<List<StoredSession>> sessions(Principal principal) {
		requireAuthorized(principal);
		try {
			return ResponseEntity.ok(agent.sessions().list());
		}
		catch (UnsupportedAgentOperationException ex) {
			throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
		}
	}

	/** Ends a named session. Always succeeds, whether or not the agent knew about it. */
	@DeleteMapping("${spring.acp.controller.path:/api/acp}/sessions/{name}")
	public ResponseEntity<Void> closeSession(@PathVariable String name, Principal principal) {
		requireAuthorized(principal);
		agent.sessions().close(name);
		return ResponseEntity.noContent().build();
	}

	/** Unauthenticated on purpose: it says whether the agent is up and nothing about what it does. */
	@GetMapping("${spring.acp.controller.path:/api/acp}/health")
	public ResponseEntity<HealthResponse> health() {
		boolean available = agent.isAlive();
		return ResponseEntity.status(available ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
				.body(new HealthResponse(available, agent.runtimeId(),
						agent.agentInfo().map(info -> info.toString()).orElse(null)));
	}

	private AgentClient.PromptSpec spec(PromptRequest request) {
		AgentClient.PromptSpec spec = agent.prompt().user(request.prompt());
		if (request.session() != null) {
			spec = spec.session(request.session());
		}
		return spec.options(options(request));
	}

	private AgentOptions options(PromptRequest request) {
		AgentOptions.Builder builder = AgentOptions.builder();
		if (request.timeout() != null) {
			builder.timeout(request.timeout());
		}
		if (properties.isAllowRequestOverrides()) {
			if (request.model() != null) {
				builder.model(request.model());
			}
			if (request.provider() != null) {
				builder.provider(request.provider());
			}
			if (request.mode() != null) {
				builder.mode(request.mode());
			}
		}
		return builder.build();
	}

	private void requireAuthorized(Principal principal) {
		if (!properties.isAllowUnauthenticated() && principal == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
		}
	}

	private void validate(PromptRequest request) {
		if (request == null || request.prompt() == null || request.prompt().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
		}
		if (request.prompt().length() > properties.getMaxPromptChars()) {
			throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "prompt is too large");
		}
		if (request.timeout() != null && (request.timeout().isNegative() || request.timeout().isZero()
				|| request.timeout().compareTo(properties.getMaxTimeout()) > 0)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"timeout must be positive and at most " + properties.getMaxTimeout());
		}
		if (!properties.isAllowRequestOverrides()
				&& (request.model() != null || request.provider() != null || request.mode() != null)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"model, provider and mode overrides are disabled; see spring.acp.controller"
							+ ".allow-request-overrides");
		}
	}

	/**
	 * @param session a named conversation to continue, or null for a throwaway turn
	 */
	public record PromptRequest(String prompt, String session, Duration timeout, String model, String provider,
			String mode) {
	}

	public record PromptResponse(String content, String stopReason, String error, String requestId) {
	}

	public record HealthResponse(boolean available, String runtime, String agent) {
	}
}
