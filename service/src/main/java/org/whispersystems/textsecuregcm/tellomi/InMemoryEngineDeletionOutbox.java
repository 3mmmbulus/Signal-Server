/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Bounded, process-local {@link EngineDeletionOutbox}. **Not durable**: a restart loses everything in it.
public class InMemoryEngineDeletionOutbox implements EngineDeletionOutbox {

  /// the dead-letter list is bounded too; the oldest entry is dropped first
  static final int MAX_DEAD_LETTERS = 1_000;

  private final int maxPending;
  // keyed by the pseudonym so a second deletion notice for the same person does not queue a second request
  private final Map<String, Entry> pending = new LinkedHashMap<>();
  private final Map<String, Entry> dead = new LinkedHashMap<>();

  public InMemoryEngineDeletionOutbox(final int maxPending) {
    this.maxPending = maxPending;
  }

  private static String key(final byte[] sub) {
    return Base64.getEncoder().encodeToString(sub);
  }

  @Override
  public synchronized boolean enqueue(final byte[] sub, final Instant now) {
    final String k = key(sub);
    if (pending.containsKey(k) || dead.containsKey(k)) {
      return true;
    }
    if (pending.size() >= maxPending) {
      return false;
    }
    pending.put(k, new Entry(UUID.randomUUID(), sub.clone(), 0, now, now, "none"));
    return true;
  }

  @Override
  public synchronized List<Entry> due(final Instant now, final int limit) {
    return pending.values().stream()
        .filter(e -> !e.nextAttemptAt().isAfter(now))
        .sorted(Comparator.comparing(Entry::enqueuedAt))
        .limit(limit)
        .toList();
  }

  @Override
  public synchronized void complete(final UUID entryId) {
    pending.values().removeIf(e -> e.entryId().equals(entryId));
  }

  @Override
  public synchronized void reschedule(final UUID entryId, final int attempts, final Instant nextAttemptAt,
      final String lastOutcome) {
    for (final Map.Entry<String, Entry> me : pending.entrySet()) {
      final Entry e = me.getValue();
      if (e.entryId().equals(entryId)) {
        me.setValue(new Entry(e.entryId(), e.sub(), attempts, nextAttemptAt, e.enqueuedAt(), lastOutcome));
        return;
      }
    }
  }

  @Override
  public synchronized void deadLetter(final UUID entryId, final int attempts, final String lastOutcome) {
    for (final Map.Entry<String, Entry> me : new ArrayList<>(pending.entrySet())) {
      final Entry e = me.getValue();
      if (e.entryId().equals(entryId)) {
        pending.remove(me.getKey());
        if (dead.size() >= MAX_DEAD_LETTERS) {
          dead.remove(dead.keySet().iterator().next());
        }
        dead.put(me.getKey(), new Entry(e.entryId(), e.sub(), attempts, e.nextAttemptAt(), e.enqueuedAt(), lastOutcome));
        return;
      }
    }
  }

  @Override
  public synchronized int pendingCount() {
    return pending.size();
  }

  @Override
  public synchronized int deadLetterCount() {
    return dead.size();
  }

  @Override
  public synchronized List<Entry> deadLetters() {
    return List.copyOf(dead.values());
  }

  @Override
  public synchronized int requeueDeadLetters(final Instant now) {
    int moved = 0;
    for (final Map.Entry<String, Entry> me : new ArrayList<>(dead.entrySet())) {
      if (pending.size() >= maxPending) {
        break;
      }
      final Entry e = me.getValue();
      dead.remove(me.getKey());
      pending.put(me.getKey(), new Entry(e.entryId(), e.sub(), 0, now, e.enqueuedAt(), e.lastOutcome()));
      moved++;
    }
    return moved;
  }
}
