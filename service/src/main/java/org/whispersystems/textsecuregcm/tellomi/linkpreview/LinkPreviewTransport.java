/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;

/// 发一个 GET。不跟重定向（由 [LinkPreviewFetcher] 一跳一跳核）、不带 cookie、不走代理。
public interface LinkPreviewTransport {

  /// @param truncated 读到上限（或读到 `stopAfter`）就停了，没读完
  record Response(int status, @Nullable String location, @Nullable String contentType, byte[] body,
                  boolean truncated) {
  }

  /// @param maxBytes  正文最多读多少字节（**解压后**），超了就停、`truncated = true`，不是报错
  /// @param stopAfter 读到这个 ASCII 标记（不分大小写，例如 `</head>`）就停；null = 不提前停
  Response get(URI uri, String userAgent, String accept, int maxBytes, Duration timeout, @Nullable String stopAfter)
      throws IOException, InterruptedException;

  /// JDK HttpClient 实现。
  final class Jdk implements LinkPreviewTransport {

    private final HttpClient client;

    public Jdk(final Duration connectTimeout) {
      this.client = HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .proxy(HttpClient.Builder.NO_PROXY)
          .connectTimeout(connectTimeout)
          .build();
      // 没设 cookieHandler = 不存也不发 cookie
    }


    @Override
    public Response get(final URI uri, final String userAgent, final String accept, final int maxBytes,
        final Duration timeout, @Nullable final String stopAfter) throws IOException, InterruptedException {

      final HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(timeout)
          .header("User-Agent", userAgent)
          .header("Accept", accept)
          .header("Accept-Encoding", "gzip")
          .GET()
          .build();
      final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (final InputStream raw = response.body()) {
        final int status = response.statusCode();
        final String location = response.headers().firstValue("Location").orElse(null);
        final String contentType = response.headers().firstValue("Content-Type").orElse(null);
        if (status < 200 || status >= 300) {
          return new Response(status, location, contentType, new byte[0], false);
        }
        final String encoding = response.headers().firstValue("Content-Encoding").orElse("identity")
            .trim().toLowerCase(Locale.ROOT);
        final InputStream in;
        if (encoding.equals("gzip") || encoding.equals("x-gzip")) {
          in = new GZIPInputStream(raw);
        } else if (encoding.equals("identity") || encoding.isEmpty()) {
          in = raw;
        } else {
          throw new IOException("unsupported content encoding");
        }
        return readLimited(status, location, contentType, in, maxBytes, stopAfter);
      }
    }
  }

  static Response readLimited(final int status, @Nullable final String location, @Nullable final String contentType,
      final InputStream in, final int maxBytes, @Nullable final String stopAfter) throws IOException {
    final byte[] marker = stopAfter == null ? null : stopAfter.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
    final byte[] buf = new byte[16 * 1024];
    int matched = 0;
    while (out.size() < maxBytes) {
      final int n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()));
      if (n < 0) {
        return new Response(status, location, contentType, out.toByteArray(), false);
      }
      if (marker != null) {
        for (int i = 0; i < n; i++) {
          final byte b = buf[i];
          final byte lower = (b >= 'A' && b <= 'Z') ? (byte) (b + 32) : b;
          if (lower == marker[matched]) {
            matched++;
            if (matched == marker.length) {
              out.write(buf, 0, i + 1);
              return new Response(status, location, contentType, out.toByteArray(), true);
            }
          } else {
            matched = lower == marker[0] ? 1 : 0;
          }
        }
      }
      out.write(buf, 0, n);
    }
    return new Response(status, location, contentType, out.toByteArray(), true);
  }
}
