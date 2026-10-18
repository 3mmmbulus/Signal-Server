/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.whispersystems.textsecuregcm.auth.TellomiFeedTokens;
import org.whispersystems.textsecuregcm.configuration.TellomiEngineDeletionConfiguration;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtension;
import org.whispersystems.textsecuregcm.storage.TellomiAccountRestrictions;
import org.whispersystems.textsecuregcm.tellomi.DynamoDbEngineDeletionOutboxTest.TestClock;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionClient.Outcome;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionClient.Result;
import org.whispersystems.textsecuregcm.util.AttributeValues;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/// How the engine deletion notice (durable outbox + live-account check) behaves around ADR-0078's «suspended» account
/// (Server #27). The restriction list is the real [TellomiAccountRestrictions] (its scan is stubbed); the registration
/// step is simulated by the one rule that matters here: the same number cannot register while `numberBlock` holds
/// (RegistrationController answers 422), otherwise the account is live again and the recycled-ACI hook fires.
/// Nothing in the #1368 code is changed or imported beyond its public read API.
class TellomiEngineDeletionSuspendedAccountTest {

  @RegisterExtension
  static final DynamoDbExtension EXTENSION = DynamoDbEngineDeletionOutboxTest.newExtension();

  private static final TellomiFeedTokens TOKENS = new TellomiFeedTokens(
      TellomiEngineDeletionTestSupport.bytes(32, (byte) 's'), TellomiEngineDeletionTestSupport.bytes(32, (byte) 'p'));
  private static final byte[] NUMBER_KEY = TellomiEngineDeletionTestSupport.bytes(32, (byte) 'n');
  private static final String NUMBER = "+8613800000009";
  private static final Duration SECOND_DELETE = Duration.ofMinutes(70);

  private final TestClock clock = new TestClock();
  private final Set<UUID> liveAccounts = new HashSet<>();
  private final List<byte[]> sent = new ArrayList<>();
  private final DynamoDbClient restrictionScan = mock(DynamoDbClient.class);
  private final TellomiAccountRestrictions restrictions =
      new TellomiAccountRestrictions(restrictionScan, "restrictions", NUMBER_KEY, clock);
  private final DynamoDbEngineDeletionOutbox outbox = new DynamoDbEngineDeletionOutbox(EXTENSION.getDynamoDbClient(),
      DynamoDbEngineDeletionOutboxTest.TABLE, clock, DynamoDbEngineDeletionOutboxTest.TTL);
  private final TellomiEngineAccountDeletionNotifier notifier = new TellomiEngineAccountDeletionNotifier(
      TellomiEngineDeletionTestSupport.enabled("https://engine.example.invalid", Duration.ofSeconds(2), 4, true,
          SECOND_DELETE),
      TOKENS::pseudonym, outbox, sub -> {
        sent.add(sub);
        return new Result(Outcome.DELETED, 200, Optional.empty());
      }, liveAccounts::contains, clock, () -> 0.0, null);

  private void suspend(final UUID aci) {
    final Instant until = clock.instant().plus(Duration.ofDays(30));
    when(restrictionScan.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(List.of(
        Map.of(TellomiAccountRestrictions.KEY_ACCOUNT, AttributeValues.fromUUID(aci),
            TellomiAccountRestrictions.KEY_SORT, AttributeValues.fromString(TellomiAccountRestrictions.CURRENT),
            TellomiAccountRestrictions.ATTR_LEVEL, AttributeValues.fromString("suspended"),
            TellomiAccountRestrictions.ATTR_UNTIL, AttributeValues.fromLong(until.getEpochSecond())),
        Map.of(TellomiAccountRestrictions.KEY_ACCOUNT, AttributeValues.fromUUID(aci),
            TellomiAccountRestrictions.KEY_SORT, AttributeValues.fromString(TellomiAccountRestrictions.NUMBER),
            TellomiAccountRestrictions.ATTR_NUMBER_HASH,
            AttributeValue.builder().b(SdkBytes.fromByteArray(
                TellomiAccountRestrictions.numberHash(NUMBER_KEY, NUMBER))).build(),
            TellomiAccountRestrictions.ATTR_UNTIL, AttributeValues.fromLong(until.getEpochSecond())))).build());
    restrictions.refresh();
  }

  private void lift() {
    when(restrictionScan.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(List.of()).build());
    restrictions.refresh();
  }

  /// the registration rule of RegistrationController, as far as the deletion path can see it
  private boolean tryToRegisterAgain(final UUID aci) {
    if (restrictions.numberBlock(NUMBER).isPresent()) {
      return false;                                   // 422 + tellomiRestriction
    }
    liveAccounts.add(aci);                            // AccountsManager.create reuses the ACI of a recently deleted account
    notifier.onAccountCreatedWithRecycledAci(aci);
    return true;
  }

  @Test
  void deletingASuspendedAccountIsQueuedAndSentLikeAnyOther() {
    final UUID aci = UUID.randomUUID();
    suspend(aci);
    assertThat(restrictions.isSuspended(aci)).isTrue();

    notifier.onAccountDeleted(aci);
    notifier.drain();

    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst()).isEqualTo(TOKENS.pseudonym(aci));
    assertThat(outbox.pendingCount()).as("the second delete is queued").isEqualTo(1);
  }

  @Test
  void aSuspendedNumberCannotComeBack_soTheSecondDeleteIsStillSent() {
    final UUID aci = UUID.randomUUID();
    suspend(aci);
    notifier.onAccountDeleted(aci);
    notifier.drain();

    assertThat(tryToRegisterAgain(aci)).as("blocked by the number hash").isFalse();

    clock.advance(SECOND_DELETE.plusMinutes(1));
    notifier.drain();
    assertThat(sent).as("first + second delete").hasSize(2);
    assertThat(outbox.pendingCount()).isZero();
  }

  @Test
  void control_onceTheSuspensionIsLiftedAndTheNumberRegistersAgain_theSecondDeleteIsCancelled() {
    final UUID aci = UUID.randomUUID();
    suspend(aci);
    notifier.onAccountDeleted(aci);
    notifier.drain();

    lift();
    assertThat(tryToRegisterAgain(aci)).isTrue();

    clock.advance(SECOND_DELETE.plusMinutes(1));
    notifier.drain();
    assertThat(sent).as("only the first delete: the new account's data is not erased").hasSize(1);
  }

  @Test
  void aNoticeThatWaitedThroughAnOutageForASuspendedNumberIsStillSent() {
    final UUID aci = UUID.randomUUID();
    suspend(aci);
    notifier.onAccountDeleted(aci);                   // nothing sent yet (outage), then 3 hours pass

    clock.advance(Duration.ofHours(3));
    restrictions.refresh();                           // still blocked: 30 days
    assertThat(tryToRegisterAgain(aci)).isFalse();
    notifier.drain();

    assertThat(sent).as("late send: the live check says «gone», so it goes out").hasSize(1);
  }
}
