/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/// Pending "tell the content engine this account is gone" work. An entry holds the engine pseudonym (`sub`), **not**
/// the ACI, so that nothing which identifies the Signal account outlives the account's deletion. `entryId` is a random
/// id used only to correlate log lines and metrics.
///
/// The only implementation in this change is {@link InMemoryEngineDeletionOutbox}: it does **not** survive a restart.
/// A durable implementation (DynamoDB table or similar) must replace it before `tellomiEngineDeletion.enabled` is
/// turned on in production; see ACCOUNT-DELETION-ENGINE-DESIGN.md §3.
public interface EngineDeletionOutbox {

  /// A unit of work. Immutable; the outbox returns a fresh instance after every change.
  record Entry(UUID entryId, byte[] sub, int attempts, Instant nextAttemptAt, Instant enqueuedAt,
               String lastOutcome) {
  }

  /// Adds the pseudonym unless it is already pending (or already dead-lettered). Returns `false` if the entry was
  /// not added because the outbox is full; `true` otherwise (added, or already there).
  boolean enqueue(byte[] sub, Instant now);

  /// Entries whose `nextAttemptAt` is not after `now`, oldest first, at most `limit`.
  List<Entry> due(Instant now, int limit);

  /// The work is done (the engine answered success): forget the entry.
  void complete(UUID entryId);

  /// Record a failed attempt and when to try again.
  void reschedule(UUID entryId, int attempts, Instant nextAttemptAt, String lastOutcome);

  /// Give up automatically (attempts used up, or a permanent answer). The entry stays visible for an operator.
  void deadLetter(UUID entryId, int attempts, String lastOutcome);

  int pendingCount();

  int deadLetterCount();

  /// Dead-lettered entries, oldest first. Operator tooling; entries carry no ACI.
  List<Entry> deadLetters();

  /// Move every dead-lettered entry back to pending, due immediately, attempts reset to 0 (after the operator fixed
  /// the cause, e.g. a rotated service key). Returns how many were moved.
  int requeueDeadLetters(Instant now);
}
