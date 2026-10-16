/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.auth.TellomiFeedTokens;
import org.whispersystems.textsecuregcm.configuration.TellomiEngineDeletionConfiguration;

/// The notifier with the real HTTP client against a mock engine on a loopback port (no real network).
class TellomiEngineAccountDeletionEndToEndTest {

  private static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-10-16T12:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final TellomiFeedTokens TOKENS = new TellomiFeedTokens(
      TellomiEngineDeletionTestSupport.bytes(32, (byte) 's'), TellomiEngineDeletionTestSupport.bytes(32, (byte) 'p'));

  private static TellomiEngineAccountDeletionNotifier notifier(final TellomiEngineDeletionConfiguration config,
      final InMemoryEngineDeletionOutbox outbox, final TestClock clock) {
    return new TellomiEngineAccountDeletionNotifier(config, TOKENS::pseudonym, outbox,
        new EngineDeletionClient(config)::delete, clock, () -> 0.0, null);
  }

  @Test
  void deletesTheRightPseudonymOnTheMockEngine() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      final TestClock clock = new TestClock();
      final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(10);
      final TellomiEngineAccountDeletionNotifier n =
          notifier(TellomiEngineDeletionTestSupport.enabled(engine.baseUrl()), outbox, clock);
      final UUID aci = UUID.randomUUID();

      n.onAccountDeleted(aci);
      n.drain();

      assertThat(engine.seen).hasSize(1);
      assertThat(engine.seen.getFirst().method()).isEqualTo("DELETE");
      assertThat(engine.seen.getFirst().path()).isEqualTo(
          "/internal/users/" + Base64.getUrlEncoder().withoutPadding().encodeToString(TOKENS.pseudonym(aci)));
      assertThat(outbox.pendingCount()).isZero();
    }
  }

  @Test
  void serverErrorsAreRetriedWithBoundedBackoffUntilTheEngineRecovers() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> n <= 2 ? MockEngine.Reply.status(503) : MockEngine.Reply.status(200);
      final TestClock clock = new TestClock();
      final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(10);
      final TellomiEngineAccountDeletionNotifier n =
          notifier(TellomiEngineDeletionTestSupport.enabled(engine.baseUrl()), outbox, clock);
      n.onAccountDeleted(UUID.randomUUID());

      n.drain();                                  // 503
      assertThat(n.drain()).as("still backing off").isZero();
      clock.now = clock.now.plus(Duration.ofSeconds(5));
      n.drain();                                  // 503
      clock.now = clock.now.plus(Duration.ofSeconds(10));
      n.drain();                                  // 200

      assertThat(engine.hits.get()).isEqualTo(3);
      assertThat(outbox.pendingCount()).isZero();
      assertThat(outbox.deadLetterCount()).isZero();
    }
  }

  @Test
  void anEngineThatNeverRecoversGetsExactlyMaxAttemptsThenTheEntryIsDeadLettered() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> MockEngine.Reply.status(500);
      final TestClock clock = new TestClock();
      final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(10);
      final TellomiEngineAccountDeletionNotifier n =
          notifier(TellomiEngineDeletionTestSupport.enabled(engine.baseUrl(), Duration.ofSeconds(2), 4, true), outbox, clock);
      n.onAccountDeleted(UUID.randomUUID());

      for (int i = 0; i < 20; i++) {
        n.drain();
        clock.now = clock.now.plus(Duration.ofMinutes(5));
      }

      assertThat(engine.hits.get()).isEqualTo(4);
      assertThat(outbox.deadLetterCount()).isEqualTo(1);
    }
  }

  @Test
  void aTimeoutIsRetriedThenSucceeds() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> n == 1 ? MockEngine.Reply.slow(1500) : MockEngine.Reply.status(200);
      final TestClock clock = new TestClock();
      final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(10);
      final TellomiEngineAccountDeletionNotifier n = notifier(
          TellomiEngineDeletionTestSupport.enabled(engine.baseUrl(), Duration.ofMillis(200), 4, true), outbox, clock);
      n.onAccountDeleted(UUID.randomUUID());

      n.drain();
      assertThat(outbox.pendingCount()).isEqualTo(1);
      clock.now = clock.now.plus(Duration.ofMinutes(1));
      n.drain();

      assertThat(outbox.pendingCount()).isZero();
      assertThat(outbox.deadLetterCount()).isZero();
    }
  }

  @Test
  void aBadRequestFromTheEngineIsNotRetried() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> MockEngine.Reply.status(400);
      final TestClock clock = new TestClock();
      final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(10);
      final TellomiEngineAccountDeletionNotifier n =
          notifier(TellomiEngineDeletionTestSupport.enabled(engine.baseUrl()), outbox, clock);
      n.onAccountDeleted(UUID.randomUUID());

      for (int i = 0; i < 5; i++) {
        n.drain();
        clock.now = clock.now.plus(Duration.ofHours(1));
      }
      assertThat(engine.hits.get()).isEqualTo(1);
      assertThat(outbox.deadLetterCount()).isEqualTo(1);
    }
  }

  @Test
  void aNotFoundIsDoneByDefaultAndPermanentWhenConfiguredOtherwise() throws Exception {
    for (final boolean notFoundMeansDeleted : new boolean[]{true, false}) {
      try (final MockEngine engine = new MockEngine()) {
        engine.script = n -> MockEngine.Reply.status(404);
        final TestClock clock = new TestClock();
        final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(10);
        final TellomiEngineAccountDeletionNotifier n = notifier(
            TellomiEngineDeletionTestSupport.enabled(engine.baseUrl(), Duration.ofSeconds(2), 4, notFoundMeansDeleted),
            outbox, clock);
        n.onAccountDeleted(UUID.randomUUID());
        n.drain();
        assertThat(outbox.pendingCount()).isZero();
        assertThat(outbox.deadLetterCount()).isEqualTo(notFoundMeansDeleted ? 0 : 1);
      }
    }
  }

  @Test
  void aDeadEngineDoesNotSlowOrFailTheCallerOfOnAccountDeleted() throws Exception {
    final TestClock clock = new TestClock();
    final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(10);
    // nothing listens on this port
    final TellomiEngineAccountDeletionNotifier n =
        notifier(TellomiEngineDeletionTestSupport.enabled("http://127.0.0.1:1"), outbox, clock);

    final long start = System.nanoTime();
    n.onAccountDeleted(UUID.randomUUID());
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
    assertThat(outbox.pendingCount()).isEqualTo(1);
  }

  @Test
  void noSecretsOrIdentifiersReachTheLogsThroughTheRealClient() throws Exception {
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    final Logger root = (Logger) LoggerFactory.getLogger("org.whispersystems.textsecuregcm.tellomi");
    root.setLevel(ch.qos.logback.classic.Level.DEBUG);
    root.addAppender(logs);
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> n == 1 ? MockEngine.Reply.status(503) : n == 2 ? MockEngine.Reply.slow(1500) : MockEngine.Reply.status(400);
      final TestClock clock = new TestClock();
      final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(10);
      final TellomiEngineAccountDeletionNotifier n = notifier(
          TellomiEngineDeletionTestSupport.enabled(engine.baseUrl(), Duration.ofMillis(200), 4, true), outbox, clock);
      final UUID aci = UUID.randomUUID();
      n.onAccountDeleted(aci);
      for (int i = 0; i < 3; i++) {
        n.drain();
        clock.now = clock.now.plus(Duration.ofMinutes(5));
      }

      final String text = logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
      assertThat(logs.list).isNotEmpty();
      assertThat(text).doesNotContain(aci.toString());
      assertThat(text).doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(TOKENS.pseudonym(aci)));
      assertThat(text).doesNotContain(Base64.getEncoder().encodeToString(TellomiEngineDeletionTestSupport.SERVICE_KEY));
      assertThat(text).doesNotContain("127.0.0.1").doesNotContain("/internal/users").doesNotContain("Bearer");
    } finally {
      root.detachAppender(logs);
    }
  }
}
