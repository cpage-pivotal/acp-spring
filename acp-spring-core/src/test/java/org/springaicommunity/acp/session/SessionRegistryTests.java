package org.springaicommunity.acp.session;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionRegistryTests {

	private final SessionRegistry registry = new SessionRegistry();

	@Test
	void createsASessionOnceAndReusesItAfterwards() {
		AtomicInteger created = new AtomicInteger();
		registry.resolve("review", name -> "sid-" + created.incrementAndGet());
		AgentSession second = registry.resolve("review", name -> "sid-" + created.incrementAndGet());

		assertThat(created).hasValue(1);
		assertThat(second.sessionId()).isEqualTo("sid-1");
	}

	@Test
	void rejectsNamesThatWouldNotBeSafeAsAFilenameOrConfigKey() {
		assertThatThrownBy(() -> registry.resolve("../escape", name -> "sid"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> registry.resolve("", name -> "sid")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> registry.resolve("a".repeat(129), name -> "sid"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void concurrentResolversOfTheSameNameShareOneSession() throws Exception {
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger created = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					registry.resolve("shared", name -> "sid-" + created.incrementAndGet());
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

		assertThat(created).hasValue(1);
		assertThat(registry.size()).isEqualTo(1);
	}

	@Test
	void evictsIdleSessionsButNotOnesMidTurn() throws Exception {
		AgentSession idle = registry.resolve("idle", name -> "sid-idle");
		AgentSession busy = registry.resolve("busy", name -> "sid-busy");
		assertThat(busy.tryBeginTurn(Duration.ofSeconds(1))).isTrue();

		List<AgentSession> evicted = registry.evictIdle(Duration.ZERO);

		assertThat(evicted).containsExactly(idle);
		assertThat(registry.knows("busy")).isTrue();
	}

	@Test
	void aTurnPermitAdmitsOneCallerAtATime() throws Exception {
		AgentSession session = registry.resolve("one", name -> "sid");

		assertThat(session.tryBeginTurn(Duration.ofMillis(50))).isTrue();
		assertThat(session.tryBeginTurn(Duration.ofMillis(50))).isFalse();

		session.endTurn();
		assertThat(session.tryBeginTurn(Duration.ofMillis(50))).isTrue();
	}

	@Test
	void endingATurnTwiceDoesNotHandOutTwoPermits() throws Exception {
		AgentSession session = registry.resolve("one", name -> "sid");
		assertThat(session.tryBeginTurn(Duration.ofMillis(50))).isTrue();

		session.endTurn();
		session.endTurn();

		assertThat(session.tryBeginTurn(Duration.ofMillis(50))).isTrue();
		assertThat(session.tryBeginTurn(Duration.ofMillis(50))).isFalse();
	}

	/**
	 * The counterpart to {@code resolve}'s idempotence, and the reason both exist: continuing a
	 * conversation must never create a second one, and binding a name to a session the agent
	 * already has must never quietly displace whatever that name meant before.
	 */
	@Test
	void adoptingAnOpenNameIsRefusedRatherThanSilentlyRebinding() {
		registry.adopt("review", "sid-1");

		assertThatThrownBy(() -> registry.adopt("review", "sid-2")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("sid-1").hasMessageContaining("sid-2");

		assertThat(registry.find("review")).get().extracting(AgentSession::sessionId).isEqualTo("sid-1");
	}

	@Test
	void adoptingValidatesTheName() {
		assertThatThrownBy(() -> registry.adopt("../escape", "sid-1")).isInstanceOf(IllegalArgumentException.class);
	}

	// --- principals and release -----------------------------------------------------------------

	private static SessionRegistry.Opened opened(String sessionId, AtomicInteger released) {
		return new SessionRegistry.Opened(sessionId, released::incrementAndGet);
	}

	@Test
	void aNameOpenForOnePrincipalIsRefusedToAnother() {
		SessionPrincipal alice = SessionPrincipal.of("alice");
		AtomicInteger created = new AtomicInteger();
		registry.resolve("ticket-42", alice, name -> opened("sid-" + created.incrementAndGet(), new AtomicInteger()));

		assertThatThrownBy(() -> registry.resolve("ticket-42", SessionPrincipal.of("bob"),
				name -> opened("sid-" + created.incrementAndGet(), new AtomicInteger())))
			.isInstanceOf(SessionOwnershipException.class).hasMessageNotContaining("alice")
			.hasMessageNotContaining("bob");
		assertThatThrownBy(() -> registry.resolve("ticket-42", name -> "sid-" + created.incrementAndGet()))
			.isInstanceOf(SessionOwnershipException.class);
		assertThat(registry.resolve("ticket-42", SessionPrincipal.of("alice"), name -> opened("unused", null))
			.sessionId()).isEqualTo("sid-1");
		assertThat(created).hasValue(1);
	}

	@Test
	void removingASessionReleasesWhatItHeldExactlyOnce() {
		AtomicInteger released = new AtomicInteger();
		registry.resolve("removed", null, name -> opened("sid-1", released));

		registry.remove("removed");
		registry.remove("removed");

		assertThat(released).hasValue(1);
	}

	@Test
	void evictionAndClearingReleaseToo() {
		AtomicInteger evicted = new AtomicInteger();
		AtomicInteger cleared = new AtomicInteger();
		registry.resolve("evicted", null, name -> opened("sid-1", evicted));

		registry.evictIdle(Duration.ofMillis(-1));
		registry.resolve("cleared", null, name -> opened("sid-2", cleared));
		registry.clear();
		registry.clear();

		assertThat(evicted).hasValue(1);
		assertThat(cleared).hasValue(1);
	}

	@Test
	void adoptingATakenNameReleasesWhatTheRefusedSessionWouldHaveHeld() {
		AtomicInteger released = new AtomicInteger();
		registry.adopt("taken", "sid-1");

		assertThatThrownBy(() -> registry.adopt("taken", "sid-2", null, released::incrementAndGet))
			.isInstanceOf(IllegalStateException.class);
		assertThat(released).hasValue(1);
	}

	@Test
	void aPrincipalNeverAppearsInItsOwnToString() {
		assertThat(SessionPrincipal.of("alice@example.com").toString()).doesNotContain("alice");
	}
}
