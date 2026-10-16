/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import jakarta.annotation.Nullable;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretBytes;

/// Tellomi（ADR-0071 §5.4、L2-5）：账号删除时通知内容引擎 `DELETE /internal/users/{sub}`。
/// 设计见 tellomi 仓库 `docs/drafts/ACCOUNT-DELETION-ENGINE-DESIGN.md`。
///
/// 默认关（`enabled = false`）：不配置这一段、或 `enabled: false`，行为和上游完全一样，不发任何请求。
/// 关着的时候不校验其它字段，所以示例配置里可以放占位符。
///
/// @param enabled              总开关，默认 `false`
/// @param baseUrl              引擎的根地址，必须 `https`（本机回环地址可用 `http`，只给联调）。不带路径
/// @param serviceKey           引擎 `ENGINE_SERVICE_KEY` 的值（Signal 的秘密包，base64），至少 32 字节；
///                             请求头是 `Authorization: Bearer <同一串 base64>`
/// @param connectTimeout       连接超时，默认 5 秒
/// @param requestTimeout       一次请求（含读响应）的超时，默认 10 秒
/// @param maxAttempts          一个删除请求最多试几次（含第一次），默认 8；用完进死信，不再自动重试
/// @param initialBackoff       第一次失败后的等待，默认 5 秒，之后每次翻倍，带抖动
/// @param maxBackoff           等待的上限，默认 15 分钟
/// @param pollInterval         后台扫一遍待办的间隔，默认 5 秒
/// @param maxPending           内存待办队列的上限，默认 10000；满了丢掉新的（记 ERROR 和计数），**不影响账号删除**
/// @param notFoundMeansDeleted 引擎回 404 / 410 算已删除，默认 `true`。注意：真实引擎在没配服务密钥时
///                             整个 `/internal` 路由是 404（`not_found`），这时 404 并不是「已删」，见设计文档第 5 节
public record TellomiEngineDeletionConfiguration(boolean enabled,
                                                 @Nullable String baseUrl,
                                                 @Nullable SecretBytes serviceKey,
                                                 @Nullable Duration connectTimeout,
                                                 @Nullable Duration requestTimeout,
                                                 @Nullable Integer maxAttempts,
                                                 @Nullable Duration initialBackoff,
                                                 @Nullable Duration maxBackoff,
                                                 @Nullable Duration pollInterval,
                                                 @Nullable Integer maxPending,
                                                 @Nullable Boolean notFoundMeansDeleted) {

  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
  public static final int DEFAULT_MAX_ATTEMPTS = 8;
  public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(5);
  public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(15);
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);
  public static final int DEFAULT_MAX_PENDING = 10_000;

  public static final int MIN_SERVICE_KEY_BYTES = 32;

  public TellomiEngineDeletionConfiguration {
    connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
    requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    maxAttempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
    initialBackoff = initialBackoff == null ? DEFAULT_INITIAL_BACKOFF : initialBackoff;
    maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;
    pollInterval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
    maxPending = maxPending == null ? DEFAULT_MAX_PENDING : maxPending;
    notFoundMeansDeleted = notFoundMeansDeleted == null ? Boolean.TRUE : notFoundMeansDeleted;

    if (enabled) {
      validateEnabled(baseUrl, serviceKey, connectTimeout, requestTimeout, maxAttempts, initialBackoff, maxBackoff,
          pollInterval, maxPending);
    }
  }

  /// 关着（缺省）的配置：什么都不做
  public static TellomiEngineDeletionConfiguration disabled() {
    return new TellomiEngineDeletionConfiguration(false, null, null, null, null, null, null, null, null, null, null);
  }

  private static void validateEnabled(@Nullable final String baseUrl, @Nullable final SecretBytes serviceKey,
      final Duration connectTimeout, final Duration requestTimeout, final int maxAttempts,
      final Duration initialBackoff, final Duration maxBackoff, final Duration pollInterval, final int maxPending) {

    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("tellomiEngineDeletion.baseUrl is required when enabled");
    }
    final URI uri;
    try {
      uri = URI.create(baseUrl.trim());
    } catch (final IllegalArgumentException e) {
      // do not echo the value: it is configuration, but keep error text free of anything operator-supplied
      throw new IllegalArgumentException("tellomiEngineDeletion.baseUrl is not a valid URL");
    }
    final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    final boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
        || host.equals("::1");
    if (host.isEmpty() || !(scheme.equals("https") || (scheme.equals("http") && loopback))) {
      throw new IllegalArgumentException(
          "tellomiEngineDeletion.baseUrl must be an https URL (http only for a loopback address)");
    }
    if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("tellomiEngineDeletion.baseUrl must not carry credentials, a query or a fragment");
    }
    if (serviceKey == null) {
      throw new IllegalArgumentException("tellomiEngineDeletion.serviceKey is required when enabled");
    }
    if (serviceKey.value().length < MIN_SERVICE_KEY_BYTES) {
      throw new IllegalArgumentException(
          "tellomiEngineDeletion.serviceKey must be at least " + MIN_SERVICE_KEY_BYTES + " bytes");
    }
    requirePositive("connectTimeout", connectTimeout);
    requirePositive("requestTimeout", requestTimeout);
    requirePositive("initialBackoff", initialBackoff);
    requirePositive("maxBackoff", maxBackoff);
    requirePositive("pollInterval", pollInterval);
    if (requestTimeout.compareTo(Duration.ofSeconds(60)) > 0 || connectTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("tellomiEngineDeletion timeouts must be at most 60 seconds");
    }
    if (maxBackoff.compareTo(initialBackoff) < 0) {
      throw new IllegalArgumentException("tellomiEngineDeletion.maxBackoff must not be less than initialBackoff");
    }
    if (maxAttempts < 1 || maxAttempts > 50) {
      throw new IllegalArgumentException("tellomiEngineDeletion.maxAttempts must be between 1 and 50");
    }
    if (maxPending < 1) {
      throw new IllegalArgumentException("tellomiEngineDeletion.maxPending must be positive");
    }
  }

  private static void requirePositive(final String name, final Duration value) {
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException("tellomiEngineDeletion." + name + " must be positive");
    }
  }
}
