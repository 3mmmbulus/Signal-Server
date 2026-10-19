/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.micrometer.core.instrument.Metrics;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.whispersystems.textsecuregcm.configuration.TellomiLinkPreviewConfiguration;

/// 缓存 + 并发上限 + 指标（ADR-0063 §6.5.3）。
///
/// **隐私规则（写死在这里，改要过评审）**：
/// - 不记任何日志行。指标只有 provider id 和结果类别（[LinkPreviewFetcher.Outcome]），没有 URL、账号、IP；
/// - 缓存键是规范化 URL 的 SHA-256，值是卡片（标题、缩略图、域名）——都是网页的公开内容，**不含谁问过**；
///   只在内存，进程重启就没了，成功的默认留 10 分钟、失败的 2 分钟；
/// - 不落盘、不写 Redis / DynamoDB。限流计数在 Redis 里按账号记，但只是次数，不带 URL。
public final class LinkPreviewService {

  public static final String FETCH_COUNTER_NAME = "tellomi.linkPreview.fetch";
  public static final String CACHE_HIT_COUNTER_NAME = "tellomi.linkPreview.cacheHit";
  public static final String BUSY_COUNTER_NAME = "tellomi.linkPreview.busy";

  public sealed interface Answer permits Answer.Found, Answer.NotAvailable, Answer.Busy {

    record Found(LinkPreviewFetcher.Card card) implements Answer {
    }

    record NotAvailable() implements Answer {
    }

    record Busy() implements Answer {
    }
  }

  private final LinkPreviewFetcher fetcher;
  private final Cache<String, LinkPreviewFetcher.Card> positive;
  private final Cache<String, Boolean> negative;
  private final Semaphore permits;

  public LinkPreviewService(final LinkPreviewFetcher fetcher, final TellomiLinkPreviewConfiguration config) {
    this.fetcher = fetcher;
    this.positive = CacheBuilder.newBuilder()
        .maximumSize(config.cacheMaxEntriesOrDefault())
        .expireAfterWrite(config.cacheTtlOrDefault())
        .build();
    this.negative = CacheBuilder.newBuilder()
        .maximumSize(config.cacheMaxEntriesOrDefault())
        .expireAfterWrite(config.negativeCacheTtlOrDefault())
        .build();
    this.permits = new Semaphore(config.maxConcurrentFetchesOrDefault());
  }

  public Answer preview(final LinkPreviewUrls.Target target) {
    final String key = LinkPreviewUrls.cacheKey(target.uri());
    final String provider = target.provider().id();
    final LinkPreviewFetcher.Card cached = positive.getIfPresent(key);
    if (cached != null) {
      Metrics.counter(CACHE_HIT_COUNTER_NAME, "provider", provider, "result", "card").increment();
      return new Answer.Found(cached);
    }
    if (negative.getIfPresent(key) != null) {
      Metrics.counter(CACHE_HIT_COUNTER_NAME, "provider", provider, "result", "none").increment();
      return new Answer.NotAvailable();
    }
    if (!permits.tryAcquire()) {
      Metrics.counter(BUSY_COUNTER_NAME, "provider", provider).increment();
      return new Answer.Busy();
    }
    try {
      final LinkPreviewFetcher.Result result = fetcher.fetch(target);
      Metrics.counter(FETCH_COUNTER_NAME, "provider", provider, "outcome", result.outcome().name(),
          "image", String.valueOf(result.card() != null && result.card().image() != null)).increment();
      if (result.card() != null) {
        positive.put(key, result.card());
        return new Answer.Found(result.card());
      }
      negative.put(key, Boolean.TRUE);
      return new Answer.NotAvailable();
    } finally {
      permits.release();
    }
  }

  /// 只给测试用
  Optional<LinkPreviewFetcher.Card> cachedForTest(final LinkPreviewUrls.Target target) {
    return Optional.ofNullable(positive.getIfPresent(LinkPreviewUrls.cacheKey(target.uri())));
  }
}
