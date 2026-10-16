/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionClient.Outcome;
import org.whispersystems.textsecuregcm.tellomi.EngineDeletionClient.Result;

class EngineDeletionClientTest {

  private static final byte[] SUB = TellomiEngineDeletionTestSupport.bytes(16, (byte) 0xfb); // base64url has '-' / '_'

  @Test
  void requestShape() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      final Result r = new EngineDeletionClient(TellomiEngineDeletionTestSupport.enabled(engine.baseUrl())).delete(SUB);

      assertThat(r.outcome()).isEqualTo(Outcome.DELETED);
      assertThat(engine.seen).hasSize(1);
      final MockEngine.Seen seen = engine.seen.getFirst();
      assertThat(seen.method()).isEqualTo("DELETE");
      // 16 bytes, base64url, no padding: what the engine's base64.RawURLEncoding accepts
      assertThat(seen.path()).isEqualTo("/internal/users/" + Base64.getUrlEncoder().withoutPadding().encodeToString(SUB));
      assertThat(seen.path()).doesNotContain("=").doesNotContain("+");
      // the engine compares against base64.StdEncoding.DecodeString(token) == ENGINE_SERVICE_KEY
      assertThat(seen.authorization())
          .isEqualTo("Bearer " + Base64.getEncoder().encodeToString(TellomiEngineDeletionTestSupport.SERVICE_KEY));
    }
  }

  @Test
  void trailingSlashOnTheBaseUrlIsHarmless() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      new EngineDeletionClient(TellomiEngineDeletionTestSupport.enabled(engine.baseUrl() + "/")).delete(SUB);
      assertThat(engine.seen.getFirst().path()).startsWith("/internal/users/").doesNotStartWith("//");
    }
  }

  @ParameterizedTest
  @CsvSource({
      "200, DELETED",
      "204, DELETED",
      "404, ALREADY_GONE",
      "410, ALREADY_GONE",
      "400, PERMANENT",
      "405, PERMANENT",
      "413, PERMANENT",
      "401, AUTH_FAILED",
      "403, AUTH_FAILED",
      "408, RETRY",
      "429, RETRY",
      "500, RETRY",
      "502, RETRY",
      "503, RETRY",
      "504, RETRY",
  })
  void statusMapping(final int status, final Outcome expected) throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> MockEngine.Reply.status(status);
      final Result r = new EngineDeletionClient(TellomiEngineDeletionTestSupport.enabled(engine.baseUrl())).delete(SUB);
      assertThat(r.outcome()).isEqualTo(expected);
      assertThat(r.status()).isEqualTo(status);
    }
  }

  @Test
  void notFoundIsNotSuccessWhenTheConfigurationSaysSo() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> MockEngine.Reply.status(404);
      final Result r = new EngineDeletionClient(
          TellomiEngineDeletionTestSupport.enabled(engine.baseUrl(), Duration.ofSeconds(2), 4, false)).delete(SUB);
      assertThat(r.outcome()).isEqualTo(Outcome.PERMANENT);
    }
    // 410 stays "gone" either way
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> MockEngine.Reply.status(410);
      final Result r = new EngineDeletionClient(
          TellomiEngineDeletionTestSupport.enabled(engine.baseUrl(), Duration.ofSeconds(2), 4, false)).delete(SUB);
      assertThat(r.outcome()).isEqualTo(Outcome.ALREADY_GONE);
    }
  }

  @Test
  void retryAfterIsReadFromRetryableAnswersOnlyAndCapped() throws Exception {
    final EngineDeletionClient client = new EngineDeletionClient(TellomiEngineDeletionTestSupport.enabled("http://127.0.0.1:1"));
    assertThat(client.classify(429, Optional.of("30")).retryAfter()).contains(Duration.ofSeconds(30));
    assertThat(client.classify(503, Optional.of("999999")).retryAfter()).contains(Duration.ofHours(1));
    assertThat(client.classify(503, Optional.of("Wed, 21 Oct 2026 07:28:00 GMT")).retryAfter()).isEmpty();
    assertThat(client.classify(503, Optional.of("-5")).retryAfter()).isEmpty();
    assertThat(client.classify(400, Optional.of("30")).retryAfter()).isEmpty();
  }

  @Test
  void redirectsAreNotFollowedAndAreNotRetried() throws Exception {
    try (final MockEngine target = new MockEngine(); final MockEngine engine = new MockEngine()) {
      engine.script = n -> MockEngine.Reply.status(302, "Location", target.baseUrl() + "/elsewhere");
      final Result r = new EngineDeletionClient(TellomiEngineDeletionTestSupport.enabled(engine.baseUrl())).delete(SUB);
      assertThat(r.outcome()).isEqualTo(Outcome.PERMANENT);
      assertThat(r.status()).isEqualTo(302);
      assertThat(target.hits.get()).as("the bearer key must not be sent to the redirect target").isZero();
    }
  }

  @Test
  void aSlowEngineTimesOutAndIsRetryable() throws Exception {
    try (final MockEngine engine = new MockEngine()) {
      engine.script = n -> MockEngine.Reply.slow(1500);
      final long start = System.nanoTime();
      final Result r = new EngineDeletionClient(
          TellomiEngineDeletionTestSupport.enabled(engine.baseUrl(), Duration.ofMillis(200), 4, true)).delete(SUB);
      assertThat(r.outcome()).isEqualTo(Outcome.RETRY);
      assertThat(r.status()).isZero();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }
  }

  @Test
  void aRefusedConnectionIsRetryable() throws Exception {
    final int port;
    try (final ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = s.getLocalPort();
    } // closed again: nothing listens
    final Result r = new EngineDeletionClient(TellomiEngineDeletionTestSupport.enabled("http://127.0.0.1:" + port)).delete(SUB);
    assertThat(r.outcome()).isEqualTo(Outcome.RETRY);
    assertThat(r.status()).isZero();
  }
}
