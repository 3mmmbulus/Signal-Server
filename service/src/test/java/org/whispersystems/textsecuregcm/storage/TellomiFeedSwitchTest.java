/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.whispersystems.textsecuregcm.configuration.TellomiFeedSwitchConfiguration;

/** Tellomi（F-14）：`global.feed.enabled` 按账号算——默认关；名单 + 区域都满足才开。 */
public class TellomiFeedSwitchTest {

  public static final String CN_NUMBER = "+8613812345678";
  public static final String US_NUMBER = "+12025550123";

  private final UUID beta = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private Function<UUID, Optional<Account>> lookup;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    lookup = mock(Function.class);
  }

  public static Account accountWithNumber(final String number) {
    final Account account = mock(Account.class);
    when(account.getNumber()).thenReturn(Optional.ofNullable(number));
    return account;
  }

  public static void givenAccount(final Function<UUID, Optional<Account>> lookup, final UUID aci, final String number) {
    final Account account = accountWithNumber(number);
    when(lookup.apply(aci)).thenReturn(Optional.of(account));
  }

  private TellomiFeedSwitch switchOf(final boolean enabled, final List<String> regions, final List<UUID> betaAcis) {
    return new TellomiFeedSwitch(new TellomiFeedSwitchConfiguration(enabled, regions, betaAcis), lookup);
  }

  @Test
  void defaultConfigurationIsOffForEveryone() {
    final TellomiFeedSwitch feedSwitch = new TellomiFeedSwitch(new TellomiFeedSwitchConfiguration(false, null, null), lookup);

    assertEquals(Optional.of("false"), feedSwitch.valueFor(beta));
    assertEquals(Optional.of("false"), feedSwitch.valueFor(other));
    verify(lookup, never()).apply(any());
  }

  @Test
  void masterOffIsOffEvenForABetaAccountInAnOpenRegion() {
    givenAccount(lookup, beta, CN_NUMBER);

    assertEquals(Optional.of("false"), switchOf(false, List.of("cn", "global"), List.of(beta)).valueFor(beta));
  }

  @Test
  void onForABetaAccountWhoseRegionIsOpen() {
    givenAccount(lookup, beta, CN_NUMBER);

    assertEquals(Optional.of("true"), switchOf(true, List.of("cn"), List.of(beta)).valueFor(beta));
  }

  @Test
  void onForAGlobalNumberWhenGlobalIsOpen() {
    givenAccount(lookup, beta, US_NUMBER);

    assertEquals(Optional.of("true"), switchOf(true, List.of("global"), List.of(beta)).valueFor(beta));
  }

  @Test
  void offWhenTheAccountIsNotOnTheBetaListAndTheAccountIsNotEvenRead() {
    givenAccount(lookup, other, CN_NUMBER);

    assertEquals(Optional.of("false"), switchOf(true, List.of("cn", "global"), List.of(beta)).valueFor(other));
    verify(lookup, never()).apply(any());
  }

  @Test
  void offWhenTheRegionIsNotOpen() {
    givenAccount(lookup, beta, CN_NUMBER);

    assertEquals(Optional.of("false"), switchOf(true, List.of("global"), List.of(beta)).valueFor(beta),
        "a +86 beta account while only global is open");

    givenAccount(lookup, beta, US_NUMBER);

    assertEquals(Optional.of("false"), switchOf(true, List.of("cn"), List.of(beta)).valueFor(beta),
        "a global beta account while only cn is open");
  }

  @Test
  void offWhenNoRegionIsOpenOrTheBetaListIsEmpty() {
    givenAccount(lookup, beta, CN_NUMBER);

    assertEquals(Optional.of("false"), switchOf(true, List.of(), List.of(beta)).valueFor(beta));
    assertEquals(Optional.of("false"), switchOf(true, null, List.of(beta)).valueFor(beta));
    assertEquals(Optional.of("false"), switchOf(true, List.of("cn", "global"), List.of()).valueFor(beta));
    assertEquals(Optional.of("false"), switchOf(true, List.of("cn", "global"), null).valueFor(beta));
  }

  @Test
  void anAccountWithoutANumberCountsAsGlobal() {
    givenAccount(lookup, beta, null);

    assertEquals(Optional.of("true"), switchOf(true, List.of("global"), List.of(beta)).valueFor(beta));
    assertEquals(Optional.of("false"), switchOf(true, List.of("cn"), List.of(beta)).valueFor(beta));
  }

  @Test
  void offWhenTheAccountIsGone() {
    when(lookup.apply(beta)).thenReturn(Optional.empty());

    assertEquals(Optional.of("false"), switchOf(true, List.of("cn", "global"), List.of(beta)).valueFor(beta));
  }

  @Test
  void aFailedAccountReadMeansNoAnswerNotTrue() {
    when(lookup.apply(beta)).thenThrow(new RuntimeException("dynamodb down"));

    assertTrue(switchOf(true, List.of("cn", "global"), List.of(beta)).valueFor(beta).isEmpty());
  }
}
