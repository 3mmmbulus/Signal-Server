/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtension;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionOutbox.Entry;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionOutbox.Kind;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;

/// The durable outbox against a real DynamoDB (DynamoDB Local): the way the other storage tests of this module run
/// (`DynamoDbExtension`, a Docker container). On a machine without Docker, point `TELLOMI_DYNAMODB_LOCAL_ENDPOINT` at a
/// running DynamoDB Local (e.g. `http://localhost:8123`).
class DynamoDbEngineDeletionOutboxTest {

  static final String TABLE = "TellomiEngineDeletionOutbox_test";

  /// the production table shape ({@link DynamoDbEngineDeletionOutbox#createTableRequest}) as a test schema
  static final DynamoDbExtension.TableSchema SCHEMA = new DynamoDbExtension.TableSchema() {
    private final CreateTableRequest request = DynamoDbEngineDeletionOutbox.createTableRequest(TABLE);

    @Override public String tableName() { return TABLE; }
    @Override public String hashKeyName() { return DynamoDbEngineDeletionOutbox.KEY; }
    @Override public String rangeKeyName() { return null; }
    @Override public List<software.amazon.awssdk.services.dynamodb.model.AttributeDefinition> attributeDefinitions() {
      return request.attributeDefinitions();
    }
    @Override public List<GlobalSecondaryIndex> globalSecondaryIndexes() {
      return request.globalSecondaryIndexes().stream()
          .map(i -> i.toBuilder().provisionedThroughput(ProvisionedThroughput.builder()
              .readCapacityUnits(20L).writeCapacityUnits(20L).build()).build())
          .toList();
    }
    @Override public List<software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex> localSecondaryIndexes() {
      return List.of();
    }
  };

  @RegisterExtension
  static final DynamoDbExtension EXTENSION = newExtension();

  static DynamoDbExtension newExtension() {
    final DynamoDbExtension extension = new DynamoDbExtension(SCHEMA);
    final String local = System.getenv("TELLOMI_DYNAMODB_LOCAL_ENDPOINT");
    if (local != null && !local.isBlank()) {
      extension.setEndpointOverride(local);
    }
    return extension;
  }

  /// a clock the test moves by hand
  static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-10-16T12:00:00Z");

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(final ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
    void advance(final Duration d) { now = now.plus(d); }
  }

  static final Duration TTL = Duration.ofDays(30);

  private final TestClock clock = new TestClock();
  private DynamoDbEngineDeletionOutbox outbox;

  private static byte[] sub(final int seed) {
    final byte[] b = new byte[16];
    java.util.Arrays.fill(b, (byte) seed);
    b[0] = (byte) (seed >> 8);
    return b;
  }

  private DynamoDbEngineDeletionOutbox newNode() {
    return new DynamoDbEngineDeletionOutbox(EXTENSION.getDynamoDbClient(), TABLE, clock, TTL);
  }

  @BeforeEach
  void setUp() {
    outbox = newNode();
  }

  @Test
  void anEntrySurvivesARestart() {
    final UUID aci = UUID.randomUUID();
    assertThat(outbox.enqueue(aci, sub(1), clock.instant())).isTrue();

    // "restart": a brand-new outbox object (a new process) over the same table
    final DynamoDbEngineDeletionOutbox afterRestart = newNode();
    final List<Entry> due = afterRestart.due(clock.instant(), 10);

    assertThat(due).hasSize(1);
    assertThat(due.getFirst().sub()).isEqualTo(sub(1));
    assertThat(due.getFirst().aci()).isEqualTo(aci);
    assertThat(due.getFirst().kind()).isEqualTo(Kind.DELETE);
    assertThat(due.getFirst().attempts()).isZero();
    assertThat(due.getFirst().enqueuedAt()).isEqualTo(clock.instant());
  }

  @Test
  void aRescheduledAttemptCountAndTheDeadLetterSurviveARestart() {
    outbox.enqueue(UUID.randomUUID(), sub(1), clock.instant());
    outbox.enqueue(UUID.randomUUID(), sub(2), clock.instant());
    final List<Entry> due = outbox.due(clock.instant(), 10);
    final Entry one = due.stream().filter(e -> e.sub()[1] == 1).findFirst().orElseThrow();
    final Entry two = due.stream().filter(e -> e.sub()[1] == 2).findFirst().orElseThrow();
    outbox.reschedule(one, 3, clock.instant().plusSeconds(60), "retry_503");
    outbox.deadLetter(two, 8, "retries_exhausted");

    final DynamoDbEngineDeletionOutbox afterRestart = newNode();
    assertThat(afterRestart.due(clock.instant(), 10)).as("one is backing off, one is dead").isEmpty();
    clock.advance(Duration.ofSeconds(61));
    final List<Entry> later = afterRestart.due(clock.instant(), 10);
    assertThat(later).hasSize(1);
    assertThat(later.getFirst().attempts()).isEqualTo(3);
    assertThat(later.getFirst().lastOutcome()).isEqualTo("retry_503");
    assertThat(afterRestart.deadLetters()).hasSize(1);
    assertThat(afterRestart.deadLetters().getFirst().lastOutcome()).isEqualTo("retries_exhausted");
    assertThat(afterRestart.deadLetters().getFirst().attempts()).isEqualTo(8);
  }

  @Test
  void onlyOneEntryPerPseudonym() {
    final byte[] sub = sub(1);
    assertThat(outbox.enqueue(UUID.randomUUID(), sub, clock.instant())).isTrue();
    assertThat(outbox.enqueue(UUID.randomUUID(), sub, clock.instant())).as("already there").isTrue();
    assertThat(outbox.enqueueSweep(UUID.randomUUID(), sub, clock.instant(), clock.instant())).as("no sweep on top").isFalse();
    assertThat(outbox.due(clock.instant(), 10)).hasSize(1);
    assertThat(outbox.pendingCount()).isEqualTo(1);
  }

  @Test
  void aNewDeleteReplacesAPendingSweepButNotTheOtherWayRound() {
    final byte[] sub = sub(1);
    final UUID oldAci = UUID.randomUUID();
    final UUID newAci = UUID.randomUUID();
    assertThat(outbox.enqueueSweep(oldAci, sub, clock.instant().plusSeconds(4200), clock.instant())).isTrue();

    clock.advance(Duration.ofMinutes(10));
    assertThat(outbox.enqueue(newAci, sub, clock.instant())).isTrue();

    final List<Entry> due = outbox.due(clock.instant(), 10);
    assertThat(due).hasSize(1);
    assertThat(due.getFirst().kind()).as("the newer deletion supersedes the old schedule").isEqualTo(Kind.DELETE);
    assertThat(due.getFirst().aci()).isEqualTo(newAci);
    assertThat(due.getFirst().enqueuedAt()).isEqualTo(clock.instant());
    assertThat(due.getFirst().attempts()).isZero();
  }

  @Test
  void aSweepIsOnlyDueWhenItsTimeComes() {
    outbox.enqueueSweep(UUID.randomUUID(), sub(1), clock.instant().plusSeconds(4200), clock.instant());
    assertThat(outbox.due(clock.instant(), 10)).isEmpty();
    clock.advance(Duration.ofSeconds(4201));
    final List<Entry> due = outbox.due(clock.instant(), 10);
    assertThat(due).hasSize(1);
    assertThat(due.getFirst().kind()).isEqualTo(Kind.SWEEP);
  }

  @Test
  void aReRegistrationOnAnotherNodeCancelsTheSweep() {
    final byte[] sub = sub(1);
    final DynamoDbEngineDeletionOutbox nodeA = newNode();
    final DynamoDbEngineDeletionOutbox nodeB = newNode();
    nodeA.enqueueSweep(UUID.randomUUID(), sub, clock.instant().plusSeconds(4200), clock.instant());

    assertThat(nodeB.cancelSweep(sub)).as("the table is shared, so the cancel reaches the sweep").isTrue();

    clock.advance(Duration.ofHours(2));
    assertThat(nodeA.due(clock.instant(), 10)).isEmpty();
    assertThat(nodeB.cancelSweep(sub)).as("nothing left to cancel").isFalse();
  }

  @Test
  void cancelSweepNeverRemovesADeleteOrADeadLetter() {
    final byte[] pending = sub(1);
    final byte[] dead = sub(2);
    outbox.enqueue(UUID.randomUUID(), pending, clock.instant());
    outbox.enqueue(UUID.randomUUID(), dead, clock.instant());
    final Entry deadEntry = outbox.due(clock.instant(), 10).stream().filter(e -> e.sub()[1] == 2).findFirst().orElseThrow();
    outbox.deadLetter(deadEntry, 8, "permanent_400");

    assertThat(outbox.cancelSweep(pending)).isFalse();
    assertThat(outbox.cancelSweep(dead)).isFalse();

    assertThat(outbox.pendingCount()).isEqualTo(1);
    assertThat(outbox.deadLetterCount()).isEqualTo(1);
  }

  @Test
  void twoNodesDoNotSendTheSameEntryAtTheSameTime() {
    outbox.enqueue(UUID.randomUUID(), sub(1), clock.instant());
    final DynamoDbEngineDeletionOutbox other = newNode();

    final List<Entry> first = outbox.due(clock.instant(), 10);
    final List<Entry> second = other.due(clock.instant(), 10);

    assertThat(first).hasSize(1);
    assertThat(second).as("claimed by the first node's lease").isEmpty();
  }

  @Test
  void aCrashedWorkersEntryIsPickedUpWhenItsLeaseExpires() {
    outbox.enqueue(UUID.randomUUID(), sub(1), clock.instant());
    assertThat(outbox.due(clock.instant(), 10)).hasSize(1); // claimed, then the worker "dies"

    final DynamoDbEngineDeletionOutbox other = newNode();
    clock.advance(DynamoDbEngineDeletionOutbox.LEASE_DURATION.minusSeconds(1));
    assertThat(other.due(clock.instant(), 10)).as("lease still running").isEmpty();
    clock.advance(Duration.ofSeconds(2));
    assertThat(other.due(clock.instant(), 10)).as("lease over").hasSize(1);
  }

  @Test
  void dueReturnsAtMostLimitOldestFirst() {
    for (int i = 1; i <= 5; i++) {
      outbox.enqueue(UUID.randomUUID(), sub(i), clock.instant());
      clock.advance(Duration.ofSeconds(1));
    }
    final List<Entry> due = outbox.due(clock.instant(), 3);
    assertThat(due).hasSize(3);
    assertThat(due).extracting(e -> (int) e.sub()[1]).containsExactly(1, 2, 3);
    assertThat(outbox.due(clock.instant(), 10)).as("the other two, the first three are leased").hasSize(2);
  }

  @Test
  void completeForgetsTheEntryAndItsAci() {
    final UUID aci = UUID.randomUUID();
    outbox.enqueue(aci, sub(1), clock.instant());
    outbox.complete(outbox.due(clock.instant(), 10).getFirst());

    assertThat(outbox.pendingCount()).isZero();
    assertThat(EXTENSION.getDynamoDbClient().scan(b -> b.tableName(TABLE)).items())
        .as("nothing, in particular no ACI, is left in the table").isEmpty();
  }

  @Test
  void aStaleCopyOfAnEntryChangesNothingAfterItWasReplaced() {
    final byte[] sub = sub(1);
    outbox.enqueueSweep(UUID.randomUUID(), sub, clock.instant(), clock.instant());
    final Entry staleSweep = outbox.due(clock.instant(), 10).getFirst();   // a worker holds this

    // meanwhile the person deleted the (re-registered) account again: the sweep becomes a new first delete
    final DynamoDbEngineDeletionOutbox other = newNode();
    clock.advance(Duration.ofSeconds(1));
    assertThat(other.enqueue(UUID.randomUUID(), sub, clock.instant())).isTrue();

    outbox.complete(staleSweep);
    outbox.reschedule(staleSweep, 5, clock.instant().plusSeconds(999), "retry_503");
    outbox.deadLetter(staleSweep, 8, "permanent_400");

    final List<Entry> due = other.due(clock.instant(), 10);
    assertThat(due).as("the new delete is untouched by the stale worker").hasSize(1);
    assertThat(due.getFirst().kind()).isEqualTo(Kind.DELETE);
    assertThat(due.getFirst().attempts()).isZero();
    assertThat(other.deadLetterCount()).isZero();
  }

  @Test
  void requeueMovesDeadLettersBackToPendingWithFreshAttempts() {
    outbox.enqueue(UUID.randomUUID(), sub(1), clock.instant());
    outbox.deadLetter(outbox.due(clock.instant(), 10).getFirst(), 8, "auth_failed");
    assertThat(outbox.deadLetters()).hasSize(1);
    assertThat(outbox.due(clock.instant(), 10)).isEmpty();

    clock.advance(Duration.ofHours(1));
    assertThat(outbox.requeueDeadLetters(clock.instant())).isEqualTo(1);

    final List<Entry> due = outbox.due(clock.instant(), 10);
    assertThat(due).hasSize(1);
    assertThat(due.getFirst().attempts()).isZero();
    assertThat(due.getFirst().enqueuedAt()).as("keeps the time of the deletion (the live check uses it)")
        .isEqualTo(Instant.parse("2026-10-16T12:00:00Z"));
    assertThat(outbox.deadLetters()).isEmpty();
    assertThat(outbox.requeueDeadLetters(clock.instant())).isZero();
  }

  @Test
  void everyRowExpiresAfterTheConfiguredTtl() {
    outbox.enqueue(UUID.randomUUID(), sub(1), clock.instant());
    outbox.enqueueSweep(UUID.randomUUID(), sub(2), clock.instant().plusSeconds(4200), clock.instant());

    final List<Map<String, AttributeValue>> items = EXTENSION.getDynamoDbClient().scan(b -> b.tableName(TABLE)).items();
    assertThat(items).hasSize(2);
    for (final Map<String, AttributeValue> item : items) {
      assertThat(Long.parseLong(item.get(DynamoDbEngineDeletionOutbox.EXPIRES).n()))
          .isEqualTo(clock.instant().plus(TTL).getEpochSecond());
    }
  }

  @Test
  void countsAreCachedButFollowChangesOnceTheCacheIsStale() {
    outbox.enqueue(UUID.randomUUID(), sub(1), clock.instant());
    assertThat(outbox.pendingCount()).isEqualTo(1);
    outbox.enqueue(UUID.randomUUID(), sub(2), clock.instant());
    assertThat(outbox.pendingCount()).as("cached").isEqualTo(1);
    clock.advance(DynamoDbEngineDeletionOutbox.COUNT_CACHE.plusSeconds(1));
    assertThat(outbox.pendingCount()).isEqualTo(2);
  }

  @Test
  void anEntryNeverPrintsTheAciOrThePseudonym() {
    final UUID aci = UUID.randomUUID();
    outbox.enqueue(aci, sub(7), clock.instant());
    final Entry e = outbox.due(clock.instant(), 1).getFirst();
    assertThat(e.toString()).doesNotContain(aci.toString())
        .doesNotContain(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(e.sub()));
  }

  @Test
  void verifyTableAcceptsTheDocumentedShapeAndRejectsAMissingOrWrongTable() {
    outbox.verifyTable(); // the test table has the production shape

    final String prod = "TellomiEngineDeletionOutbox_prodshape";
    EXTENSION.getDynamoDbClient().createTable(DynamoDbEngineDeletionOutbox.createTableRequest(prod));
    try {
      new DynamoDbEngineDeletionOutbox(EXTENSION.getDynamoDbClient(), prod, clock, TTL).verifyTable();
    } finally {
      EXTENSION.getDynamoDbClient().deleteTable(b -> b.tableName(prod));
    }

    assertThatThrownBy(() -> new DynamoDbEngineDeletionOutbox(EXTENSION.getDynamoDbClient(), "no_such_table", clock, TTL)
        .verifyTable()).isInstanceOf(IllegalStateException.class).hasMessageContaining("does not exist");

    final String wrong = "TellomiEngineDeletionOutbox_noindex";
    EXTENSION.getDynamoDbClient().createTable(b -> b.tableName(wrong)
        .billingMode(software.amazon.awssdk.services.dynamodb.model.BillingMode.PAY_PER_REQUEST)
        .keySchema(k -> k.attributeName("S").keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.HASH))
        .attributeDefinitions(a -> a.attributeName("S")
            .attributeType(software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType.S)));
    try {
      assertThatThrownBy(() -> new DynamoDbEngineDeletionOutbox(EXTENSION.getDynamoDbClient(), wrong, clock, TTL)
          .verifyTable()).isInstanceOf(IllegalStateException.class).hasMessageContaining("index");
    } finally {
      EXTENSION.getDynamoDbClient().deleteTable(b -> b.tableName(wrong));
    }
  }

  @Test
  void theRowHoldsExactlyTheDocumentedAttributes() {
    final UUID aci = UUID.randomUUID();
    outbox.enqueue(aci, sub(3), clock.instant());
    final Map<String, AttributeValue> item = EXTENSION.getDynamoDbClient().getItem(GetItemRequest.builder()
        .tableName(TABLE)
        .key(Map.of("S", AttributeValue.builder().s(
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sub(3))).build()))
        .consistentRead(true).build()).item();
    assertThat(item.keySet()).containsExactlyInAnyOrder("S", "I", "K", "A", "T", "N", "Q", "O", "X", "P");
    assertThat(item.get("A").s()).isEqualTo(aci.toString());
  }
}
