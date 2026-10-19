/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import java.io.IOException;

/// 给别的包的测试用（控制器测试要一张真 PNG）
public final class LinkPreviewFetcherTestSupport {

  private LinkPreviewFetcherTestSupport() {
  }

  public static byte[] png(final int w, final int h) throws IOException {
    return LinkPreviewFetcherTest.png(w, h);
  }
}
