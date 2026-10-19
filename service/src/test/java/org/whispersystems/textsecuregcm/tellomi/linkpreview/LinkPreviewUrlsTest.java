/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkPreviewUrlsTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "https://mp.weixin.qq.com/s/_chjr0XiTqoDg3Qg6JzU_g",
      "https://www.zhihu.com/question/640359835/answer/3371728492",
      "https://zhuanlan.zhihu.com/p/74424675",
      "https://weibo.com/2/detail/comos:nisvcqf1240032",
      "https://m.weibo.cn/status/4154099588849643",
      "https://www.xiaohongshu.com/explore/6858ccaa0000000013013c94",
      "https://xhslink.com/o/8iQbOIYV4IX",
      "https://v.douyin.com/8rmc2PU/",
      "https://www.douyin.com/video/7567415789267995955",
      "https://www.iesdouyin.com/share/video/7548643435046030628/",
      "https://item.taobao.com/item.htm?id=674169489573",
      "https://detail.tmall.com/item.htm?id=540349415064",
      "https://detail.m.tmall.com/item.htm?id=540349415064",
      "HTTPS://MP.WEIXIN.QQ.COM/s/abc",
      "https://mp.weixin.qq.com:443/s/abc",
  })
  void acceptsTheWhitelistedProviders(final String url) {
    assertTrue(LinkPreviewUrls.parse(url).isPresent(), url);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://mp.weixin.qq.com/s/abc",            // 只收 https
      "https://mp.weixin.qq.com:8443/s/abc",      // 非默认端口
      "https://user@mp.weixin.qq.com/s/abc",      // userinfo
      "https://evil.com/s/abc",                   // 不在白名单
      "https://mp.weixin.qq.com.evil.com/s/abc",  // 后缀仿冒
      "https://weixin.qq.com/s/abc",              // 同一家的别的主机也不收
      "https://t.cn/abc",                         // 微博短链会展开到任意站，不收
      "https://m.tb.cn/h.TBxLfpbarZpWrRH",        // 淘宝短链是 JS 跳转页，不收
      "https://www.taobao.com/",                  // 只收商品页主机
      "https://login.taobao.com/",
      "https://qishui.douyin.com/x",              // 汽水音乐是另一个品牌
      "https://www.baidu.com/",
      "https://127.0.0.1/",
      "https://zh.wikipedia.org/wiki/中国",       // 非 ASCII：客户端要先编码
      "https://mp.weixin.qq.com/s/a b",
      "mp.weixin.qq.com/s/abc",
      "javascript:alert(1)",
      "",
  })
  void rejectsEverythingElse(final String url) {
    assertFalse(LinkPreviewUrls.parse(url).isPresent(), url);
  }

  @Test
  void tooLongIsRejected() {
    assertFalse(LinkPreviewUrls.parse("https://mp.weixin.qq.com/s/" + "a".repeat(2048)).isPresent());
  }

  @Test
  void stripsFragmentAndSharerParams() {
    final URI uri = LinkPreviewUrls.parse(
        "https://mp.weixin.qq.com/s?__biz=MTI0&mid=265&idx=1&sn=490d&chksm=abc&sharer_shareid=me&scene=1&utm_source=x#rd")
        .orElseThrow().uri();
    assertEquals("https://mp.weixin.qq.com/s?__biz=MTI0&mid=265&idx=1&sn=490d", uri.toString());

    final URI tb = LinkPreviewUrls.parse("https://item.taobao.com/item.htm?spm=a1&id=674169489573&ali_trackid=x&un=me")
        .orElseThrow().uri();
    assertEquals("https://item.taobao.com/item.htm?id=674169489573", tb.toString());

    final URI xhs = LinkPreviewUrls.parse(
        "https://www.xiaohongshu.com/discovery/item/67b97405000000002503ed8f?xsec_token=T&xsec_source=app_share")
        .orElseThrow().uri();
    assertEquals("https://www.xiaohongshu.com/discovery/item/67b97405000000002503ed8f", xhs.toString());
  }

  @Test
  void cacheKeyIgnoresWhoSharedAndDiffersPerObject() {
    final String a = LinkPreviewUrls.cacheKey(LinkPreviewUrls.parse(
        "https://mp.weixin.qq.com/s/abcdefabcdefabcdef?sharer_shareid=alice").orElseThrow().uri());
    final String b = LinkPreviewUrls.cacheKey(LinkPreviewUrls.parse(
        "https://mp.weixin.qq.com/s/abcdefabcdefabcdef?sharer_shareid=bob#x").orElseThrow().uri());
    final String c = LinkPreviewUrls.cacheKey(LinkPreviewUrls.parse(
        "https://mp.weixin.qq.com/s/zzzzzzzzzzzzzzzzzz").orElseThrow().uri());
    assertEquals(a, b);
    assertNotEquals(a, c);
    assertEquals(64, a.length());
    assertFalse(a.contains("weixin"));
  }

  @Test
  void imageMustBeOnTheProvidersImageHosts() {
    final URI page = URI.create("https://mp.weixin.qq.com/s/abc");
    final LinkPreviewProviders.Provider wx = LinkPreviewProviders.WEIXIN_MP;
    assertEquals("https://mmbiz.qpic.cn/mmbiz_jpg/x/0?wx_fmt=jpeg",
        LinkPreviewUrls.imageUri(wx, page, "https://mmbiz.qpic.cn/mmbiz_jpg/x/0?wx_fmt=jpeg").orElseThrow().toString());
    assertEquals("https://mmbiz.qpic.cn/a.jpg",
        LinkPreviewUrls.imageUri(wx, page, "http://mmbiz.qpic.cn/a.jpg").orElseThrow().toString());
    assertEquals("https://mmbiz.qpic.cn/a.jpg",
        LinkPreviewUrls.imageUri(wx, page, "//mmbiz.qpic.cn/a.jpg").orElseThrow().toString());
    assertFalse(LinkPreviewUrls.imageUri(wx, page, "https://evil.com/a.jpg").isPresent());
    assertFalse(LinkPreviewUrls.imageUri(wx, page, "/a.jpg").isPresent());        // 页面主机不是图片主机
    assertFalse(LinkPreviewUrls.imageUri(wx, page, "https://10.0.0.1/a.jpg").isPresent());
    assertFalse(LinkPreviewUrls.imageUri(wx, page, "https://mmbiz.qpic.cn:8080/a.jpg").isPresent());
    assertFalse(LinkPreviewUrls.imageUri(wx, page, "data:image/png;base64,AAAA").isPresent());
    assertTrue(LinkPreviewUrls.imageUri(LinkPreviewProviders.WEIBO, URI.create("https://weibo.com/x"),
        "https://n.sinaimg.cn/a.jpg").isPresent());
    assertTrue(LinkPreviewUrls.imageUri(LinkPreviewProviders.ZHIHU, URI.create("https://www.zhihu.com/x"),
        "https://pic1.zhimg.com/v2-a.jpg").isPresent());
    assertFalse(LinkPreviewUrls.imageUri(LinkPreviewProviders.ZHIHU, URI.create("https://www.zhihu.com/x"),
        "https://evilzhimg.com/a.jpg").isPresent());
    assertTrue(LinkPreviewUrls.imageUri(LinkPreviewProviders.TMALL, URI.create("https://detail.tmall.com/item.htm"),
        "//img.alicdn.com/imgextra/i1/x.jpg").isPresent());
    assertTrue(LinkPreviewUrls.imageUri(LinkPreviewProviders.DOUYIN, URI.create("https://www.douyin.com/video/1"),
        "https://p3-pc-sign.douyinpic.com/a.jpeg?x=1").isPresent());
    // 每家只认自己的图床
    assertFalse(LinkPreviewUrls.imageUri(LinkPreviewProviders.DOUYIN, URI.create("https://www.douyin.com/video/1"),
        "https://img.alicdn.com/a.jpg").isPresent());
  }

  @Test
  void whitelistIsExactlyTheApprovedProviders() {
    assertEquals(java.util.List.of("weixin-mp", "zhihu", "weibo", "xiaohongshu", "douyin", "taobao", "tmall"),
        LinkPreviewProviders.ALL.stream().map(LinkPreviewProviders.Provider::id).toList());
  }
}
