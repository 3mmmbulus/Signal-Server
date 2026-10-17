/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.whispersystems.textsecuregcm.auth.TellomiFeedTokens;
import org.whispersystems.textsecuregcm.configuration.TellomiEngineDeletionConfiguration;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtension;
import org.whispersystems.textsecuregcm.tellomi.DynamoDbEngineDeletionOutboxTest.TestClock;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionClient.Outcome;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionClient.Result;

/// The notifier on the durable outbox, with "nodes" being separate notifier + outbox objects over one DynamoDB table:
/// a restart or a second node loses nothing and the late-send rules hold across them.
class TellomiEngineDurableDeletionTest {

  @RegisterExtension
  static final DynamoDbExtension EXTENSION = DynamoDbEngineDeletionOutboxTest.newExtension();

  private static final TellomiFeedTokens TOKENS = new TellomiFeedTokens(
      TellomiEngineDeletionTestSupport.bytes(32, (byte) 's'), TellomiEngineDeletionTestSupport.bytes(32, (byte) 'p'));
  private static final Duration SECOND_DELETE = Duration.ofMinutes(70);

  private final TestClock clock = new TestClock();
  /// ACIs with a live account (shared by all "nodes": it is the account store)
  private final Set<UUID> liveAccounts = new HashSet<>();

  private final TellomiEngineDeletionConfiguration config = TellomiEngineDeletionTestSupport.enabled(
      "https://engine.example.invalid", Duration.ofSeconds(2), 4, true, SECOND_DELETE);

  /// one process: its own outbox object, notifier, and what its sender saw
  private final class Node {
    final List<byte[]> sent = new ArrayList<>();
    final List<Result> script = new ArrayList<>();
    final EngineDeletionOutbox outbox;
    final TellomiEngineAccountDeletionNotifier notifier;

    Node(final Function<Node, EngineDeletionOutbox> outboxFactory) {
      this.outbox = outboxFactory.apply(this);
      this.notifier = new TellomiEngineAccountDeletionNotifier(config, TOKENS::pseudonym, outbox, sub -> {
        sent.add(sub);
        return script.isEmpty() ? new Result(Outcome.DELETED, 200, Optional.empty()) : script.removeFirst();
      }, liveAccounts::contains, clock, () -> 0.0, null);
    }
  }

  private Node durableNode() {
    return new Node(n -> new DynamoDbEngineDeletionOutbox(EXTENSION.getDynamoDbClient(),
        DynamoDbEngineDeletionOutboxTest.TABLE, clock, DynamoDbEngineDeletionOutboxTest.TTL));
  }

  @Test
  void aNoticeQueuedBeforeARestartIsSentAfterIt() {
    final UUID aci = UUID.randomUUID();
    final Node before = durableNode();
    before.notifier.onAccountDeleted(aci);
    // the process dies here: nothing was sent

    final Node after = durableNode();
    after.notifier.drain();

    assertThat(before.sent).isEmpty();
    assertThat(after.sent).hasSize(1);
    assertThat(after.sent.getFirst()).isEqualTo(TOKENS.pseudonym(aci));
    assertThat(after.outbox.pendingCount()).as("the second delete is queued").isEqualTo(1);
  }

  @Test
  void theSameScenarioOnTheInMemoryOutboxLosesTheNotice_controlThatTheTestDiscriminates() {
    final UUID aci = UUID.randomUUID();
    final Node before = new Node(n -> new InMemoryEngineDeletionOutbox(10));
    before.notifier.onAccountDeleted(aci);

    final Node after = new Node(n -> new InMemoryEngineDeletionOutbox(10));
    after.notifier.drain();

    assertThat(after.sent).as("lost: this is what the durable outbox fixes").isEmpty();
  }

  @Test
  void aFailedAttemptAndItsBackoffSurviveARestart() {
    final UUID aci = UUID.randomUUID();
    final Node before = durableNode();
    before.script.add(new Result(Outcome.RETRY, 503, Optional.empty()));
    before.notifier.onAccountDeleted(aci);
    before.notifier.drain();                    // engine down: attempt 1 failed, retry in 5 s
    assertThat(before.sent).hasSize(1);

    final Node after = durableNode();           // restart
    after.notifier.drain();
    assertThat(after.sent).as("still backing off").isEmpty();
    clock.advance(Duration.ofMinutes(1));
    after.notifier.drain();
    assertThat(after.sent).as("the retry goes out after the restart").hasSize(1);
  }

  @Test
  void aWorkerThatDiedMidSendIsReplacedAfterItsLease() {
    final UUID aci = UUID.randomUUID();
    final Node dying = durableNode();
    dying.notifier.onAccountDeleted(aci);
    assertThat(dying.outbox.due(clock.instant(), 10)).hasSize(1);      // claimed, then the process dies

    final Node other = durableNode();
    other.notifier.drain();
    assertThat(other.sent).as("leased by the dead worker").isEmpty();
    clock.advance(DynamoDbEngineDeletionOutbox.LEASE_DURATION.plusSeconds(1));
    other.notifier.drain();
    assertThat(other.sent).hasSize(1);
  }

  @Test
  void aNoticeThatWaitedThroughALongOutageIsDroppedIfTheAciWasReRegistered() {
    final UUID aci = UUID.randomUUID();
    final Node before = durableNode();
    before.notifier.onAccountDeleted(aci);      // process dies, nothing sent

    liveAccounts.add(aci);                      // same number, same ACI, registered again while we were down
    clock.advance(Duration.ofHours(3));

    final Node after = durableNode();
    after.notifier.drain();

    assertThat(after.sent).as("sending it would erase the new account's data").isEmpty();
    assertThat(after.outbox.pendingCount()).isZero();
    assertThat(after.outbox.deadLetterCount()).isZero();
  }

  @Test
  void aNoticeForAnAciThatStaysGoneIsStillSentAfterALongOutage() {
    final UUID aci = UUID.randomUUID();
    durableNode().notifier.onAccountDeleted(aci);
    clock.advance(Duration.ofHours(3));

    final Node after = durableNode();
    after.notifier.drain();

    assertThat(after.sent).hasSize(1);
  }

  @Test
  void aReRegistrationHandledByAnotherNodeCancelsTheSecondDelete() {
    final UUID aci = UUID.randomUUID();
    final Node deletionNode = durableNode();
    deletionNode.notifier.onAccountDeleted(aci);
    deletionNode.notifier.drain();
    assertThat(deletionNode.sent).hasSize(1);

    final Node registrationNode = durableNode();
    registrationNode.notifier.onAccountCreatedWithRecycledAci(aci);

    clock.advance(SECOND_DELETE.plusMinutes(1));
    deletionNode.notifier.drain();
    assertThat(deletionNode.sent).as("in memory this cancel only worked on the node that deleted").hasSize(1);
  }

  @Test
  void theSecondDeleteSurvivesARestartAndIsSent() {
    final UUID aci = UUID.randomUUID();
    final Node before = durableNode();
    before.notifier.onAccountDeleted(aci);
    before.notifier.drain();

    clock.advance(SECOND_DELETE);
    final Node after = durableNode();
    after.notifier.drain();

    assertThat(after.sent).hasSize(1);
    assertThat(after.outbox.pendingCount()).isZero();
  }

  @Test
  void twoNodesDrainingTogetherSendEachNoticeOnce() {
    final Node a = durableNode();
    final Node b = durableNode();
    for (int i = 0; i < 6; i++) {
      (i % 2 == 0 ? a : b).notifier.onAccountDeleted(UUID.randomUUID());
    }
    a.notifier.drain();
    b.notifier.drain();
    assertThat(a.sent.size() + b.sent.size()).isEqualTo(6);
  }
}
