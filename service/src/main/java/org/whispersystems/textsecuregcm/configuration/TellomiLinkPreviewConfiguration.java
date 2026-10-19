/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import jakarta.annotation.Nullable;
import java.time.Duration;

/// Tellomi（ADR-0063 §6.5，owner 2026-10-19）：服务端代抓链接卡片。**默认关**：不配这一段、或 `enabled: false`，
/// `POST /v1/tellomi/link-preview` 根本不注册（客户端拿到 404，照旧走本机抓取 / 品牌壳）。
///
/// 白名单（公众号、知乎、微博、小红书、抖音、淘宝 / 天猫商品页）编译在 [org.whispersystems.textsecuregcm.tellomi.linkpreview.LinkPreviewProviders]
/// 里，**不在配置里**：加一个域名要 owner / 产品经理批、改代码、过评审，运维改配置加不了。
///
/// @param enabled              总开关，默认 false
/// @param cacheTtl             成功结果的缓存时长（键只是规范化 URL 的 SHA-256，不含账号），默认 10 分钟，上限 1 小时
/// @param negativeCacheTtl     失败结果的缓存时长，默认 2 分钟，上限 10 分钟
/// @param cacheMaxEntries      两个缓存各自最多几条，默认 2000
/// @param maxConcurrentFetches 全服务器同时在抓的链接数上限（超了回 503），默认 8
public record TellomiLinkPreviewConfiguration(boolean enabled,
                                              @Nullable Duration cacheTtl,
                                              @Nullable Duration negativeCacheTtl,
                                              @Nullable Integer cacheMaxEntries,
                                              @Nullable Integer maxConcurrentFetches) {

  public static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
  public static final Duration MAX_CACHE_TTL = Duration.ofHours(1);
  public static final Duration DEFAULT_NEGATIVE_CACHE_TTL = Duration.ofMinutes(2);
  public static final Duration MAX_NEGATIVE_CACHE_TTL = Duration.ofMinutes(10);
  public static final int DEFAULT_CACHE_MAX_ENTRIES = 2000;
  public static final int DEFAULT_MAX_CONCURRENT_FETCHES = 8;

  public TellomiLinkPreviewConfiguration {
    if (cacheTtl != null && (cacheTtl.isNegative() || cacheTtl.compareTo(MAX_CACHE_TTL) > 0)) {
      throw new IllegalArgumentException("tellomiLinkPreview.cacheTtl must be between 0 and " + MAX_CACHE_TTL);
    }
    if (negativeCacheTtl != null
        && (negativeCacheTtl.isNegative() || negativeCacheTtl.compareTo(MAX_NEGATIVE_CACHE_TTL) > 0)) {
      throw new IllegalArgumentException(
          "tellomiLinkPreview.negativeCacheTtl must be between 0 and " + MAX_NEGATIVE_CACHE_TTL);
    }
    if (cacheMaxEntries != null && (cacheMaxEntries < 0 || cacheMaxEntries > 100_000)) {
      throw new IllegalArgumentException("tellomiLinkPreview.cacheMaxEntries must be between 0 and 100000");
    }
    if (maxConcurrentFetches != null && (maxConcurrentFetches < 1 || maxConcurrentFetches > 64)) {
      throw new IllegalArgumentException("tellomiLinkPreview.maxConcurrentFetches must be between 1 and 64");
    }
  }

  public Duration cacheTtlOrDefault() {
    return cacheTtl != null ? cacheTtl : DEFAULT_CACHE_TTL;
  }

  public Duration negativeCacheTtlOrDefault() {
    return negativeCacheTtl != null ? negativeCacheTtl : DEFAULT_NEGATIVE_CACHE_TTL;
  }

  public int cacheMaxEntriesOrDefault() {
    return cacheMaxEntries != null ? cacheMaxEntries : DEFAULT_CACHE_MAX_ENTRIES;
  }

  public int maxConcurrentFetchesOrDefault() {
    return maxConcurrentFetches != null ? maxConcurrentFetches : DEFAULT_MAX_CONCURRENT_FETCHES;
  }
}
