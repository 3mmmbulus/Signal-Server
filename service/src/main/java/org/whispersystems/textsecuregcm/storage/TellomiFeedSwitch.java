/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

import io.micrometer.core.instrument.Metrics;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.TellomiFeedSwitchConfiguration;
import org.whispersystems.textsecuregcm.util.Util;

/// Tellomi（ADR-0071 §8.8、feed-mvp-plan F-14）：算某个账号的 `global.feed.enabled`，规则见 [TellomiFeedSwitchConfiguration]。
///
/// 只被 [RemoteConfigsManager] 调用，HTTP `/v2/config` 和 gRPC 都经过它，所以两条路的结果是同一个函数的结果。
/// 热路径上的成本：总闸关或不在名单里 = 一次内存集合查询；只有名单里的账号才会去读账号（走 AccountsManager 的缓存）。
public class TellomiFeedSwitch {

  public static final String CONFIG_KEY = "global.feed.enabled";

  private static final Logger log = LoggerFactory.getLogger(TellomiFeedSwitch.class);
  private static final String RESULT_COUNTER_NAME = name(TellomiFeedSwitch.class, "result");
  private static final String CN_COUNTRY_CODE = "86";

  private final boolean enabled;
  private final Set<String> regions;
  private final Set<UUID> betaAcis;
  private final Function<UUID, Optional<Account>> accountLookup;

  public TellomiFeedSwitch(final TellomiFeedSwitchConfiguration configuration,
      final Function<UUID, Optional<Account>> accountLookup) {
    this.enabled = configuration.enabled();
    this.regions = Set.copyOf(configuration.regions());
    this.betaAcis = new HashSet<>(configuration.betaAcis());
    this.accountLookup = accountLookup;
  }

  /// @return `"true"` / `"false"`；算不出来（读账号出错）时 empty，调用方**不下发**这个键（客户端当作关），其他配置不受影响
  public Optional<String> valueFor(final UUID accountIdentifier) {
    if (!enabled) {
      return result("false", "master_off");
    }
    if (!betaAcis.contains(accountIdentifier)) {
      return result("false", "not_in_beta");
    }
    if (regions.isEmpty()) {
      return result("false", "no_region");
    }
    final Optional<Account> account;
    try {
      account = accountLookup.apply(accountIdentifier);
    } catch (final RuntimeException e) {
      Metrics.counter(RESULT_COUNTER_NAME, "result", "error").increment();
      log.warn("Tellomi feed switch: could not read the account, global.feed.enabled is not sent", e);
      return Optional.empty();
    }
    if (account.isEmpty()) {
      return result("false", "no_account");
    }
    final String region = regionOf(account.get());
    return regions.contains(region) ? result("true", region) : result("false", "region_off");
  }

  /// +86 号码 → cn；其他、或没有号码 → global
  static String regionOf(final Account account) {
    return account.getNumber().map(Util::getCountryCode).filter(CN_COUNTRY_CODE::equals).isPresent()
        ? TellomiFeedSwitchConfiguration.REGION_CN
        : TellomiFeedSwitchConfiguration.REGION_GLOBAL;
  }

  private static Optional<String> result(final String value, final String reason) {
    Metrics.counter(RESULT_COUNTER_NAME, "result", reason).increment();
    return Optional.of(value);
  }
}
