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
/// **上线闸门（不要在生产打开 `enabled`，直到下面全部满足）：**
/// 1. 法务回答了「删号时能不能把举报记录、证据截图、互动日志一起删」（设计文档 §LEGAL，owner: legal，待答）。引擎
///    现在的 `delete_account` 一律全删，包括别人对该用户内容的举报；需要保留的话要先改引擎，开关不能替法务做决定；
/// 2. 待办队列换成持久实现：配置 `outboxTableName`（DynamoDB 表，见 `DynamoDbEngineDeletionOutbox`；不配置 = 只在内存，重启就丢）。
///    表要先建好并打开 TTL（属性 `X`），启动时会检查表是否存在；另外还缺对账作业（设计文档 §13）；
/// 3. 引擎侧 `/internal` 在没配服务密钥时回 503 `service_disabled`（content-engine `bot/engine-internal-503-service-disabled`）。
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
/// @param secondDeleteDelay    第二次删除：账号删除后过这么久再删一次，默认 70 分钟（反馈凭证最长 1 小时有效，引擎不知道
///                             Signal 账号已经没了，旧凭证在这段时间里还能重新建主页等）。`PT0S` = 不做第二次删除。
///                             只在第一次删除成功之后才排；不超过 24 小时
/// @param outboxTableName      持久待办表的名字（DynamoDB，主键 `S`，索引 `P-N`，TTL 属性 `X`，建表形状见
///                             `DynamoDbEngineDeletionOutbox.createTableRequest`）。不配置 = 内存待办（不持久）。
///                             配了以后 `maxPending` 不再起作用（DynamoDB 是上限，看 `pending` 指标报警）
/// @param outboxTtl            持久待办每条的最长保留时间（含死信），默认 30 天（≥ 现在的删号保留窗口，之后 ACI 不会再被
///                             回收，重送不会碰到新账号）；1 天到 400 天。只在配了 `outboxTableName` 时校验
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
                                                 @Nullable Duration secondDeleteDelay,
                                                 @Nullable Boolean notFoundMeansDeleted,
                                                 @Nullable String outboxTableName,
                                                 @Nullable Duration outboxTtl) {

  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
  public static final int DEFAULT_MAX_ATTEMPTS = 8;
  public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(5);
  public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(15);
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);
  public static final int DEFAULT_MAX_PENDING = 10_000;
  /// feed credential lifetime (1 h, `TellomiFeedController.CREDENTIAL_LIFETIME`) + the engine's 60 s leeway + margin
  public static final Duration DEFAULT_SECOND_DELETE_DELAY = Duration.ofMinutes(70);
  public static final Duration MAX_SECOND_DELETE_DELAY = Duration.ofHours(24);

  /// not tied to the retention constants (which this class must not touch): only has to outlast the deleted-accounts
  /// window, after which an ACI can no longer be recycled
  public static final Duration DEFAULT_OUTBOX_TTL = Duration.ofDays(30);
  public static final Duration MIN_OUTBOX_TTL = Duration.ofDays(1);
  public static final Duration MAX_OUTBOX_TTL = Duration.ofDays(400);

  public static final int MIN_SERVICE_KEY_BYTES = 32;

  /// the configuration as it was before the durable outbox existed (in-memory outbox)
  public TellomiEngineDeletionConfiguration(boolean enabled, @Nullable String baseUrl, @Nullable SecretBytes serviceKey,
      @Nullable Duration connectTimeout, @Nullable Duration requestTimeout, @Nullable Integer maxAttempts,
      @Nullable Duration initialBackoff, @Nullable Duration maxBackoff, @Nullable Duration pollInterval,
      @Nullable Integer maxPending, @Nullable Duration secondDeleteDelay, @Nullable Boolean notFoundMeansDeleted) {
    this(enabled, baseUrl, serviceKey, connectTimeout, requestTimeout, maxAttempts, initialBackoff, maxBackoff,
        pollInterval, maxPending, secondDeleteDelay, notFoundMeansDeleted, null, null);
  }

  public TellomiEngineDeletionConfiguration {
    connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
    requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    maxAttempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
    initialBackoff = initialBackoff == null ? DEFAULT_INITIAL_BACKOFF : initialBackoff;
    maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;
    pollInterval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
    maxPending = maxPending == null ? DEFAULT_MAX_PENDING : maxPending;
    secondDeleteDelay = secondDeleteDelay == null ? DEFAULT_SECOND_DELETE_DELAY : secondDeleteDelay;
    notFoundMeansDeleted = notFoundMeansDeleted == null ? Boolean.TRUE : notFoundMeansDeleted;
    outboxTableName = outboxTableName == null || outboxTableName.isBlank() ? null : outboxTableName.trim();
    outboxTtl = outboxTtl == null ? DEFAULT_OUTBOX_TTL : outboxTtl;

    if (enabled) {
      validateEnabled(baseUrl, serviceKey, connectTimeout, requestTimeout, maxAttempts, initialBackoff, maxBackoff,
          pollInterval, maxPending, secondDeleteDelay);
      if (outboxTableName != null
          && (outboxTtl.compareTo(MIN_OUTBOX_TTL) < 0 || outboxTtl.compareTo(MAX_OUTBOX_TTL) > 0)) {
        throw new IllegalArgumentException("tellomiEngineDeletion.outboxTtl must be between P1D and P400D");
      }
    }
  }

  /// `true` when pending notices survive a restart (a table is configured)
  public boolean durableOutbox() {
    return outboxTableName != null;
  }

  /// 关着（缺省）的配置：什么都不做
  public static TellomiEngineDeletionConfiguration disabled() {
    return new TellomiEngineDeletionConfiguration(false, null, null, null, null, null, null, null, null, null, null, null,
        null, null);
  }

  private static void validateEnabled(@Nullable final String baseUrl, @Nullable final SecretBytes serviceKey,
      final Duration connectTimeout, final Duration requestTimeout, final int maxAttempts,
      final Duration initialBackoff, final Duration maxBackoff, final Duration pollInterval, final int maxPending,
      final Duration secondDeleteDelay) {

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
    if (secondDeleteDelay.isNegative() || secondDeleteDelay.compareTo(MAX_SECOND_DELETE_DELAY) > 0) {
      throw new IllegalArgumentException("tellomiEngineDeletion.secondDeleteDelay must be between PT0S (off) and PT24H");
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
