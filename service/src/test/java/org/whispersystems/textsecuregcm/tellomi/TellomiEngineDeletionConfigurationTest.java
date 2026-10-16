/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.whispersystems.textsecuregcm.configuration.TellomiEngineDeletionConfiguration;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretBytes;

class TellomiEngineDeletionConfigurationTest {

  private static TellomiEngineDeletionConfiguration with(final boolean enabled, final String baseUrl,
      final byte[] key) {
    return new TellomiEngineDeletionConfiguration(enabled, baseUrl, key == null ? null : new SecretBytes(key), null,
        null, null, null, null, null, null, null, null);
  }

  @Test
  void disabledByDefault() throws Exception {
    assertThat(TellomiEngineDeletionConfiguration.disabled().enabled()).isFalse();
    // a config section that omits `enabled` is off, whatever else it says
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final TellomiEngineDeletionConfiguration parsed = mapper.readValue(
        "{\"baseUrl\":\"https://engine.example.invalid\"}", TellomiEngineDeletionConfiguration.class);
    assertThat(parsed.enabled()).isFalse();
  }

  @Test
  void disabledConfigurationIsNotValidatedSoPlaceholdersAreFine() {
    // placeholder / empty values while off must not stop the server from starting
    assertThat(with(false, "", null).enabled()).isFalse();
    assertThat(with(false, "not a url", null).enabled()).isFalse();
  }

  @Test
  void defaults() {
    final TellomiEngineDeletionConfiguration c = with(true, "https://engine.example.invalid",
        TellomiEngineDeletionTestSupport.SERVICE_KEY);
    assertThat(c.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(c.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(c.maxAttempts()).isEqualTo(8);
    assertThat(c.initialBackoff()).isEqualTo(Duration.ofSeconds(5));
    assertThat(c.maxBackoff()).isEqualTo(Duration.ofMinutes(15));
    assertThat(c.maxPending()).isEqualTo(10_000);
    assertThat(c.notFoundMeansDeleted()).isTrue();
  }

  @Test
  void enabledRequiresUrlAndKey() {
    final byte[] key = TellomiEngineDeletionTestSupport.SERVICE_KEY;
    assertThatThrownBy(() -> with(true, null, key)).hasMessageContaining("baseUrl");
    assertThatThrownBy(() -> with(true, " ", key)).hasMessageContaining("baseUrl");
    assertThatThrownBy(() -> with(true, "https://engine.example.invalid", null)).hasMessageContaining("serviceKey");
    assertThatThrownBy(() -> with(true, "https://engine.example.invalid", TellomiEngineDeletionTestSupport.bytes(31, (byte) 1)))
        .hasMessageContaining("at least 32");
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://engine.example.invalid", "ftp://engine.example.invalid", "engine.example.invalid",
      "https://user:pw@engine.example.invalid", "https://engine.example.invalid/?k=v", "http://127.0.0.1.evil.example"})
  void enabledRejectsUnsafeUrls(final String url) {
    assertThatThrownBy(() -> with(true, url, TellomiEngineDeletionTestSupport.SERVICE_KEY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://engine.example.invalid", "https://engine.example.invalid:8443/", "http://127.0.0.1:8080",
      "http://localhost:8080"})
  void enabledAcceptsHttpsAndLoopbackHttp(final String url) {
    assertThat(with(true, url, TellomiEngineDeletionTestSupport.SERVICE_KEY).enabled()).isTrue();
  }

  @Test
  void errorMessagesDoNotEchoTheUrlOrTheKey() {
    final String url = "https://user:topsecret@engine.example.invalid";
    assertThatThrownBy(() -> with(true, url, TellomiEngineDeletionTestSupport.SERVICE_KEY))
        .hasMessageNotContaining("topsecret");
  }

  @Test
  void rejectsInconsistentBackoffAndLimits() {
    final byte[] key = TellomiEngineDeletionTestSupport.SERVICE_KEY;
    final SecretBytes k = new SecretBytes(key);
    final String url = "https://engine.example.invalid";
    assertThatThrownBy(() -> new TellomiEngineDeletionConfiguration(true, url, k, null, null, 0, null, null, null, null, null, null))
        .hasMessageContaining("maxAttempts");
    assertThatThrownBy(() -> new TellomiEngineDeletionConfiguration(true, url, k, null, null, 51, null, null, null, null, null, null))
        .hasMessageContaining("maxAttempts");
    assertThatThrownBy(() -> new TellomiEngineDeletionConfiguration(true, url, k, null, null, null,
        Duration.ofMinutes(2), Duration.ofSeconds(1), null, null, null, null)).hasMessageContaining("maxBackoff");
    assertThatThrownBy(() -> new TellomiEngineDeletionConfiguration(true, url, k, null, Duration.ZERO, null, null, null,
        null, null, null, null)).hasMessageContaining("requestTimeout");
    assertThatThrownBy(() -> new TellomiEngineDeletionConfiguration(true, url, k, null, Duration.ofMinutes(5), null, null,
        null, null, null, null, null)).hasMessageContaining("60 seconds");
    assertThatThrownBy(() -> new TellomiEngineDeletionConfiguration(true, url, k, null, null, null, null, null, null, 0,
        null, null)).hasMessageContaining("maxPending");
  }

  @Test
  void secondDeleteDelayDefaultsToSeventyMinutesAndCanBeTurnedOff() {
    final TellomiEngineDeletionConfiguration c = with(true, "https://engine.example.invalid",
        TellomiEngineDeletionTestSupport.SERVICE_KEY);
    assertThat(c.secondDeleteDelay()).isEqualTo(Duration.ofMinutes(70));
    final SecretBytes k = new SecretBytes(TellomiEngineDeletionTestSupport.SERVICE_KEY);
    final String url = "https://engine.example.invalid";
    assertThat(new TellomiEngineDeletionConfiguration(true, url, k, null, null, null, null, null, null, null,
        Duration.ZERO, null).secondDeleteDelay()).isEqualTo(Duration.ZERO);
    assertThatThrownBy(() -> new TellomiEngineDeletionConfiguration(true, url, k, null, null, null, null, null, null,
        null, Duration.ofMinutes(-1), null)).hasMessageContaining("secondDeleteDelay");
    assertThatThrownBy(() -> new TellomiEngineDeletionConfiguration(true, url, k, null, null, null, null, null, null,
        null, Duration.ofHours(25), null)).hasMessageContaining("secondDeleteDelay");
  }
}
