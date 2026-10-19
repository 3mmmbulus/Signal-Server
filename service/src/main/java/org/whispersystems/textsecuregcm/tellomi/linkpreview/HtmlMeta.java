/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/// 只做 OG 与 `<title>` 的轻量提取（和 `rust/links` 一样：不执行 JS、不建 DOM）。输入是页面开头的一段字节
/// （最多 [LinkPreviewFetcher#MAX_HTML_BYTES]，读到 `</head>` 就停）。
public final class HtmlMeta {

  public static final int MAX_TITLE_CODE_POINTS = 200;

  private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]{0,4096}>", Pattern.CASE_INSENSITIVE);
  private static final Pattern ATTR = Pattern.compile(
      "([a-zA-Z_:][-a-zA-Z0-9_:.]{0,64})\\s*=\\s*(?:\"([^\"]{0,4096})\"|'([^']{0,4096})'|([^\\s\"'>]{1,4096}))");
  private static final Pattern TITLE = Pattern.compile("<title\\b[^>]{0,1024}>(.{0,4096}?)</title\\s*>",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern META_CHARSET = Pattern.compile(
      "<meta\\b[^>]{0,512}?charset\\s*=\\s*[\"']?\\s*([-a-zA-Z0-9_:.]{1,40})", Pattern.CASE_INSENSITIVE);
  private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
  private static final Map<String, String> NAMED = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"",
      "apos", "'", "nbsp", " ", "middot", "·", "mdash", "—", "ndash", "–", "hellip", "…");

  public record Meta(@Nullable String ogTitle, @Nullable String title, @Nullable String ogImage) {
  }

  private HtmlMeta() {
  }

  public static Meta parse(final byte[] body, @Nullable final String contentTypeCharset) {
    final Charset charset = charset(body, contentTypeCharset);
    final String html = new String(body, charset);

    final Map<String, String> meta = new HashMap<>();
    final Matcher m = META_TAG.matcher(html);
    while (m.find()) {
      String key = null;
      String content = null;
      final Matcher a = ATTR.matcher(m.group());
      while (a.find()) {
        final String name = a.group(1).toLowerCase(Locale.ROOT);
        final String value = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
        if (name.equals("property") || name.equals("name") || name.equals("itemprop")) {
          key = value.trim().toLowerCase(Locale.ROOT);
        } else if (name.equals("content")) {
          content = value;
        }
      }
      if (key != null && content != null) {
        meta.putIfAbsent(key, content);
      }
    }

    final Matcher t = TITLE.matcher(html);
    final String title = t.find() ? clean(t.group(1)) : null;
    String image = meta.get("og:image");
    if (image == null || image.isBlank()) {
      image = meta.get("og:image:url");
    }
    if (image == null || image.isBlank()) {
      image = meta.get("twitter:image");
    }
    return new Meta(clean(meta.get("og:title")), title, image == null || image.isBlank() ? null : unescape(image.trim()));
  }

  static Charset charset(final byte[] body, @Nullable final String declared) {
    final Charset fromHeader = lookup(declared);
    if (fromHeader != null) {
      return fromHeader;
    }
    final String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
    final Matcher m = META_CHARSET.matcher(head);
    if (m.find()) {
      final Charset c = lookup(m.group(1));
      if (c != null) {
        return c;
      }
    }
    return StandardCharsets.UTF_8;
  }

  @Nullable
  private static Charset lookup(@Nullable final String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    try {
      final String n = name.trim().toLowerCase(Locale.ROOT);
      // gb2312 页面里常有 GBK 才有的字；按 WHATWG 的做法当 GBK（GB18030 是它的超集）
      if (n.equals("gb2312") || n.equals("gbk") || n.equals("x-gbk")) {
        return Charset.forName("GB18030");
      }
      return Charset.forName(n);
    } catch (final RuntimeException e) {
      return null;
    }
  }

  @Nullable
  static String clean(@Nullable final String raw) {
    if (raw == null) {
      return null;
    }
    final String s = unescape(raw).replaceAll("[\\p{Cntrl}\\u200B\\u2060\\uFEFF]", " ").replaceAll("\\s+", " ").trim();
    if (s.isEmpty()) {
      return null;
    }
    if (s.codePointCount(0, s.length()) <= MAX_TITLE_CODE_POINTS) {
      return s;
    }
    return s.substring(0, s.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS - 1)) + "…";
  }

  static String unescape(final String s) {
    if (s.indexOf('&') < 0) {
      return s;
    }
    final Matcher m = ENTITY.matcher(s);
    final StringBuilder out = new StringBuilder();
    while (m.find()) {
      final String e = m.group(1);
      String rep = null;
      try {
        if (e.startsWith("#x") || e.startsWith("#X")) {
          rep = codePoint(Integer.parseInt(e.substring(2), 16));
        } else if (e.startsWith("#")) {
          rep = codePoint(Integer.parseInt(e.substring(1)));
        } else {
          rep = NAMED.get(e.toLowerCase(Locale.ROOT));
        }
      } catch (final NumberFormatException ignored) {
        // 保留原样
      }
      m.appendReplacement(out, Matcher.quoteReplacement(rep != null ? rep : m.group()));
    }
    m.appendTail(out);
    return out.toString();
  }

  @Nullable
  private static String codePoint(final int cp) {
    if (cp <= 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
      return null;
    }
    return new String(Character.toChars(cp));
  }
}
