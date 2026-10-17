/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/// Pending "tell the content engine this account is gone" work. An entry holds the engine pseudonym (`sub`) **and the
/// ACI** of the deleted account. The ACI is there for one reason: before a *late* send the notifier must check that the
/// ACI has not been re-registered (same ACI = same pseudonym, the engine cannot tell the two accounts apart; design doc
/// §6.3 / §13.5), and nothing can be looked up by pseudonym. It is bounded by the entry's life: removed when the entry
/// completes or is cancelled, and by the durable store's TTL otherwise; never logged. `entryId` is a random id used
/// only to correlate log lines and metrics.
///
/// Implementations: {@link InMemoryEngineDeletionOutbox} (process-local, lost on restart) and
/// {@link DynamoDbEngineDeletionOutbox} (durable, shared by all nodes; used when
/// `tellomiEngineDeletion.outboxTableName` is set). Both keep at most one entry per pseudonym.
public interface EngineDeletionOutbox {

  /// `DELETE`: the deletion that follows the account deletion. `SWEEP`: the second delete scheduled after it, to catch
  /// what a feed credential that was still valid (up to 1 h) could create after the first delete.
  enum Kind {
    DELETE, SWEEP
  }

  /// A unit of work. Immutable; the outbox returns a fresh instance after every change. `enqueuedAt` is when the
  /// account was deleted (a sweep keeps the time of the delete it follows).
  record Entry(UUID entryId, Kind kind, byte[] sub, UUID aci, int attempts, Instant nextAttemptAt, Instant enqueuedAt,
               String lastOutcome) {
    /// no `sub` and no ACI in text form, ever (it ends up in logs and assertion messages)
    @Override
    public String toString() {
      return "Entry[entryId=" + entryId + ", kind=" + kind + ", attempts=" + attempts + ", nextAttemptAt="
          + nextAttemptAt + ", lastOutcome=" + lastOutcome + "]";
    }
  }

  /// Adds a `DELETE` for the pseudonym unless one is already pending (or dead-lettered). A pending `SWEEP` for the
  /// same pseudonym is replaced by the new `DELETE` (a newer deletion supersedes the old schedule). Returns `false`
  /// if the entry was not added because the outbox is full; `true` otherwise (added, or already there).
  boolean enqueue(UUID aci, byte[] sub, Instant now);

  /// Adds a `SWEEP` due at `dueAt`, unless the pseudonym already has any entry (pending or dead-lettered). At most
  /// one entry per pseudonym, so a sweep never multiplies. Returns `true` when it was added.
  boolean enqueueSweep(UUID aci, byte[] sub, Instant dueAt, Instant enqueuedAt);

  /// Removes a pending `SWEEP` for the pseudonym (the ACI was recycled by a new account, whose data the sweep would
  /// wrongly erase). Does nothing to a pending `DELETE`. Returns `true` if one was removed.
  boolean cancelSweep(byte[] sub);

  /// Entries whose `nextAttemptAt` is not after `now`, oldest first, at most `limit`. A shared (durable) outbox also
  /// **claims** them for a lease, so that two nodes do not send the same entry at the same time; the claim ends with
  /// {@link #complete}, {@link #reschedule} or {@link #deadLetter}, or when the lease runs out (crashed worker).
  List<Entry> due(Instant now, int limit);

  /// The work is done (the engine answered success, or the entry is no longer needed): forget the entry. Acts only on
  /// this very entry (`entryId`): an entry that was replaced or cancelled in the meantime is left alone.
  void complete(Entry entry);

  /// Record a failed attempt and when to try again (only if `entry` is still the pending entry).
  void reschedule(Entry entry, int attempts, Instant nextAttemptAt, String lastOutcome);

  /// Give up automatically (attempts used up, or a permanent answer). The entry stays visible for an operator.
  void deadLetter(Entry entry, int attempts, String lastOutcome);

  int pendingCount();

  int deadLetterCount();

  /// Dead-lettered entries, oldest first. Operator tooling; entries carry no ACI.
  List<Entry> deadLetters();

  /// Move every dead-lettered entry back to pending, due immediately, attempts reset to 0 (after the operator fixed
  /// the cause, e.g. a rotated service key). Returns how many were moved.
  int requeueDeadLetters(Instant now);
}
