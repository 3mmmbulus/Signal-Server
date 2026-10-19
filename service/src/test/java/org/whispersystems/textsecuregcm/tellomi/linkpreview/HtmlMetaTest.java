/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HtmlMetaTest {

  @Test
  void readsOgTagsInEitherAttributeOrder() {
    final String html = """
        <html><head><title>站点</title>
        <meta property="og:title" content="WISE2024 年度企业发布｜正确的事，跨步向前" />
        <meta content="https://mmbiz.qpic.cn/a/0?wx_fmt=jpeg&amp;x=1" property='og:image'>
        </head><body>…</body></html>""";
    final HtmlMeta.Meta meta = HtmlMeta.parse(html.getBytes(StandardCharsets.UTF_8), "utf-8");
    assertEquals("WISE2024 年度企业发布｜正确的事，跨步向前", meta.ogTitle());
    assertEquals("https://mmbiz.qpic.cn/a/0?wx_fmt=jpeg&x=1", meta.ogImage());
    assertEquals("站点", meta.title());
  }

  @Test
  void decodesEntitiesAndCollapsesWhitespace() {
    final String html = "<meta property=\"og:title\" content=\"  A &amp; B&#x4E2D;&#25991;\n\t&quot;x&quot; \">";
    assertEquals("A & B中文 \"x\"", HtmlMeta.parse(html.getBytes(StandardCharsets.UTF_8), null).ogTitle());
  }

  @Test
  void gbkPagesAreDecoded() {
    final Charset gbk = Charset.forName("GBK");
    final byte[] body = "<meta charset=\"gb2312\"><title>新浪微博</title>".getBytes(gbk);
    assertEquals("新浪微博", HtmlMeta.parse(body, null).title());
  }

  @Test
  void headerCharsetWins() {
    final byte[] body = "<meta charset=\"utf-8\"><title>中文</title>".getBytes(Charset.forName("GB18030"));
    assertEquals("中文", HtmlMeta.parse(body, "gbk").title());
  }

  @Test
  void longTitlesAreCut() {
    final String t = "长".repeat(500);
    final String got = HtmlMeta.parse(("<title>" + t + "</title>").getBytes(StandardCharsets.UTF_8), null).title();
    assertEquals(HtmlMeta.MAX_TITLE_CODE_POINTS, got.codePointCount(0, got.length()));
  }

  @Test
  void missingTagsAreNull() {
    final HtmlMeta.Meta meta = HtmlMeta.parse("<html><body>x</body></html>".getBytes(StandardCharsets.UTF_8), null);
    assertNull(meta.ogTitle());
    assertNull(meta.title());
    assertNull(meta.ogImage());
  }

  @Test
  void twitterImageIsAFallback() {
    final String html = "<meta name=\"twitter:image\" content=\"https://n.sinaimg.cn/a.jpg\">";
    assertEquals("https://n.sinaimg.cn/a.jpg", HtmlMeta.parse(html.getBytes(StandardCharsets.UTF_8), null).ogImage());
  }
}
