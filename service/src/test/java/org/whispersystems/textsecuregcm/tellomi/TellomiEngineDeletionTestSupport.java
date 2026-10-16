/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import java.time.Duration;
import java.util.Arrays;
import org.whispersystems.textsecuregcm.configuration.TellomiEngineDeletionConfiguration;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretBytes;

final class TellomiEngineDeletionTestSupport {

  /// not a real key: 32 test bytes
  static final byte[] SERVICE_KEY = bytes(32, (byte) 'k');

  private TellomiEngineDeletionTestSupport() {
  }

  static byte[] bytes(final int n, final byte b) {
    final byte[] a = new byte[n];
    Arrays.fill(a, b);
    return a;
  }

  static TellomiEngineDeletionConfiguration enabled(final String baseUrl) {
    return enabled(baseUrl, Duration.ofSeconds(2), 4, true);
  }

  /// second delete off (PT0S), so these tests see exactly one request per account
  static TellomiEngineDeletionConfiguration enabled(final String baseUrl, final Duration requestTimeout,
      final int maxAttempts, final boolean notFoundMeansDeleted) {
    return enabled(baseUrl, requestTimeout, maxAttempts, notFoundMeansDeleted, Duration.ZERO);
  }

  static TellomiEngineDeletionConfiguration enabled(final String baseUrl, final Duration requestTimeout,
      final int maxAttempts, final boolean notFoundMeansDeleted, final Duration secondDeleteDelay) {
    return new TellomiEngineDeletionConfiguration(true, baseUrl, new SecretBytes(SERVICE_KEY), Duration.ofSeconds(2),
        requestTimeout, maxAttempts, Duration.ofSeconds(10), Duration.ofSeconds(80), Duration.ofSeconds(1), 100,
        secondDeleteDelay, notFoundMeansDeleted);
  }
}
