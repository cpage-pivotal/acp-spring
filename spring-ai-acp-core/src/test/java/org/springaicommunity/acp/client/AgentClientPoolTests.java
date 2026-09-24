package org.springaicommunity.acp.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.config.AgentOptions;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.config.PoolSettings;
import org.springaicommunity.acp.event.AgentEvent;
import org.springaicommunity.acp.session.AgentSession;
import org.springaicommunity.acp.session.AgentSessions;
import org.springaicommunity.acp.session.SessionRegistry;
import org.springaicommunity.acp.session.StoredSession;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Routing, stickiness and replacement, against a fake connection rather than an agent.
 *
 * <p>
 * Nothing here needs a real agent: what the pool decides is which connection a name goes
 * to and when a connection is replaced, and a fake makes both observable in a way a
 * subprocess would not.
 */
class AgentClientPoolTests {

	@TempDir
	Path workspace;

	private final AtomicInteger connections = new AtomicInteger();

	private final List<FakeClient> created = new ArrayList<>();

	private AgentSettings settings(int processes, int sessionsPerProcess) {
		return AgentSettings.builder("fake", workspace)
			.pool(new PoolSettings(processes, sessionsPerProcess, PoolSettings.DEFAULT_MAX_RESTARTS))
			.build();
	}

	private AgentClientPool pool(AgentSettings settings) {
		return new AgentClientPool("fake", settings, this::newClient);
	}

	private AgentClient newClient() {
		FakeClient client = new FakeClient(connections.incrementAndGet());
		created.add(client);
		return client;
	}

	@Test
	@DisplayName("nothing is connected until something is asked of the pool")
	void connectsLazily() {
		try (AgentClientPool pool = pool(settings(2, 32))) {
			assertThat(pool.openConnections()).isZero();

			pool.openSession("a");

			assertThat(pool.openConnections()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("a named session always goes back to the connection that has its context")
	void namedSessionsAreSticky() {
		try (AgentClientPool pool = pool(settings(3, 32))) {
			pool.openSession("review");
			FakeClient owner = created.get(0);

			for (int i = 0; i < 5; i++) {
				pool.prompt().session("review").user("again").call();
			}

			assertThat(owner.prompts).isEqualTo(5);
			assertThat(created).hasSize(1);
		}
	}

	@Test
	@DisplayName("unrelated conversations spread across the pool")
	void spreadsAcrossConnections() {
		try (AgentClientPool pool = pool(settings(3, 32))) {
			pool.openSession("a");
			pool.openSession("b");
			pool.openSession("c");

			assertThat(pool.openConnections()).isEqualTo(3);
			assertThat(created).allSatisfy(client -> assertThat(client.registry.size()).isEqualTo(1));
		}
	}

	@Test
	@DisplayName("a full pool says so, and says what to change")
	void refusesWhenFull() {
		try (AgentClientPool pool = pool(settings(2, 1))) {
			pool.openSession("a");
			pool.openSession("b");

			assertThatThrownBy(() -> pool.openSession("c")).isInstanceOf(AgentClientException.class)
				.hasMessageContaining("maximum of 2 sessions")
				.hasMessageContaining("spring.acp.pool");
		}
	}

	@Test
	@DisplayName("a connection that reports itself dead is replaced on next use")
	void replacesADeadConnection() {
		try (AgentClientPool pool = pool(settings(1, 32))) {
			pool.openSession("a");
			FakeClient first = created.get(0);
			first.alive.set(false);

			pool.openSession("b");

			assertThat(created).hasSize(2);
			assertThat(first.closed).isTrue();
		}
	}

	@Test
	@DisplayName("a connection that keeps dying is left down rather than respawned forever")
	void stopsReplacingAfterTheBudget() {
		AgentSettings settings = AgentSettings.builder("fake", workspace).pool(new PoolSettings(1, 32, 2)).build();
		try (AgentClientPool pool = new AgentClientPool("fake", settings, () -> {
			FakeClient client = (FakeClient) newClient();
			client.alive.set(false);
			return client;
		})) {
			assertThatThrownBy(() -> {
				for (int i = 0; i < 10; i++) {
					pool.openSession("s" + i);
				}
			}).isInstanceOf(AgentClientException.class);
			assertThat(created).hasSizeLessThanOrEqualTo(4);
		}
	}

	@Test
	@DisplayName("sweeping evicts idle sessions and gives their place in the pool back")
	void sweepFreesCapacity() {
		try (AgentClientPool pool = pool(settings(1, 1))) {
			pool.openSession("a");
			created.get(0).registry.clear();

			// The name was holding the only place in the pool; the sweep should notice it
			// is gone.
			pool.openSession("b");

			assertThat(created).hasSize(1);
		}
	}

	@Test
	@DisplayName("closing a session frees its name and tells the connection that owns it")
	void closeReleasesTheName() {
		try (AgentClientPool pool = pool(settings(1, 32))) {
			pool.openSession("a");

			pool.sessions().close("a");

			assertThat(pool.session("a")).isEmpty();
			assertThat(created.get(0).registry.knows("a")).isFalse();
		}
	}

	@Test
	@DisplayName("closing the pool closes every connection it opened")
	void closeClosesEverything() {
		AgentClientPool pool = pool(settings(2, 32));
		pool.openSession("a");
		pool.openSession("b");

		pool.close();

		assertThat(created).allSatisfy(client -> assertThat(client.closed).isTrue());
		assertThat(pool.openConnections()).isZero();
	}

	@Test
	@DisplayName("a throwaway turn runs without taking a name")
	void ephemeralTurns() {
		try (AgentClientPool pool = pool(settings(1, 32))) {
			String answer = pool.prompt("hello").call().content();

			assertThat(answer).isEqualTo("ok");
			assertThat(created.get(0).lastSessionName).isNull();
		}
	}

	@Test
	@DisplayName("a streamed turn picks its connection on subscription, not when it was described")
	void streamsAreDeferred() {
		try (AgentClientPool pool = pool(settings(1, 32))) {
			AgentClient.AgentStream stream = pool.prompt("hello").stream();
			assertThat(pool.openConnections()).isZero();

			List<AgentEvent> events = stream.events().collectList().block();

			assertThat(events).hasSize(2);
			assertThat(pool.openConnections()).isEqualTo(1);
		}
	}

	/** An {@link AgentClient} that records what was asked of it and nothing else. */
	private static final class FakeClient implements AgentClient {

		private final int index;

		private final SessionRegistry registry = new SessionRegistry();

		private final AtomicBoolean alive = new AtomicBoolean(true);

		private int prompts;

		private String lastSessionName;

		private boolean closed;

		private FakeClient(int index) {
			this.index = index;
		}

		@Override
		public PromptSpec prompt() {
			return new FakePromptSpec();
		}

		@Override
		public String runtimeId() {
			return "fake";
		}

		@Override
		public Optional<AgentInfo> agentInfo() {
			return Optional.of(new AgentInfo("fake", "1." + index));
		}

		@Override
		public boolean isAlive() {
			return alive.get();
		}

		@Override
		public AgentSessions sessions() {
			return new FakeSessions();
		}

		@Override
		public Optional<AgentSession> session(String name) {
			return registry.find(name);
		}

		@Override
		public AgentSession openSession(String name) {
			return registry.resolve(name, n -> "sid-" + index + "-" + n);
		}

		@Override
		public void close() {
			closed = true;
		}

		private final class FakePromptSpec implements PromptSpec {

			private String sessionName;

			@Override
			public PromptSpec session(String name) {
				this.sessionName = name;
				return this;
			}

			@Override
			public PromptSpec user(String text) {
				return this;
			}

			@Override
			public PromptSpec options(AgentOptions options) {
				return this;
			}

			@Override
			public PromptSpec options(java.util.function.Consumer<AgentOptions.Builder> customizer) {
				return this;
			}

			@Override
			public AgentResponse call() {
				run();
				return new AgentResponse() {

					@Override
					public String content() {
						return "ok";
					}

					@Override
					public AgentEvent.Completed completion() {
						return new AgentEvent.Completed(AcpSchema.StopReason.END_TURN);
					}
				};
			}

			@Override
			public AgentStream stream() {
				return () -> Flux.defer(() -> {
					run();
					return Flux.just(new AgentEvent.Text("ok"),
							new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));
				});
			}

			private void run() {
				prompts++;
				lastSessionName = sessionName;
				if (sessionName != null) {
					openSession(sessionName);
				}
			}

		}

		private final class FakeSessions implements AgentSessions {

			@Override
			public List<StoredSession> list() {
				return List.of();
			}

			@Override
			public List<StoredSession> list(Path cwd) {
				return List.of();
			}

			@Override
			public AgentSession load(String name, String sessionId) {
				return registry.adopt(name, sessionId);
			}

			@Override
			public AgentSession resume(String name, String sessionId) {
				return registry.adopt(name, sessionId);
			}

			@Override
			public void delete(String sessionId) {
			}

			@Override
			public void close(String name) {
				registry.remove(name);
			}

			@Override
			public List<AgentSession> open() {
				return registry.all();
			}

			@Override
			public Optional<AgentSession> find(String name) {
				return registry.find(name);
			}

			@Override
			public boolean supports(Operation operation) {
				return true;
			}

		}

	}

	/**
	 * The regression that made the re-assertion in {@code clientFor} necessary: replacing
	 * a dead connection releases the names it held, and a name released and not re-taken
	 * lands on a different connection next time — the same conversation, on two
	 * processes, under one name.
	 */
	@Test
	@DisplayName("a session keeps its connection after that connection has been replaced")
	void stickinessSurvivesAReplacement() {
		try (AgentClientPool pool = pool(settings(3, 32))) {
			pool.openSession("review");
			created.get(0).alive.set(false);

			pool.openSession("review");
			pool.openSession("review");

			// One replacement, and nothing beyond it: the name went back to where it
			// landed.
			assertThat(created).hasSize(2);
			assertThat(created.get(1).registry.knows("review")).isTrue();
		}
	}

	@Test
	@DisplayName("asking who the agent is does not start one")
	void agentInfoDoesNotConnect() {
		try (AgentClientPool pool = pool(settings(1, 32))) {
			assertThat(pool.agentInfo()).isEmpty();
			assertThat(pool.openConnections()).isZero();

			pool.openSession("a");

			assertThat(pool.agentInfo()).isPresent();
		}
	}

	/**
	 * The name is only let go after it has been missing across two sweeps, so a sweep
	 * landing between assigning a name and opening its session cannot send the next call
	 * elsewhere.
	 */
	@Test
	@DisplayName("a name is not forgotten by the first sweep that finds its session missing")
	void pruningTakesTwoSweeps() {
		try (AgentClientPool pool = pool(settings(2, 32))) {
			pool.openSession("review");
			FakeClient owner = created.get(0);
			owner.registry.clear();

			pool.evictIdleSessions();
			pool.openSession("review");

			assertThat(created).hasSize(1);
			assertThat(owner.registry.knows("review")).isTrue();
		}
	}

	@Test
	@DisplayName("a name whose session is gone for two sweeps is let go")
	void pruningHappensEventually() {
		try (AgentClientPool pool = pool(settings(2, 32))) {
			pool.openSession("review");
			created.get(0).registry.clear();

			pool.evictIdleSessions();
			pool.evictIdleSessions();

			assertThat(pool.session("review")).isEmpty();
		}
	}

}
