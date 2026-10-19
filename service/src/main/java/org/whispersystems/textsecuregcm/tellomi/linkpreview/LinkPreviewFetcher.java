/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import javax.annotation.Nullable;

/// 抓一条白名单链接、做成卡片（ADR-0063 §6.5.2 / §6.5.4）。不缓存、不记日志——那些在 [LinkPreviewService]。
///
/// 抓取契约（与客户端 §4.4 同口径，只在列出的地方不同）：
/// - 只走 https、默认端口；每一跳先过 [AddressGuard]，再核「还在同一家的页面主机里」；最多 5 跳；
/// - 不带 cookie / Accept-Language；UA 按 provider（[LinkPreviewProviders]）；
/// - 整条链接总预算 10 s，单个请求 ≤ 8 s，连接 5 s；
/// - 页面最多读 1 MiB（解压后），读到 `</head>` 就停；**超了是截断、不是丢弃**（产品经理 2026-10-19 (c)）；
/// - 图片：1 个请求、不跟重定向、≤ 2 MiB、Content-Type 必须是 image/*，再交给 [ImageThumbnailer]。
public final class LinkPreviewFetcher {

  public static final int MAX_HTML_BYTES = 1024 * 1024;
  public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
  public static final int MAX_REDIRECTS = 5;
  public static final Duration TOTAL_BUDGET = Duration.ofSeconds(10);
  public static final Duration MAX_REQUEST_TIME = Duration.ofSeconds(8);
  public static final Duration MIN_IMAGE_BUDGET = Duration.ofMillis(800);

  public enum Outcome {
    OK,
    /// 重定向到白名单（同一家）以外，或者落到登录 / 验证码 / 404 路径
    LEFT_PROVIDER,
    /// 解析出内网 / 保留地址
    FORBIDDEN_ADDRESS,
    HTTP_STATUS,
    NOT_HTML,
    /// 页面拿到了，但没有这条内容自己的标题（只有站点通用标题）
    NO_TITLE,
    TIMEOUT,
    NETWORK
  }

  public record Card(String title, String domain, String provider, @Nullable ImageThumbnailer.Thumbnail image) {
  }

  public record Result(Outcome outcome, @Nullable Card card) {

    static Result failed(final Outcome outcome) {
      return new Result(outcome, null);
    }
  }

  private final LinkPreviewTransport transport;
  private final AddressGuard.Resolver resolver;
  private final Clock clock;

  public LinkPreviewFetcher(final LinkPreviewTransport transport, final AddressGuard.Resolver resolver,
      final Clock clock) {
    this.transport = transport;
    this.resolver = resolver;
    this.clock = clock;
  }

  public Result fetch(final LinkPreviewUrls.Target target) {
    final Instant deadline = clock.instant().plus(TOTAL_BUDGET);
    final LinkPreviewProviders.Provider provider = target.provider();
    URI current = target.uri();
    try {
      LinkPreviewTransport.Response page = null;
      for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
        if (provider.isBlockedPath(current.getRawPath())) {
          return Result.failed(Outcome.LEFT_PROVIDER);
        }
        AddressGuard.check(resolver, current.getHost());
        final Duration remaining = remaining(deadline);
        if (remaining.isZero()) {
          return Result.failed(Outcome.TIMEOUT);
        }
        final LinkPreviewTransport.Response response = transport.get(current, provider.userAgent(),
            "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1", MAX_HTML_BYTES, min(remaining, MAX_REQUEST_TIME),
            "</head>");
        if (isRedirect(response.status())) {
          if (response.location() == null || hop == MAX_REDIRECTS) {
            return Result.failed(Outcome.LEFT_PROVIDER);
          }
          final Optional<LinkPreviewUrls.Target> next = resolveRedirect(current, response.location(), provider);
          if (next.isEmpty()) {
            return Result.failed(Outcome.LEFT_PROVIDER);
          }
          current = next.get().uri();
          continue;
        }
        page = response;
        break;
      }
      if (page == null) {
        return Result.failed(Outcome.LEFT_PROVIDER);
      }
      if (page.status() < 200 || page.status() >= 300) {
        return Result.failed(Outcome.HTTP_STATUS);
      }
      final String mime = mime(page.contentType());
      if (!mime.equals("text/html") && !mime.equals("application/xhtml+xml")) {
        return Result.failed(Outcome.NOT_HTML);
      }

      final HtmlMeta.Meta meta = HtmlMeta.parse(page.body(), charsetParam(page.contentType()));
      final String title = pickTitle(provider, meta);
      if (title == null) {
        return Result.failed(Outcome.NO_TITLE);
      }

      ImageThumbnailer.Thumbnail image = null;
      final Optional<URI> imageUri = LinkPreviewUrls.imageUri(provider, current, meta.ogImage());
      if (imageUri.isPresent() && remaining(deadline).compareTo(MIN_IMAGE_BUDGET) >= 0) {
        image = fetchImage(provider, imageUri.get(), deadline);
      }
      return new Result(Outcome.OK, new Card(title, target.uri().getHost(), provider.id(), image));
    } catch (final AddressGuard.ForbiddenAddressException e) {
      return Result.failed(Outcome.FORBIDDEN_ADDRESS);
    } catch (final HttpTimeoutException e) {
      return Result.failed(Outcome.TIMEOUT);
    } catch (final IOException e) {
      return Result.failed(Outcome.NETWORK);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.failed(Outcome.TIMEOUT);
    }
  }

  @Nullable
  private ImageThumbnailer.Thumbnail fetchImage(final LinkPreviewProviders.Provider provider, final URI uri,
      final Instant deadline) {
    try {
      AddressGuard.check(resolver, uri.getHost());
      final LinkPreviewTransport.Response response = transport.get(uri, provider.userAgent(),
          "image/jpeg,image/png,image/webp,image/gif;q=0.9", MAX_IMAGE_BYTES, min(remaining(deadline), MAX_REQUEST_TIME),
          null);
      if (response.status() < 200 || response.status() >= 300 || response.truncated()
          || !mime(response.contentType()).startsWith("image/")) {
        return null;
      }
      return ImageThumbnailer.thumbnail(response.body()).orElse(null);
    } catch (final IOException e) {
      return null;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  @Nullable
  static String pickTitle(final LinkPreviewProviders.Provider provider, final HtmlMeta.Meta meta) {
    if (meta.ogTitle() != null && !provider.isGenericTitle(meta.ogTitle())) {
      return meta.ogTitle();
    }
    if (meta.title() != null && !provider.isGenericTitle(meta.title())) {
      return meta.title();
    }
    return null;
  }

  static Optional<LinkPreviewUrls.Target> resolveRedirect(final URI current, final String location,
      final LinkPreviewProviders.Provider provider) {
    final String loc = location.trim();
    if (loc.isEmpty() || loc.length() > LinkPreviewUrls.MAX_URL_LENGTH) {
      return Optional.empty();
    }
    for (int i = 0; i < loc.length(); i++) {
      final char c = loc.charAt(i);
      if (c <= 0x20 || c >= 0x7f) {
        return Optional.empty();
      }
    }
    try {
      return LinkPreviewUrls.normalizeHop(current.resolve(new URI(loc)), provider);
    } catch (final URISyntaxException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static boolean isRedirect(final int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  static String mime(@Nullable final String contentType) {
    if (contentType == null) {
      return "";
    }
    final int semi = contentType.indexOf(';');
    return (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
  }

  @Nullable
  static String charsetParam(@Nullable final String contentType) {
    if (contentType == null) {
      return null;
    }
    for (final String part : contentType.split(";")) {
      final String p = part.trim();
      if (p.regionMatches(true, 0, "charset=", 0, 8)) {
        return p.substring(8).replace("\"", "").trim();
      }
    }
    return null;
  }

  private Duration remaining(final Instant deadline) {
    final Duration d = Duration.between(clock.instant(), deadline);
    return d.isNegative() ? Duration.ZERO : d;
  }

  private static Duration min(final Duration a, final Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
