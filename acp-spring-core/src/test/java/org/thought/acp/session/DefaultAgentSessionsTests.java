package org.thought.acp.session;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thought.acp.client.SessionConfigRecorder;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.session.AgentSessions.Operation;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Capability gating, pagination, and the rule that binding a name is not the same as opening one.
 */
class DefaultAgentSessionsTests {

	@TempDir
	Path workspace;

	private final AcpAsyncClient acp = mock(AcpAsyncClient.class);

	private final SessionRegistry registry = new SessionRegistry();

	private final SessionConfigRecorder recorder = new SessionConfigRecorder();

	private DefaultAgentSessions sessions(AcpSchema.AgentCapabilities capabilities) {
		return new DefaultAgentSessions(acp, "fake", AgentSettings.builder("fake", workspace).build(), capabilities,
				registry, recorder, session -> {
				});
	}

	private static AcpSchema.AgentCapabilities capabilities(boolean load, AcpSchema.SessionCapabilities session) {
		return new AcpSchema.AgentCapabilities(load, session, null, null, null, null);
	}

	private static AcpSchema.SessionCapabilities all() {
		// ACP signals support by the member being present; the value itself carries no meaning.
		return new AcpSchema.SessionCapabilities(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, null, null);
	}

	@Test
	@DisplayName("an operation the agent never advertised is refused by name, not on the wire")
	void refusesUnsupportedOperations() {
		AgentSessions none = sessions(capabilities(false, null));

		assertThat(none.supports(Operation.LIST)).isFalse();
		assertThatThrownBy(none::list).isInstanceOf(UnsupportedAgentOperationException.class)
				.hasMessageContaining("session/list");
		verify(acp, never()).listSessions(any());
	}

	@Test
	@DisplayName("capabilities are read member by member, since presence is the signal")
	void readsCapabilitiesIndividually() {
		AgentSessions partial = sessions(capabilities(true,
				new AcpSchema.SessionCapabilities(Boolean.TRUE, null, null, null, null, null)));

		assertThat(partial.supports(Operation.LIST)).isTrue();
		assertThat(partial.supports(Operation.LOAD)).isTrue();
		assertThat(partial.supports(Operation.CLOSE)).isFalse();
		assertThat(partial.supports(Operation.RESUME)).isFalse();
		assertThat(partial.supports(Operation.DELETE)).isFalse();
	}

	@Test
	@DisplayName("listing follows the cursor to the end and hands back one list")
	void followsPagination() {
		when(acp.listSessions(any())).thenReturn(
				Mono.just(new AcpSchema.ListSessionsResponse(List.of(info("a")), "next", null)),
				Mono.just(new AcpSchema.ListSessionsResponse(List.of(info("b")), null, null)));

		List<StoredSession> found = sessions(capabilities(false, all())).list();

		assertThat(found).extracting(StoredSession::sessionId).containsExactly("a", "b");
	}

	@Test
	@DisplayName("an unparseable updatedAt costs that field, not the listing")
	void toleratesABadTimestamp() {
		when(acp.listSessions(any())).thenReturn(Mono.just(new AcpSchema.ListSessionsResponse(
				List.of(new AcpSchema.SessionInfo("a", workspace.toString(), "title", "yesterday", null, null)))));

		StoredSession found = sessions(capabilities(false, all())).list().get(0);

		assertThat(found.findUpdatedAt()).isEmpty();
		assertThat(found.findTitle()).contains("title");
	}

	@Test
	@DisplayName("loading binds a name to an existing session")
	void loadBindsAName() {
		when(acp.loadSession(any())).thenReturn(Mono.just(new AcpSchema.LoadSessionResponse(null, null)));

		AgentSession bound = sessions(capabilities(true, all())).load("review", "sid-7");

		assertThat(bound.name()).isEqualTo("review");
		assertThat(bound.sessionId()).isEqualTo("sid-7");
		assertThat(registry.find("review")).isPresent();
	}

	@Test
	@DisplayName("a failed load leaves no name pointing at a session this client never attached to")
	void failedLoadUnbinds() {
		when(acp.loadSession(any())).thenReturn(Mono.error(new IllegalStateException("no such session")));

		assertThatThrownBy(() -> sessions(capabilities(true, all())).load("review", "sid-7"))
				.isInstanceOf(RuntimeException.class);

		assertThat(registry.find("review")).isEmpty();
	}

	@Test
	@DisplayName("binding a name that is already open is a mistake, not a silent rebinding")
	void refusesToRebindAnOpenName() {
		when(acp.loadSession(any())).thenReturn(Mono.just(new AcpSchema.LoadSessionResponse(null, null)));
		AgentSessions open = sessions(capabilities(true, all()));
		open.load("review", "sid-7");

		assertThatThrownBy(() -> open.load("review", "sid-8")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already open");
	}

	@Test
	@DisplayName("deleting a session also forgets any local name for it")
	void deleteForgetsTheName() {
		when(acp.loadSession(any())).thenReturn(Mono.just(new AcpSchema.LoadSessionResponse(null, null)));
		when(acp.deleteSession(any())).thenReturn(Mono.just(new AcpSchema.DeleteSessionResponse()));
		AgentSessions open = sessions(capabilities(true, all()));
		open.load("review", "sid-7");

		open.delete("sid-7");

		assertThat(registry.find("review")).isEmpty();
	}

	@Test
	@DisplayName("closing a name always works locally, even when the agent has no session/close")
	void closeWithoutTheCapability() {
		registry.adopt("review", "sid-7");
		AgentSessions open = sessions(capabilities(false, null));

		open.close("review");

		assertThat(registry.find("review")).isEmpty();
		verify(acp, never()).closeSession(any());
	}

	@Test
	@DisplayName("closing a name tells the agent when it can be told")
	void closeWithTheCapability() {
		registry.adopt("review", "sid-7");
		when(acp.closeSession(any())).thenReturn(Mono.just(new AcpSchema.CloseSessionResponse()));

		sessions(capabilities(false, all())).close("review");

		verify(acp).closeSession(any());
	}

	private AcpSchema.SessionInfo info(String id) {
		return new AcpSchema.SessionInfo(id, workspace.toString());
	}
}
