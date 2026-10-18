/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/// Tellomi（ADR-0071 §8.8、feed-mvp-plan F-14）：按账号下发远程配置 `global.feed.enabled`（动态入口显不显示）。
///
/// 可选：**不配这一段 = 服务端不碰 `global.feed.enabled`**（和以前一样：不下发 = 客户端当作关；`remoteConfig.globalConfig`
/// 里手写的值照旧原样下发）。配了这一段，每个账号的 `GET /v2/config` 和 gRPC `GetConfiguration` 都会带上
/// `global.feed.enabled` = `"true"` / `"false"`，并且**覆盖** `globalConfig` 里的同名值。
///
/// 一个账号拿到 `"true"`，要同时满足：`enabled` 为真、账号的 ACI 在 `betaAcis` 里、账号的内容区域在 `regions` 里。
/// 三个条件缺一个就是 `"false"`；`regions` / `betaAcis` 不写 = 空 = 没有人。默认值全是关。
///
/// 区域在这里只按账号的**号码**算：+86 → `cn`，其他（或没有号码）→ `global`。远程配置的路径上没有请求的主机 / 来源 IP，
/// 所以 [TellomiFeedController] 里「取严」的另两项（`tellomi.cn` 入口、大陆 ASN）这里用不上；这是故意取的更窄的一面：
/// 开关只会比 feed auth 的区域判断更少放行，不会更多。
///
/// @param enabled 总闸，默认 `false`。配了这一段又想整体关掉：写 `enabled: false`，所有账号下次拉配置就拿到明确的 `"false"`
/// @param regions 开放的内容区域，只能是 `cn`、`global`
/// @param betaAcis 内测名单（账号 ACI）。**和内容引擎的 `beta_allowlist`（假名）是两份**，改一边要同步另一边
public record TellomiFeedSwitchConfiguration(boolean enabled,
                                             @Nullable List<String> regions,
                                             @Nullable List<UUID> betaAcis) {

  public static final String REGION_CN = "cn";
  public static final String REGION_GLOBAL = "global";
  private static final Set<String> KNOWN_REGIONS = Set.of(REGION_CN, REGION_GLOBAL);

  public TellomiFeedSwitchConfiguration {
    regions = regions == null ? List.of() : regions.stream().map(r -> r == null ? "" : r.trim().toLowerCase(Locale.ROOT)).distinct().toList();
    for (final String region : regions) {
      if (!KNOWN_REGIONS.contains(region)) {
        throw new IllegalArgumentException("tellomiFeedSwitch.regions: unknown region '" + region + "' (use cn or global)");
      }
    }
    if (betaAcis != null && betaAcis.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("tellomiFeedSwitch.betaAcis must not contain empty entries");
    }
    betaAcis = betaAcis == null ? List.of() : List.copyOf(betaAcis);
  }
}
