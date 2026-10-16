/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.TellomiEngineDeletionConfiguration;

/// One `DELETE /internal/users/{sub}` call to the content engine (content-engine `internal/engine/api/deletion.go`).
///
/// - path: `sub` is the 16-byte pseudonym, base64url **without** padding (the engine decodes `RawURLEncoding`);
/// - auth: `Authorization: Bearer <standard base64 of ENGINE_SERVICE_KEY>`;
/// - success: `200 {"deleted_objects": n}`; the engine is idempotent (a second call is also 200);
/// - 400 `invalid_sub`, 401 `service_credential_invalid`, 404 `not_found` when the engine has no service key
///   configured (the route does not exist then), 5xx on its own errors.
///
/// The response body is never read or logged. Redirects are never followed (the bearer key must not leave for another
/// host). Nothing here logs the pseudonym, the key or the URL.
public class EngineDeletionClient {

  /// What one attempt came to.
  public enum Outcome {
    /// 200 / 204: the engine deleted (or had nothing)
    DELETED,
    /// 404 / 410, counted as "already gone" because the configuration says so
    ALREADY_GONE,
    /// network error, timeout, 5xx, 408, 425, 429: try again later
    RETRY,
    /// the engine refused our credentials (401 / 403): retrying does not help until an operator fixes the key
    AUTH_FAILED,
    /// any other 4xx, a redirect, or something unexpected: retrying the same request cannot succeed
    PERMANENT
  }

  /// @param outcome    what to do next
  /// @param status     the HTTP status, or 0 when there was no response (timeout, connection error)
  /// @param retryAfter the engine's `Retry-After` (whole seconds) when it sent one on a retryable answer
  public record Result(Outcome outcome, int status, Optional<Duration> retryAfter) {
    static Result of(final Outcome outcome, final int status) {
      return new Result(outcome, status, Optional.empty());
    }
  }

  private static final Logger log = LoggerFactory.getLogger(EngineDeletionClient.class);
  private static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

  private final HttpClient httpClient;
  private final URI base;
  private final String authorization;
  private final Duration requestTimeout;
  private final boolean notFoundMeansDeleted;

  public EngineDeletionClient(final TellomiEngineDeletionConfiguration config) {
    this(HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build(),
        config);
  }

  EngineDeletionClient(final HttpClient httpClient, final TellomiEngineDeletionConfiguration config) {
    this.httpClient = httpClient;
    final String url = config.baseUrl().trim();
    this.base = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    this.authorization = "Bearer " + Base64.getEncoder().encodeToString(config.serviceKey().value());
    this.requestTimeout = config.requestTimeout();
    this.notFoundMeansDeleted = config.notFoundMeansDeleted();
  }

  /// @param sub the 16-byte pseudonym
  public Result delete(final byte[] sub) {
    final HttpRequest request = HttpRequest.newBuilder(
            URI.create(base + "/internal/users/" + Base64.getUrlEncoder().withoutPadding().encodeToString(sub)))
        .timeout(requestTimeout)
        .header("Authorization", authorization)
        .header("Accept", "application/json")
        .header("User-Agent", "tellomi-signal-server")
        .DELETE()
        .build();
    try {
      final HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return classify(response.statusCode(), response.headers().firstValue("Retry-After"));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.of(Outcome.RETRY, 0);
    } catch (final IOException | RuntimeException e) {
      // HttpTimeoutException, ConnectException, SSL errors, ...: only the class goes to the log, never the message
      // (a connect error message can contain the host and port).
      log.debug("Tellomi engine deletion request failed: {}", e.getClass().getSimpleName());
      return Result.of(Outcome.RETRY, 0);
    }
  }

  Result classify(final int status, final Optional<String> retryAfterHeader) {
    if (status == 200 || status == 204) {
      return Result.of(Outcome.DELETED, status);
    }
    if (status == 410 || (status == 404 && notFoundMeansDeleted)) {
      return Result.of(Outcome.ALREADY_GONE, status);
    }
    if (status == 401 || status == 403) {
      return Result.of(Outcome.AUTH_FAILED, status);
    }
    if (status >= 500 || status == 408 || status == 425 || status == 429) {
      return new Result(Outcome.RETRY, status, retryAfterHeader.flatMap(EngineDeletionClient::parseRetryAfter));
    }
    // other 4xx (400 invalid_sub, 404 when not accepted as "gone", 405, 413, ...), 1xx and 3xx
    return Result.of(Outcome.PERMANENT, status);
  }

  private static Optional<Duration> parseRetryAfter(final String value) {
    try {
      final long seconds = Long.parseLong(value.trim());
      if (seconds < 0) {
        return Optional.empty();
      }
      final Duration d = Duration.ofSeconds(seconds);
      return Optional.of(d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d);
    } catch (final NumberFormatException e) {
      return Optional.empty(); // HTTP-date form: ignored, the normal backoff applies
    }
  }
}
