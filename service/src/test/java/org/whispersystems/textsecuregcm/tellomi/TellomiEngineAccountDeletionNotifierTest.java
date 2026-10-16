/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.auth.TellomiFeedTokens;
import org.whispersystems.textsecuregcm.configuration.TellomiEngineDeletionConfiguration;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionClient.Outcome;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionClient.Result;

class TellomiEngineAccountDeletionNotifierTest {

  /// a clock the test moves by hand
  private static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-10-16T12:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(final Duration d) {
      now = now.plus(d);
    }
  }

  private static final TellomiFeedTokens TOKENS = new TellomiFeedTokens(
      TellomiEngineDeletionTestSupport.bytes(32, (byte) 's'), TellomiEngineDeletionTestSupport.bytes(32, (byte) 'p'));

  // initial 10 s, max 80 s, 4 attempts; jitter 0 = the lower half (factor 0.5)
  private final TellomiEngineDeletionConfiguration config = TellomiEngineDeletionTestSupport.enabled("https://engine.example.invalid");
  private final TestClock clock = new TestClock();
  private final InMemoryEngineDeletionOutbox outbox = new InMemoryEngineDeletionOutbox(100);
  private final List<byte[]> sent = new ArrayList<>();
  private final List<Result> script = new ArrayList<>();
  private double jitter = 0.0;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final List<Logger> attached = new ArrayList<>();

  private TellomiEngineAccountDeletionNotifier notifier() {
    return notifier(config, TOKENS::pseudonym, outbox);
  }

  private TellomiEngineAccountDeletionNotifier notifier(final TellomiEngineDeletionConfiguration c,
      final Function<UUID, byte[]> pseudonym, final EngineDeletionOutbox box) {
    return new TellomiEngineAccountDeletionNotifier(c, pseudonym, box, sub -> {
      sent.add(sub);
      return script.isEmpty() ? new Result(Outcome.DELETED, 200, Optional.empty()) : script.removeFirst();
    }, clock, () -> jitter, null);
  }

  private static Result r(final Outcome outcome, final int status) {
    return new Result(outcome, status, Optional.empty());
  }

  @BeforeEach
  void captureLogs() {
    logs.start();
    for (final Class<?> c : List.of(TellomiEngineAccountDeletionNotifier.class, EngineDeletionClient.class,
        InMemoryEngineDeletionOutbox.class)) {
      final Logger l = (Logger) LoggerFactory.getLogger(c);
      l.setLevel(Level.DEBUG);
      l.addAppender(logs);
      attached.add(l);
    }
  }

  @AfterEach
  void releaseLogs() {
    attached.forEach(l -> l.detachAppender(logs));
    logs.stop();
  }

  private String allLogText() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
  }

  @Test
  void successDeletesOnceWithTheCredentialPseudonymAndForgetsTheEntry() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    final UUID aci = UUID.randomUUID();

    n.onAccountDeleted(aci);
    assertThat(outbox.pendingCount()).isEqualTo(1);
    assertThat(sent).as("queuing does no network I/O").isEmpty();

    assertThat(n.drain()).isEqualTo(1);

    assertThat(sent).hasSize(1);
    // the same derivation as the feed credential's `sub` (so the engine finds the right person)
    assertThat(sent.getFirst()).isEqualTo(TOKENS.pseudonym(aci));
    assertThat(outbox.pendingCount()).isZero();
    assertThat(outbox.deadLetterCount()).isZero();
    assertThat(n.drain()).as("nothing left to do").isZero();
  }

  @Test
  void theOutboxHoldsThePseudonymNotTheAci() {
    final UUID aci = UUID.randomUUID();
    notifier().onAccountDeleted(aci);
    final EngineDeletionOutbox.Entry entry = outbox.due(clock.instant(), 10).getFirst();
    assertThat(entry.sub()).isEqualTo(TOKENS.pseudonym(aci));
    assertThat(entry.toString()).doesNotContain(aci.toString());
  }

  @Test
  void aSecondNoticeForTheSamePersonDoesNotQueueASecondRequest() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    final UUID aci = UUID.randomUUID();
    n.onAccountDeleted(aci);
    n.onAccountDeleted(aci);
    assertThat(outbox.pendingCount()).isEqualTo(1);
    n.drain();
    assertThat(sent).hasSize(1);
  }

  @Test
  void alreadyGoneCountsAsDone() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    script.add(r(Outcome.ALREADY_GONE, 404));
    n.onAccountDeleted(UUID.randomUUID());
    n.drain();
    assertThat(outbox.pendingCount()).isZero();
    assertThat(outbox.deadLetterCount()).isZero();
    script.add(r(Outcome.ALREADY_GONE, 410));
    n.onAccountDeleted(UUID.randomUUID());
    n.drain();
    assertThat(outbox.pendingCount()).isZero();
  }

  @Test
  void retryableFailuresBackOffExponentiallyWithJitterThenDeadLetter() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    n.onAccountDeleted(UUID.randomUUID());
    for (int i = 0; i < 3; i++) {
      script.add(r(Outcome.RETRY, 503));
    }
    script.add(r(Outcome.RETRY, 0)); // 4th attempt = maxAttempts

    // attempt 1 fails: wait = 10 s * 2^0 * 0.5 = 5 s
    n.drain();
    assertThat(sent).hasSize(1);
    assertThat(outbox.due(clock.instant(), 10)).isEmpty();
    clock.advance(Duration.ofMillis(4_999));
    assertThat(n.drain()).as("not before the backoff has passed").isZero();
    clock.advance(Duration.ofMillis(1));

    // attempt 2 fails: 10 s * 2 * 0.5 = 10 s
    assertThat(n.drain()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(9));
    assertThat(n.drain()).isZero();
    clock.advance(Duration.ofSeconds(1));

    // attempt 3 fails: 10 s * 4 * 0.5 = 20 s
    assertThat(n.drain()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(20));

    // attempt 4 fails and is the last one: dead letter, no more requests
    assertThat(n.drain()).isEqualTo(1);
    assertThat(sent).hasSize(4);
    assertThat(outbox.pendingCount()).isZero();
    assertThat(outbox.deadLetterCount()).isEqualTo(1);
    assertThat(outbox.deadLetters().getFirst().attempts()).isEqualTo(4);
    clock.advance(Duration.ofDays(1));
    assertThat(n.drain()).isZero();
    assertThat(sent).hasSize(4);
  }

  @Test
  void backoffIsCappedAndJitterStaysInTheUpperHalf() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    jitter = 0.0;
    assertThat(n.backoff(1, Optional.empty())).isEqualTo(Duration.ofSeconds(5));
    assertThat(n.backoff(2, Optional.empty())).isEqualTo(Duration.ofSeconds(10));
    assertThat(n.backoff(4, Optional.empty())).isEqualTo(Duration.ofSeconds(40));
    assertThat(n.backoff(5, Optional.empty())).as("80 s cap * 0.5").isEqualTo(Duration.ofSeconds(40));
    assertThat(n.backoff(50, Optional.empty())).as("no overflow").isEqualTo(Duration.ofSeconds(40));
    jitter = 0.999999;
    assertThat(n.backoff(1, Optional.empty())).isBetween(Duration.ofMillis(9_990), Duration.ofSeconds(10));
    assertThat(n.backoff(50, Optional.empty())).isBetween(Duration.ofMillis(79_900), Duration.ofSeconds(80));
    jitter = 5.0; // a broken random source cannot push the delay out of range
    assertThat(n.backoff(1, Optional.empty())).isLessThanOrEqualTo(Duration.ofSeconds(10));
    jitter = -3.0;
    assertThat(n.backoff(1, Optional.empty())).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void retryAfterFromTheEngineWinsWhenLonger() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    n.onAccountDeleted(UUID.randomUUID());
    script.add(new Result(Outcome.RETRY, 429, Optional.of(Duration.ofMinutes(10))));
    n.drain();
    clock.advance(Duration.ofMinutes(9));
    assertThat(n.drain()).isZero();
    clock.advance(Duration.ofMinutes(1));
    assertThat(n.drain()).isEqualTo(1);
  }

  @Test
  void aRoundStopsAfterTheFirstRetryableFailure() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    for (int i = 0; i < 5; i++) {
      n.onAccountDeleted(UUID.randomUUID());
    }
    script.add(r(Outcome.RETRY, 503));

    assertThat(n.drain()).as("the engine looks down: do not spend an attempt of every queued entry").isEqualTo(1);
    assertThat(sent).hasSize(1);
    assertThat(outbox.pendingCount()).isEqualTo(5);

    // the next round goes on with the others (the failed one is waiting out its backoff)
    assertThat(n.drain()).isEqualTo(4);
    assertThat(outbox.pendingCount()).isEqualTo(1);
  }

  @Test
  void permanentClientErrorIsNotRetriedButIsAudited() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    n.onAccountDeleted(UUID.randomUUID());
    script.add(r(Outcome.PERMANENT, 400));
    n.drain();

    assertThat(sent).hasSize(1);
    assertThat(outbox.pendingCount()).isZero();
    assertThat(outbox.deadLetters()).hasSize(1);
    assertThat(outbox.deadLetters().getFirst().lastOutcome()).isEqualTo("permanent_400");
    clock.advance(Duration.ofDays(1));
    n.drain();
    assertThat(sent).hasSize(1);
    // the audit trail: a WARN with the reason and status
    assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
        && e.getFormattedMessage().contains("dead_letter") && e.getFormattedMessage().contains("permanent_400"));
  }

  @Test
  void rejectedCredentialsAreDeadLetteredAndCanBeRequeuedAfterTheFix() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    n.onAccountDeleted(UUID.randomUUID());
    n.onAccountDeleted(UUID.randomUUID());
    script.add(r(Outcome.AUTH_FAILED, 401));
    script.add(r(Outcome.AUTH_FAILED, 401));
    n.drain();
    assertThat(outbox.deadLetterCount()).isEqualTo(2);
    assertThat(outbox.deadLetters()).allMatch(e -> e.lastOutcome().equals("auth_failed"));
    assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("rejected")))
        .as("the credential problem is logged loudly, once").hasSize(1);

    // operator fixed the key
    assertThat(outbox.requeueDeadLetters(clock.instant())).isEqualTo(2);
    assertThat(outbox.deadLetterCount()).isZero();
    n.drain();
    assertThat(outbox.pendingCount()).isZero();
    assertThat(sent).hasSize(4);
  }

  @Test
  void accountDeletionIsNeverBlockedByTheNotifier() {
    // 1. the pseudonym derivation blows up
    final TellomiEngineAccountDeletionNotifier broken = notifier(config, aci -> {
      throw new IllegalStateException("boom " + aci);
    }, outbox);
    broken.onAccountDeleted(UUID.randomUUID()); // must not throw

    // 2. the outbox is full
    final InMemoryEngineDeletionOutbox tiny = new InMemoryEngineDeletionOutbox(1);
    final TellomiEngineAccountDeletionNotifier small = notifier(config, TOKENS::pseudonym, tiny);
    small.onAccountDeleted(UUID.randomUUID());
    small.onAccountDeleted(UUID.randomUUID()); // dropped, must not throw
    assertThat(tiny.pendingCount()).isEqualTo(1);

    // 3. the outbox itself throws
    final EngineDeletionOutbox hostile = new InMemoryEngineDeletionOutbox(1) {
      @Override
      public synchronized boolean enqueue(final byte[] sub, final Instant now) {
        throw new RuntimeException("storage down");
      }
    };
    notifier(config, TOKENS::pseudonym, hostile).onAccountDeleted(UUID.randomUUID());

    assertThat(sent).as("none of this touched the network").isEmpty();
  }

  @Test
  void aSenderThatThrowsCountsAsAFailedAttempt() {
    final TellomiEngineAccountDeletionNotifier n = new TellomiEngineAccountDeletionNotifier(config, TOKENS::pseudonym, outbox,
        sub -> {
          throw new IllegalStateException("bug");
        }, clock, () -> 0.0, null);
    n.onAccountDeleted(UUID.randomUUID());
    n.drain();
    assertThat(outbox.pendingCount()).isEqualTo(1);
    assertThat(outbox.due(clock.instant(), 10)).as("backing off, not spinning").isEmpty();
  }

  @Test
  void logsCarryNoAciPseudonymKeyOrUrl() {
    final TellomiEngineAccountDeletionNotifier n = notifier();
    final UUID aci = UUID.randomUUID();
    final byte[] sub = TOKENS.pseudonym(aci);
    n.onAccountDeleted(aci);
    // every kind of ending: retry, then success; and another person who ends in the dead letter box
    script.add(r(Outcome.RETRY, 503));
    n.drain();
    clock.advance(Duration.ofMinutes(5));
    n.drain();
    n.onAccountDeleted(UUID.randomUUID());
    script.add(r(Outcome.PERMANENT, 400));
    n.drain();
    n.onAccountDeleted(UUID.randomUUID());
    script.add(r(Outcome.AUTH_FAILED, 401));
    n.drain();
    n.onAccountDeleted(UUID.randomUUID());
    script.add(r(Outcome.ALREADY_GONE, 404));
    n.drain();

    final String text = allLogText();
    assertThat(logs.list).isNotEmpty();
    assertThat(text).doesNotContain(aci.toString());
    assertThat(text).doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(sub));
    assertThat(text).doesNotContain(Base64.getEncoder().encodeToString(sub));
    assertThat(text).doesNotContain(Base64.getEncoder().encodeToString(TellomiEngineDeletionTestSupport.SERVICE_KEY));
    assertThat(text).doesNotContain("engine.example.invalid").doesNotContain("/internal/users").doesNotContain("Bearer");
    assertThat(text).doesNotContain(Arrays.toString(sub));
  }
}
