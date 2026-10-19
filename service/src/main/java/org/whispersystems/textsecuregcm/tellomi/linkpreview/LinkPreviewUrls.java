/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/// 请求里那条链接的形状检查与规范化（ADR-0063 §6.5.3 / §6.5.4）。
///
/// - 只收 https、默认端口、没有 userinfo、长度 ≤ 2048、只有 ASCII（客户端发之前要百分号编码）；
/// - 主机必须在白名单里；
/// - 去掉片段（`#…`）和该 provider 的分享者 / 追踪参数，再抓、再算缓存键。去参数**也是隐私措施**：公众号的
///   `sharer_shareid` 这类参数能认出是谁分享的，带着它去抓，等于把「谁分享了」告诉了站点。
public final class LinkPreviewUrls {

  public static final int MAX_URL_LENGTH = 2048;

  private LinkPreviewUrls() {
  }

  public record Target(LinkPreviewProviders.Provider provider, URI uri) {
  }

  public static Optional<Target> parse(final String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > MAX_URL_LENGTH) {
      return Optional.empty();
    }
    for (int i = 0; i < raw.length(); i++) {
      final char c = raw.charAt(i);
      if (c <= 0x20 || c >= 0x7f) {
        return Optional.empty();
      }
    }
    final URI uri;
    try {
      uri = new URI(raw);
    } catch (final URISyntaxException e) {
      return Optional.empty();
    }
    return normalizeHop(uri, null);
  }

  /// 一跳的检查（入口和每次重定向都用）：https、默认端口、无 userinfo、主机在 provider 里。
  /// `required` 不为空时，主机必须还属于同一个 provider。
  public static Optional<Target> normalizeHop(final URI uri, final LinkPreviewProviders.Provider required) {
    if (!uri.isAbsolute() || uri.isOpaque() || !"https".equalsIgnoreCase(uri.getScheme())) {
      return Optional.empty();
    }
    if (uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null) {
      return Optional.empty();
    }
    final String host = uri.getHost().toLowerCase(Locale.ROOT);
    final Optional<LinkPreviewProviders.Provider> provider = LinkPreviewProviders.forPageHost(host);
    if (provider.isEmpty() || (required != null && !required.id().equals(provider.get().id()))) {
      return Optional.empty();
    }
    final String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    final String query = stripParams(provider.get(), uri.getRawQuery());
    try {
      return Optional.of(new Target(provider.get(),
          new URI("https://" + host + path + (query == null ? "" : "?" + query))));
    } catch (final URISyntaxException e) {
      return Optional.empty();
    }
  }

  static String stripParams(final LinkPreviewProviders.Provider provider, final String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return null;
    }
    final StringBuilder out = new StringBuilder();
    for (final String pair : rawQuery.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      final int eq = pair.indexOf('=');
      final String name = eq < 0 ? pair : pair.substring(0, eq);
      if (provider.isStrippedParam(name)) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append('&');
      }
      out.append(pair);
    }
    return out.isEmpty() ? null : out.toString();
  }

  /// 缓存键 = 规范化 URL 的 SHA-256（十六进制）。不含账号、设备、IP。
  public static String cacheKey(final URI normalized) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(normalized.toString().getBytes(StandardCharsets.US_ASCII)));
    } catch (final NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  /// og:image 的地址：相对地址按页面地址解析，`//` 开头补 https，http 升成 https（只对白名单图片主机）。
  public static Optional<URI> imageUri(final LinkPreviewProviders.Provider provider, final URI page, final String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String s = raw.trim();
    if (s.isEmpty() || s.length() > MAX_URL_LENGTH) {
      return Optional.empty();
    }
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (c <= 0x20 || c >= 0x7f) {
        return Optional.empty();
      }
    }
    if (s.regionMatches(true, 0, "http://", 0, 7)) {
      s = "https://" + s.substring(7);
    }
    final URI resolved;
    try {
      resolved = page.resolve(new URI(s));
    } catch (final URISyntaxException | IllegalArgumentException e) {
      return Optional.empty();
    }
    if (!"https".equalsIgnoreCase(resolved.getScheme()) || resolved.getHost() == null
        || resolved.getRawUserInfo() != null || (resolved.getPort() != -1 && resolved.getPort() != 443)
        || !provider.isImageHost(resolved.getHost())) {
      return Optional.empty();
    }
    final String path = resolved.getRawPath() == null || resolved.getRawPath().isEmpty() ? "/" : resolved.getRawPath();
    try {
      return Optional.of(new URI("https://" + resolved.getHost().toLowerCase(Locale.ROOT) + path
          + (resolved.getRawQuery() == null ? "" : "?" + resolved.getRawQuery())));
    } catch (final URISyntaxException e) {
      return Optional.empty();
    }
  }
}
