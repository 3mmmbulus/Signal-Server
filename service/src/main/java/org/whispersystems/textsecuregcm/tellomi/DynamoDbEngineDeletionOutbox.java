/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/// Durable, multi-node {@link EngineDeletionOutbox} on a DynamoDB table (ACCOUNT-DELETION-ENGINE-DESIGN.md §13.1).
///
/// **Why DynamoDB.** Account records, deleted-accounts, verification sessions, change-number waiting periods and
/// the other small "pending work" records of this server already live in DynamoDB (one table each, a TTL attribute, a
/// sync `DynamoDbClient` from `DynamoDbFromConfig`), so no new kind of infrastructure or client is needed and the
/// outbox shares the account store's durability and region. Redis is a cache here (data can be flushed) and
/// FoundationDB is used for message storage, not for this kind of record.
///
/// **Table** ({@link #createTableRequest}): hash key `S` (the pseudonym, base64url) — at most one entry per
/// pseudonym, which is what makes `enqueue` idempotent and lets a re-registration on *another node* cancel the second
/// delete. Attributes: `I` entry id, `K` kind (`D` delete / `W` sweep), `A` ACI, `T` attempts, `N` next attempt (epoch
/// ms), `Q` enqueued at (epoch ms), `O` last outcome, `X` expiry (epoch seconds; **enable DynamoDB TTL on `X`**), `L`
/// lease until (epoch ms), `P` = `"1"` while the entry is pending (absent on dead letters). A sparse global secondary
/// index `P-N` (hash `P`, range `N`) lists the pending entries by due time.
///
/// **Concurrency.** Every change is a conditional write that also checks the entry id, so a worker holding a stale
/// copy (the entry was cancelled, replaced by a newer delete, or requeued) changes nothing. {@link #due} claims an
/// entry with a lease (`L`) before returning it; a crashed worker's entries become due again when the lease expires.
/// The same entry can still be sent twice only if a worker is slower than the lease — the engine's delete is
/// idempotent and the live-account check runs before every late send.
///
/// **Privacy.** The row holds the pseudonym and the ACI (needed for the live-account check, see {@link EngineDeletionOutbox}).
/// It is deleted when the entry completes or is cancelled; `X` bounds everything else (dead letters). Nothing here
/// logs either value.
///
/// Costs/limits: `enqueue` and `cancelSweep` do one or two DynamoDB calls on the thread that deletes the account
/// (inside the account lock); failures are caught by the notifier and never fail the deletion. `maxPending` of the
/// in-memory outbox does not apply: DynamoDB is the bound, alert on the `pending` gauge. The gauges use a count that is
/// refreshed at most every {@link #COUNT_CACHE} (a scan of a table that only holds undelivered work).
public class DynamoDbEngineDeletionOutbox implements EngineDeletionOutbox {

  private static final Logger log = LoggerFactory.getLogger(DynamoDbEngineDeletionOutbox.class);

  static final String KEY = "S";
  static final String ID = "I";
  static final String KIND = "K";
  static final String ACI = "A";
  static final String ATTEMPTS = "T";
  static final String NEXT_ATTEMPT = "N";
  static final String ENQUEUED = "Q";
  static final String OUTCOME = "O";
  static final String EXPIRES = "X";
  static final String LEASE = "L";
  static final String PENDING = "P";
  static final String PENDING_INDEX = "P-N";

  private static final String KIND_DELETE = "D";
  private static final String KIND_SWEEP = "W";

  /// how long a claim lasts; must exceed a full batch of slow engine calls ({@link
  /// TellomiEngineAccountDeletionNotifier#BATCH_SIZE} x request timeout is the worst case, a failing call ends the round)
  static final Duration LEASE_DURATION = Duration.ofMinutes(15);
  static final Duration COUNT_CACHE = Duration.ofSeconds(30);
  static final int MAX_DEAD_LETTERS_LISTED = 1_000;

  private final DynamoDbClient dynamoDb;
  private final String tableName;
  private final Clock clock;
  private final Duration entryTtl;

  private volatile int cachedPending;
  private volatile int cachedDead;
  private volatile Instant countsRefreshedAt = Instant.MIN;

  public DynamoDbEngineDeletionOutbox(final DynamoDbClient dynamoDb, final String tableName, final Clock clock,
      final Duration entryTtl) {
    this.dynamoDb = dynamoDb;
    this.tableName = tableName;
    this.clock = clock;
    this.entryTtl = entryTtl;
  }

  /// The table this class expects (also what the tests create). On-demand billing; the infrastructure owner creates
  /// the production table from this shape and enables TTL on `X`.
  public static CreateTableRequest createTableRequest(final String tableName) {
    return CreateTableRequest.builder()
        .tableName(tableName)
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .keySchema(KeySchemaElement.builder().attributeName(KEY).keyType(KeyType.HASH).build())
        .attributeDefinitions(
            AttributeDefinition.builder().attributeName(KEY).attributeType(ScalarAttributeType.S).build(),
            AttributeDefinition.builder().attributeName(PENDING).attributeType(ScalarAttributeType.S).build(),
            AttributeDefinition.builder().attributeName(NEXT_ATTEMPT).attributeType(ScalarAttributeType.N).build())
        .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
            .indexName(PENDING_INDEX)
            .keySchema(KeySchemaElement.builder().attributeName(PENDING).keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName(NEXT_ATTEMPT).keyType(KeyType.RANGE).build())
            .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
            .build())
        .build();
  }

  /// Startup check: the table and its index exist (so that the first account deletion does not find out). Throws
  /// `IllegalStateException` with no table name echoed beyond what the operator configured.
  public void verifyTable() {
    final DescribeTableResponse described;
    try {
      described = dynamoDb.describeTable(b -> b.tableName(tableName));
    } catch (final ResourceNotFoundException e) {
      throw new IllegalStateException("tellomiEngineDeletion.outboxTableName: the table does not exist");
    }
    final boolean keyOk = described.table().keySchema().stream()
        .anyMatch(k -> k.attributeName().equals(KEY) && k.keyType() == KeyType.HASH);
    final boolean indexOk = described.table().globalSecondaryIndexes() != null
        && described.table().globalSecondaryIndexes().stream().anyMatch(i -> i.indexName().equals(PENDING_INDEX));
    if (!keyOk || !indexOk) {
      throw new IllegalStateException(
          "tellomiEngineDeletion.outboxTableName: the table must have hash key " + KEY + " and index " + PENDING_INDEX);
    }
  }

  // ---------------------------------------------------------------------------------------------------------------

  private static String keyOf(final byte[] sub) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(sub);
  }

  private static AttributeValue s(final String v) {
    return AttributeValue.builder().s(v).build();
  }

  private static AttributeValue n(final long v) {
    return AttributeValue.builder().n(Long.toString(v)).build();
  }

  private long expiresAtSeconds(final Instant enqueuedAt) {
    return enqueuedAt.plus(entryTtl).getEpochSecond();
  }

  private Map<String, AttributeValue> newItem(final Kind kind, final UUID aci, final byte[] sub, final Instant due,
      final Instant enqueuedAt) {
    return Map.of(
        KEY, s(keyOf(sub)),
        ID, s(UUID.randomUUID().toString()),
        KIND, s(kind == Kind.SWEEP ? KIND_SWEEP : KIND_DELETE),
        ACI, s(aci.toString()),
        ATTEMPTS, n(0),
        NEXT_ATTEMPT, n(due.toEpochMilli()),
        ENQUEUED, n(enqueuedAt.toEpochMilli()),
        OUTCOME, s("none"),
        EXPIRES, n(expiresAtSeconds(enqueuedAt)),
        PENDING, s("1"));
  }

  private static Entry toEntry(final Map<String, AttributeValue> item) {
    return new Entry(
        UUID.fromString(item.get(ID).s()),
        KIND_SWEEP.equals(item.get(KIND).s()) ? Kind.SWEEP : Kind.DELETE,
        Base64.getUrlDecoder().decode(item.get(KEY).s()),
        UUID.fromString(item.get(ACI).s()),
        Integer.parseInt(item.get(ATTEMPTS).n()),
        Instant.ofEpochMilli(Long.parseLong(item.get(NEXT_ATTEMPT).n())),
        Instant.ofEpochMilli(Long.parseLong(item.get(ENQUEUED).n())),
        item.get(OUTCOME).s());
  }

  // ---------------------------------------------------------------------------------------------------------------

  @Override
  public boolean enqueue(final UUID aci, final byte[] sub, final Instant now) {
    for (int round = 0; round < 3; round++) {
      try {
        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(newItem(Kind.DELETE, aci, sub, now, now))
            .conditionExpression("attribute_not_exists(" + KEY + ")")
            .build());
        return true;
      } catch (final ConditionalCheckFailedException e) {
        // an entry for this pseudonym exists: a pending sweep is replaced by this newer delete, anything else stays
      }
      final Map<String, AttributeValue> existing = dynamoDb.getItem(GetItemRequest.builder()
          .tableName(tableName).key(Map.of(KEY, s(keyOf(sub)))).consistentRead(true).build()).item();
      if (existing == null || existing.isEmpty()) {
        continue; // removed in the meantime: try the put again
      }
      if (!KIND_SWEEP.equals(existing.get(KIND).s()) || !existing.containsKey(PENDING)) {
        return true; // a pending delete, or a dead letter: already there
      }
      try {
        dynamoDb.updateItem(UpdateItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(KEY, s(keyOf(sub))))
            .updateExpression("SET " + ID + " = :id, " + KIND + " = :d, " + ACI + " = :aci, " + ATTEMPTS + " = :zero, "
                + NEXT_ATTEMPT + " = :due, " + ENQUEUED + " = :q, " + OUTCOME + " = :none, " + EXPIRES + " = :x "
                + "REMOVE " + LEASE)
            .conditionExpression(ID + " = :old AND " + KIND + " = :w AND attribute_exists(" + PENDING + ")")
            .expressionAttributeValues(Map.of(
                ":id", s(UUID.randomUUID().toString()), ":d", s(KIND_DELETE), ":aci", s(aci.toString()),
                ":zero", n(0), ":due", n(now.toEpochMilli()), ":q", n(now.toEpochMilli()), ":none", s("none"),
                ":x", n(expiresAtSeconds(now)), ":old", existing.get(ID), ":w", s(KIND_SWEEP)))
            .build());
        return true;
      } catch (final ConditionalCheckFailedException e) {
        // changed under us: look again
      }
    }
    throw new IllegalStateException("could not enqueue (the entry kept changing)");
  }

  @Override
  public boolean enqueueSweep(final UUID aci, final byte[] sub, final Instant dueAt, final Instant enqueuedAt) {
    try {
      dynamoDb.putItem(PutItemRequest.builder()
          .tableName(tableName)
          .item(newItem(Kind.SWEEP, aci, sub, dueAt, enqueuedAt))
          .conditionExpression("attribute_not_exists(" + KEY + ")")
          .build());
      return true;
    } catch (final ConditionalCheckFailedException e) {
      return false; // one entry per pseudonym
    }
  }

  @Override
  public boolean cancelSweep(final byte[] sub) {
    try {
      final DeleteItemResponse response = dynamoDb.deleteItem(DeleteItemRequest.builder()
          .tableName(tableName)
          .key(Map.of(KEY, s(keyOf(sub))))
          .conditionExpression(KIND + " = :w AND attribute_exists(" + PENDING + ")")
          .expressionAttributeValues(Map.of(":w", s(KIND_SWEEP)))
          .returnValues(ReturnValue.ALL_OLD)
          .build());
      return response.hasAttributes() && !response.attributes().isEmpty();
    } catch (final ConditionalCheckFailedException e) {
      return false; // none, or a delete / dead letter: not ours to cancel
    }
  }

  @Override
  public List<Entry> due(final Instant now, final int limit) {
    final long nowMs = now.toEpochMilli();
    final List<Entry> claimed = new ArrayList<>();
    Map<String, AttributeValue> startKey = null;
    do {
      final QueryResponse page = dynamoDb.query(QueryRequest.builder()
          .tableName(tableName)
          .indexName(PENDING_INDEX)
          .keyConditionExpression(PENDING + " = :p AND " + NEXT_ATTEMPT + " <= :now")
          .expressionAttributeValues(Map.of(":p", s("1"), ":now", n(nowMs)))
          .limit(limit)
          .exclusiveStartKey(startKey)
          .build());
      for (final Map<String, AttributeValue> candidate : page.items()) {
        if (claimed.size() >= limit) {
          break;
        }
        final Map<String, AttributeValue> claimedItem = claim(candidate.get(KEY).s(), nowMs);
        if (claimedItem != null) {
          claimed.add(toEntry(claimedItem));
        }
      }
      startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
    } while (startKey != null && claimed.size() < limit);
    claimed.sort(Comparator.comparing(Entry::enqueuedAt));
    return claimed;
  }

  /// Takes the lease on the *current* version of the item (read back with `ALL_NEW`), only if it is still pending, due
  /// and not leased by someone else. The index is eventually consistent; this write is the authority.
  private Map<String, AttributeValue> claim(final String key, final long nowMs) {
    try {
      return dynamoDb.updateItem(UpdateItemRequest.builder()
          .tableName(tableName)
          .key(Map.of(KEY, s(key)))
          .updateExpression("SET " + LEASE + " = :lease")
          .conditionExpression("attribute_exists(" + PENDING + ") AND " + NEXT_ATTEMPT + " <= :now AND ("
              + "attribute_not_exists(" + LEASE + ") OR " + LEASE + " <= :now)")
          .expressionAttributeValues(Map.of(":lease", n(nowMs + LEASE_DURATION.toMillis()), ":now", n(nowMs)))
          .returnValues(ReturnValue.ALL_NEW)
          .build()).attributes();
    } catch (final ConditionalCheckFailedException e) {
      return null;
    }
  }

  @Override
  public void complete(final Entry entry) {
    try {
      dynamoDb.deleteItem(DeleteItemRequest.builder()
          .tableName(tableName)
          .key(Map.of(KEY, s(keyOf(entry.sub()))))
          .conditionExpression(ID + " = :id")
          .expressionAttributeValues(Map.of(":id", s(entry.entryId().toString())))
          .build());
    } catch (final ConditionalCheckFailedException e) {
      // already cancelled or replaced
    }
  }

  @Override
  public void reschedule(final Entry entry, final int attempts, final Instant nextAttemptAt, final String lastOutcome) {
    try {
      dynamoDb.updateItem(UpdateItemRequest.builder()
          .tableName(tableName)
          .key(Map.of(KEY, s(keyOf(entry.sub()))))
          .updateExpression("SET " + ATTEMPTS + " = :t, " + NEXT_ATTEMPT + " = :n, " + OUTCOME + " = :o REMOVE " + LEASE)
          .conditionExpression(ID + " = :id AND attribute_exists(" + PENDING + ")")
          .expressionAttributeValues(Map.of(":t", n(attempts), ":n", n(nextAttemptAt.toEpochMilli()),
              ":o", s(lastOutcome), ":id", s(entry.entryId().toString())))
          .build());
    } catch (final ConditionalCheckFailedException e) {
      // cancelled or replaced meanwhile
    }
  }

  @Override
  public void deadLetter(final Entry entry, final int attempts, final String lastOutcome) {
    try {
      dynamoDb.updateItem(UpdateItemRequest.builder()
          .tableName(tableName)
          .key(Map.of(KEY, s(keyOf(entry.sub()))))
          .updateExpression("SET " + ATTEMPTS + " = :t, " + OUTCOME + " = :o REMOVE " + PENDING + ", " + LEASE)
          .conditionExpression(ID + " = :id AND attribute_exists(" + PENDING + ")")
          .expressionAttributeValues(Map.of(":t", n(attempts), ":o", s(lastOutcome),
              ":id", s(entry.entryId().toString())))
          .build());
    } catch (final ConditionalCheckFailedException e) {
      // cancelled or replaced meanwhile
    }
  }

  // ---- counts and operator tooling (scans of a table that only holds undelivered work) -----------------------------

  private void refreshCountsIfStale() {
    final Instant now = clock.instant();
    if (Duration.between(countsRefreshedAt, now).compareTo(COUNT_CACHE) < 0) {
      return;
    }
    try {
      cachedPending = countWhere("attribute_exists(" + PENDING + ")");
      cachedDead = countWhere("attribute_not_exists(" + PENDING + ")");
      countsRefreshedAt = now;
    } catch (final RuntimeException e) {
      // a gauge must not throw; keep the last value and try again on the next read
      log.warn("Tellomi engine deletion: could not count outbox entries ({})", e.getClass().getSimpleName());
    }
  }

  private int countWhere(final String filter) {
    int total = 0;
    Map<String, AttributeValue> startKey = null;
    do {
      final ScanResponse page = dynamoDb.scan(ScanRequest.builder()
          .tableName(tableName).select(Select.COUNT).filterExpression(filter).exclusiveStartKey(startKey).build());
      total += page.count();
      startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
    } while (startKey != null);
    return total;
  }

  @Override
  public int pendingCount() {
    refreshCountsIfStale();
    return cachedPending;
  }

  @Override
  public int deadLetterCount() {
    refreshCountsIfStale();
    return cachedDead;
  }

  private List<Map<String, AttributeValue>> scanDeadLetterItems(final int max) {
    final List<Map<String, AttributeValue>> items = new ArrayList<>();
    Map<String, AttributeValue> startKey = null;
    do {
      final ScanResponse page = dynamoDb.scan(ScanRequest.builder()
          .tableName(tableName).filterExpression("attribute_not_exists(" + PENDING + ")").consistentRead(true)
          .exclusiveStartKey(startKey).build());
      items.addAll(page.items());
      startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
    } while (startKey != null && items.size() < max);
    return items;
  }

  @Override
  public List<Entry> deadLetters() {
    return scanDeadLetterItems(MAX_DEAD_LETTERS_LISTED).stream()
        .map(DynamoDbEngineDeletionOutbox::toEntry)
        .sorted(Comparator.comparing(Entry::enqueuedAt))
        .limit(MAX_DEAD_LETTERS_LISTED)
        .toList();
  }

  @Override
  public int requeueDeadLetters(final Instant now) {
    int moved = 0;
    for (final Map<String, AttributeValue> item : scanDeadLetterItems(Integer.MAX_VALUE)) {
      try {
        dynamoDb.updateItem(UpdateItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(KEY, item.get(KEY)))
            .updateExpression("SET " + PENDING + " = :one, " + ATTEMPTS + " = :zero, " + NEXT_ATTEMPT + " = :now "
                + "REMOVE " + LEASE)
            .conditionExpression(ID + " = :id AND attribute_not_exists(" + PENDING + ")")
            .expressionAttributeValues(Map.of(":one", s("1"), ":zero", n(0), ":now", n(now.toEpochMilli()),
                ":id", item.get(ID)))
            .build());
        moved++;
      } catch (final ConditionalCheckFailedException e) {
        // changed meanwhile
      }
    }
    countsRefreshedAt = Instant.MIN;
    return moved;
  }

}
