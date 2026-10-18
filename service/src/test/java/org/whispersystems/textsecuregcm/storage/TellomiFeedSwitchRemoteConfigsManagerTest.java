/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.whispersystems.textsecuregcm.storage.TellomiFeedSwitchTest.CN_NUMBER;
import static org.whispersystems.textsecuregcm.storage.TellomiFeedSwitchTest.US_NUMBER;
import static org.whispersystems.textsecuregcm.storage.TellomiFeedSwitchTest.givenAccount;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.whispersystems.textsecuregcm.configuration.TellomiFeedSwitchConfiguration;

/**
 * Tellomi（F-14）：{@link RemoteConfigsManager} 里 `global.feed.enabled` 的行为。
 * 现有的 {@link RemoteConfigsManagerTest} 不动（它用的是不带开关的构造器，那条路径必须和以前完全一样）。
 */
class TellomiFeedSwitchRemoteConfigsManagerTest {

  private static final Map<String, String> GLOBAL = Map.of("maxGroupSize", "42");
  private static final List<RemoteConfig> CLIENT = List.of(
      new RemoteConfig("always.true", 100, Set.of(), null, null, null));

  private final UUID beta = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private RemoteConfigs remoteConfigs;
  private Function<UUID, Optional<Account>> lookup;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    remoteConfigs = mock(RemoteConfigs.class);
    when(remoteConfigs.getAll()).thenReturn(CLIENT);
    lookup = mock(Function.class);
    givenAccount(lookup, beta, CN_NUMBER);
    givenAccount(lookup, other, US_NUMBER);
  }

  private RemoteConfigsManager manager(final Map<String, String> global, final boolean enabled,
      final List<String> regions, final List<UUID> betaAcis) {
    return new RemoteConfigsManager(remoteConfigs, global, new TellomiFeedSwitch(
        new TellomiFeedSwitchConfiguration(enabled, regions, betaAcis), lookup));
  }

  @Test
  void withoutTheSwitchTheKeyIsNeverAddedAndAHandWrittenGlobalValueIsUntouched() {
    final RemoteConfigsManager none = new RemoteConfigsManager(remoteConfigs, GLOBAL);
    assertThat(none.getConfigForAccount(beta, null)).doesNotContainKey("global.feed.enabled");

    final RemoteConfigsManager handWritten = new RemoteConfigsManager(remoteConfigs,
        Map.of("maxGroupSize", "42", "feed.enabled", "true"), null);
    assertThat(handWritten.getConfigForAccount(beta, null)).contains(entry("global.feed.enabled", "true"));
  }

  @Test
  void defaultSwitchConfigurationSendsAnExplicitFalseAndChangesNothingElse() {
    final Map<String, String> configs = manager(GLOBAL, false, null, null).getConfigForAccount(beta, null);

    assertThat(configs).containsOnly(
        entry("always.true", "true"),
        entry("global.maxGroupSize", "42"),
        entry("global.feed.enabled", "false"));
  }

  @Test
  void onForTheBetaAccountOffForEveryoneElse() {
    final RemoteConfigsManager manager = manager(GLOBAL, true, List.of("cn"), List.of(beta));

    assertThat(manager.getConfigForAccount(beta, null)).contains(entry("global.feed.enabled", "true"));
    assertThat(manager.getConfigForAccount(other, null)).contains(entry("global.feed.enabled", "false"));
    assertThat(manager.getConfigForAccount(UUID.randomUUID(), null)).contains(entry("global.feed.enabled", "false"));
  }

  @Test
  void theComputedValueWinsOverAHandWrittenGlobalValueWithoutBlowingUpOnTheDuplicateKey() {
    final Map<String, String> global = new HashMap<>(GLOBAL);
    global.put("feed.enabled", "true");

    assertThat(manager(global, true, List.of("cn"), List.of(beta)).getConfigForAccount(other, null))
        .contains(entry("global.feed.enabled", "false"));
    assertThat(manager(global, false, null, null).getConfigForAccount(beta, null))
        .contains(entry("global.feed.enabled", "false"));
  }

  @Test
  void aFailedAccountReadDropsOnlyTheFeedKey() {
    when(lookup.apply(beta)).thenThrow(new RuntimeException("dynamodb down"));
    final Map<String, String> global = new HashMap<>(GLOBAL);
    global.put("feed.enabled", "true");

    final Map<String, String> configs = manager(global, true, List.of("cn"), List.of(beta)).getConfigForAccount(beta, null);

    assertThat(configs).doesNotContainKey("global.feed.enabled");
    assertThat(configs).contains(entry("always.true", "true"), entry("global.maxGroupSize", "42"));
  }
}
