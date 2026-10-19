/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.whispersystems.textsecuregcm.configuration.TellomiLinkPreviewConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinkPreviewFetcherTest {

  private static final String WX = "https://mp.weixin.qq.com/s/_chjr0XiTqoDg3Qg6JzU_g";
  private static final String WX_IMG = "https://mmbiz.qpic.cn/mmbiz_jpg/abc/0?wx_fmt=jpeg";

  /// 记下每个请求（URL 和 UA），按 URL 回预设的响应
  static class FakeTransport implements LinkPreviewTransport {
    final Map<String, Object> routes = new HashMap<>();
    /// 只对 Safari UA 生效的响应（没有就用 routes）
    final Map<String, Object> safariRoutes = new HashMap<>();
    final List<String> requested = new ArrayList<>();
    final List<String> userAgents = new ArrayList<>();

    @Override
    public Response get(final URI uri, final String userAgent, final String accept, final int maxBytes,
        final Duration timeout, final String stopAfter) throws IOException {
      requested.add(uri.toString());
      userAgents.add(userAgent);
      final Object r = LinkPreviewProviders.SAFARI_UA.equals(userAgent) && safariRoutes.containsKey(uri.toString())
          ? safariRoutes.get(uri.toString())
          : routes.get(uri.toString());
      if (r instanceof IOException e) {
        throw e;
      }
      if (r == null) {
        return new Response(404, null, "text/html", new byte[0], false);
      }
      final Response resp = (Response) r;
      if (resp.status() >= 200 && resp.status() < 300) {
        return LinkPreviewTransport.readLimited(resp.status(), resp.location(), resp.contentType(),
            new ByteArrayInputStream(resp.body()), maxBytes, stopAfter);
      }
      return resp;
    }

    void html(final String url, final String body) {
      routes.put(url, new Response(200, null, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), false));
    }

    void redirect(final String url, final String location) {
      routes.put(url, new Response(302, location, null, new byte[0], false));
    }

    void image(final String url, final byte[] bytes) {
      routes.put(url, new Response(200, null, "image/jpeg", bytes, false));
    }

    void safariHtml(final String url, final String body) {
      safariRoutes.put(url, new Response(200, null, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), false));
    }

    void status(final String url, final int status) {
      routes.put(url, new Response(status, null, "text/html", new byte[0], false));
    }

    long pageRequests(final String url) {
      return requested.stream().filter(url::equals).count();
    }
  }

  private FakeTransport transport;
  private Map<String, String> dns;
  private LinkPreviewFetcher fetcher;

  @BeforeEach
  void setUp() {
    transport = new FakeTransport();
    dns = new HashMap<>();
    fetcher = new LinkPreviewFetcher(transport,
        host -> new InetAddress[]{InetAddress.getByName(dns.getOrDefault(host, "203.119.1.1"))},
        Clock.systemUTC());
  }

  static byte[] png(final int w, final int h) throws IOException {
    final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  private LinkPreviewFetcher.Result fetch(final String url) {
    return fetcher.fetch(LinkPreviewUrls.parse(url).orElseThrow());
  }

  @Test
  void weixinArticleGivesTitleAndThumbnailWithSafariUa() throws Exception {
    transport.html(WX, "<head><meta property=\"og:title\" content=\"标题\"><meta property=\"og:image\" content=\""
        + WX_IMG + "\"></head><body>" + "x".repeat(10_000) + "</body>");
    transport.image(WX_IMG, png(1600, 900));
    final LinkPreviewFetcher.Result result = fetch(WX);
    assertEquals(LinkPreviewFetcher.Outcome.OK, result.outcome());
    assertEquals("标题", result.card().title());
    assertEquals("mp.weixin.qq.com", result.card().domain());
    assertEquals("weixin-mp", result.card().provider());
    assertNotNull(result.card().image());
    assertEquals(800, result.card().image().width());
    assertEquals(450, result.card().image().height());
    assertEquals("image/jpeg", result.card().image().contentType());
    assertEquals(List.of(WX, WX_IMG), transport.requested);
    assertTrue(transport.userAgents.stream().allMatch(LinkPreviewProviders.SAFARI_UA::equals));
  }

  @Test
  void otherProvidersKeepTheWhatsAppUa() {
    transport.html("https://weibo.com/2/detail/comos:abc", "<meta property=\"og:title\" content=\"微博文章\">");
    assertEquals(LinkPreviewFetcher.Outcome.OK, fetch("https://weibo.com/2/detail/comos:abc").outcome());
    assertEquals(List.of("WhatsApp/2"), transport.userAgents);
  }

  @Test
  void sharerParamsAreNotSentToTheSite() {
    transport.html(WX, "<meta property=\"og:title\" content=\"t\">");
    fetch(WX + "?sharer_shareid=alice&scene=1#rd");
    assertEquals(List.of(WX), transport.requested);
  }

  @Test
  void noImageStillGivesATextCard() {
    transport.html(WX, "<meta property=\"og:title\" content=\"只有标题\">");
    final LinkPreviewFetcher.Result result = fetch(WX);
    assertEquals(LinkPreviewFetcher.Outcome.OK, result.outcome());
    assertNull(result.card().image());
  }

  @Test
  void imageOffTheWhitelistIsNeverRequested() {
    transport.html(WX, "<meta property=\"og:title\" content=\"t\"><meta property=\"og:image\" content=\"https://tracker.example/p.png\">");
    final LinkPreviewFetcher.Result result = fetch(WX);
    assertNull(result.card().image());
    assertEquals(List.of(WX), transport.requested);
  }

  @Test
  void redirectInsideTheProviderIsFollowed() {
    transport.redirect("https://xhslink.com/o/8iQbOIYV4IX",
        "https://www.xiaohongshu.com/discovery/item/67b97405000000002503ed8f?xsec_token=T");
    transport.html("https://www.xiaohongshu.com/discovery/item/67b97405000000002503ed8f",
        "<meta property=\"og:title\" content=\"笔记标题\">");
    final LinkPreviewFetcher.Result result = fetch("https://xhslink.com/o/8iQbOIYV4IX");
    assertEquals(LinkPreviewFetcher.Outcome.OK, result.outcome());
    assertEquals("xhslink.com", result.card().domain());
  }

  @Test
  void weiboVisitorWallIsNotPassed() {
    transport.redirect("https://m.weibo.cn/status/4154099588849643",
        "https://visitor.passport.weibo.cn/visitor/visitor?entry=sinawap");
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER, fetch("https://m.weibo.cn/status/4154099588849643").outcome());
    // 默认 UA 一次、Safari 重试一次；两次都只请求白名单里的那一页，访客页本身从不请求
    assertEquals(List.of("https://m.weibo.cn/status/4154099588849643", "https://m.weibo.cn/status/4154099588849643"),
        transport.requested);
  }

  @Test
  void redirectToAnotherProviderOrToHttpOrToAnIpIsRejected() {
    transport.redirect(WX, "https://www.zhihu.com/question/1");
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER, fetch(WX).outcome());
    transport.redirect(WX, "http://mp.weixin.qq.com/s/x");
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER, fetch(WX).outcome());
    transport.redirect(WX, "https://169.254.169.254/latest/meta-data/");
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER, fetch(WX).outcome());
  }

  @Test
  void captchaAndLoginPathsCountAsUnavailable() {
    transport.redirect(WX, "/mp/wappoc_appmsgcaptcha?poc_token=x");
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER, fetch(WX).outcome());
    transport.redirect("https://www.xiaohongshu.com/explore/6858ccaa0000000013013c94", "/404/sec_abc?x=1");
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER,
        fetch("https://www.xiaohongshu.com/explore/6858ccaa0000000013013c94").outcome());
  }

  @Test
  void tooManyRedirectsStop() {
    for (int i = 0; i < 10; i++) {
      transport.redirect("https://www.zhihu.com/r" + i, "/r" + (i + 1));
    }
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER, fetch("https://www.zhihu.com/r0").outcome());
    // 每一轮最多 MAX_REDIRECTS + 1 个请求；默认 UA 一轮、Safari 重试一轮
    assertEquals(2 * (LinkPreviewFetcher.MAX_REDIRECTS + 1), transport.requested.size());
  }

  @Test
  void privateAddressIsNeverRequested() {
    dns.put("mp.weixin.qq.com", "10.0.0.5");
    assertEquals(LinkPreviewFetcher.Outcome.FORBIDDEN_ADDRESS, fetch(WX).outcome());
    assertTrue(transport.requested.isEmpty());
  }

  @Test
  void privateImageAddressDropsOnlyTheImage() throws Exception {
    dns.put("mmbiz.qpic.cn", "100.100.100.200");
    transport.html(WX, "<meta property=\"og:title\" content=\"t\"><meta property=\"og:image\" content=\"" + WX_IMG + "\">");
    transport.image(WX_IMG, png(400, 400));
    final LinkPreviewFetcher.Result result = fetch(WX);
    assertEquals(LinkPreviewFetcher.Outcome.OK, result.outcome());
    assertNull(result.card().image());
    assertEquals(List.of(WX), transport.requested);
  }

  @Test
  void genericSiteTitlesAreNotACard() {
    transport.html("https://www.zhihu.com/question/1", "<title>知乎 - 有问题，就会有答案</title>");
    assertEquals(LinkPreviewFetcher.Outcome.NO_TITLE, fetch("https://www.zhihu.com/question/1").outcome());
    transport.html("https://weibo.com/u/1234567", "<title>Sina Visitor System</title>");
    assertEquals(LinkPreviewFetcher.Outcome.NO_TITLE, fetch("https://weibo.com/u/1234567").outcome());
  }

  @Test
  void douyinShellTitleIsGeneric() {
    transport.redirect("https://v.douyin.com/8rmc2PU/", "https://www.iesdouyin.com/share/video/6957891939144322307/?region=CN&u_code=x");
    transport.html("https://www.iesdouyin.com/share/video/6957891939144322307/", "<title>在抖音记录美好生活20261019 - 抖音</title>");
    final LinkPreviewFetcher.Result r = fetch("https://v.douyin.com/8rmc2PU/");
    assertEquals(LinkPreviewFetcher.Outcome.NO_TITLE, r.outcome());
    // 去参数后的地址才发出去（u_code 能认出分享者）；默认 UA 一轮、Safari 重试一轮
    final List<String> oneRound = List.of("https://v.douyin.com/8rmc2PU/", "https://www.iesdouyin.com/share/video/6957891939144322307/");
    final List<String> both = new ArrayList<>(oneRound);
    both.addAll(oneRound);
    assertEquals(both, transport.requested);
    assertEquals(2, r.attempts());
  }

  @Test
  void taobaoToOverseasOrLoginIsUnavailable() {
    transport.redirect("https://item.taobao.com/item.htm?id=674169489573", "http://m.intl.taobao.com/detail/detail.html?id=674169489573");
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER, fetch("https://item.taobao.com/item.htm?id=674169489573").outcome());
    transport.redirect("https://detail.tmall.com/item.htm?id=1", "https://login.taobao.com/member/login.jhtml");
    assertEquals(LinkPreviewFetcher.Outcome.LEFT_PROVIDER, fetch("https://detail.tmall.com/item.htm?id=1").outcome());
  }

  @Test
  void tmallItemGivesACard() throws Exception {
    transport.redirect("https://detail.tmall.com/item.htm?id=1", "https://detail.m.tmall.com/item.htm?id=1");
    transport.html("https://detail.m.tmall.com/item.htm?id=1",
        "<title>某某旗舰店 新款外套-tmall.com天猫</title><meta property=\"og:image\" content=\"//img.alicdn.com/i1/a.jpg\">");
    transport.image("https://img.alicdn.com/i1/a.jpg", png(600, 600));
    final LinkPreviewFetcher.Result r = fetch("https://detail.tmall.com/item.htm?id=1&spm=x");
    assertEquals(LinkPreviewFetcher.Outcome.OK, r.outcome());
    assertEquals("detail.tmall.com", r.card().domain());
    assertEquals("tmall", r.card().provider());
    assertNotNull(r.card().image());
  }

  @Test
  void titleTagIsTheFallback() {
    transport.html("https://zhuanlan.zhihu.com/p/74424675", "<title>一篇专栏 - 知乎</title>");
    assertEquals("一篇专栏 - 知乎", fetch("https://zhuanlan.zhihu.com/p/74424675").card().title());
  }

  @Test
  void bigPagesAreReadOnlyToTheHead() {
    transport.html(WX, "<head><meta property=\"og:title\" content=\"t\"></head>" + "x".repeat(3 * 1024 * 1024));
    assertEquals(LinkPreviewFetcher.Outcome.OK, fetch(WX).outcome());
  }

  @Test
  void statusAndTypeAndNetworkFailures() {
    transport.routes.put(WX, new LinkPreviewTransport.Response(403, null, "text/html", new byte[0], false));
    assertEquals(LinkPreviewFetcher.Outcome.HTTP_STATUS, fetch(WX).outcome());
    transport.routes.put(WX, new LinkPreviewTransport.Response(200, null, "application/json", "{}".getBytes(), false));
    assertEquals(LinkPreviewFetcher.Outcome.NOT_HTML, fetch(WX).outcome());
    transport.routes.put(WX, new HttpTimeoutException("t"));
    assertEquals(LinkPreviewFetcher.Outcome.TIMEOUT, fetch(WX).outcome());
    transport.routes.put(WX, new IOException("reset"));
    assertEquals(LinkPreviewFetcher.Outcome.NETWORK, fetch(WX).outcome());
  }

  @Test
  void oversizedImageIsDropped() {
    transport.html(WX, "<meta property=\"og:title\" content=\"t\"><meta property=\"og:image\" content=\"" + WX_IMG + "\">");
    transport.image(WX_IMG, new byte[LinkPreviewFetcher.MAX_IMAGE_BYTES + 1]);
    assertNull(fetch(WX).card().image());
  }

  @Test
  void readLimitedStopsAtTheMarker() throws Exception {
    final byte[] body = "<HEAD>a</HeAd>bbbb".getBytes(StandardCharsets.US_ASCII);
    final LinkPreviewTransport.Response r = LinkPreviewTransport.readLimited(200, null, null,
        new ByteArrayInputStream(body), 1000, "</head>");
    assertEquals("<HEAD>a</HeAd>", new String(r.body(), StandardCharsets.US_ASCII));
    assertTrue(r.truncated());
    final LinkPreviewTransport.Response capped = LinkPreviewTransport.readLimited(200, null, null,
        new ByteArrayInputStream(body), 4, null);
    assertEquals(4, capped.body().length);
    assertTrue(capped.truncated());
    final LinkPreviewTransport.Response whole = LinkPreviewTransport.readLimited(200, null, null,
        new ByteArrayInputStream(body), 1000, null);
    assertFalse(whole.truncated());
  }

  // ---- 换 UA 重试一次（产品经理 2026-10-19 (4)）----

  private static final String ZH = "https://www.zhihu.com/question/640359835";
  private static final String ZH_IMG = "https://pic1.zhimg.com/v2-abc.jpg";

  @Test
  void noTitleWithTheDefaultUaThenSafariGivesTheCard() throws Exception {
    transport.html(ZH, "<title>知乎 - 有问题，就会有答案</title>");
    transport.safariHtml(ZH, "<meta property=\"og:title\" content=\"真问题\"><meta property=\"og:image\" content=\"" + ZH_IMG + "\">");
    transport.image(ZH_IMG, png(400, 300));
    final LinkPreviewFetcher.Result r = fetch(ZH);
    assertEquals(LinkPreviewFetcher.Outcome.OK, r.outcome());
    assertEquals("真问题", r.card().title());
    assertNotNull(r.card().image());
    assertEquals(LinkPreviewFetcher.UserAgentUsed.SAFARI_RETRY, r.userAgentUsed());
    assertEquals(2, r.attempts());
    assertEquals(List.of(ZH, ZH, ZH_IMG), transport.requested);
    assertEquals(List.of("WhatsApp/2", LinkPreviewProviders.SAFARI_UA, LinkPreviewProviders.SAFARI_UA), transport.userAgents,
        "图片用抓成功那次的 UA");
  }

  @Test
  void a403WithTheDefaultUaThenSafariGivesTheCard() {
    transport.status(ZH, 403);
    transport.safariHtml(ZH, "<title>一个回答 - 知乎</title>");
    final LinkPreviewFetcher.Result r = fetch(ZH);
    assertEquals(LinkPreviewFetcher.Outcome.OK, r.outcome());
    assertEquals("一个回答 - 知乎", r.card().title());
    assertEquals(LinkPreviewFetcher.UserAgentUsed.SAFARI_RETRY, r.userAgentUsed());
  }

  @Test
  void aLoginRedirectWithTheDefaultUaThenSafariGivesTheCard() {
    final String item = "https://detail.tmall.com/item.htm?id=1";
    transport.redirect(item, "https://login.taobao.com/member/login.jhtml");
    transport.safariHtml(item, "<title>某某旗舰店 新款外套-tmall.com天猫</title>");
    final LinkPreviewFetcher.Result r = fetch(item);
    assertEquals(LinkPreviewFetcher.Outcome.OK, r.outcome());
    assertEquals(LinkPreviewFetcher.UserAgentUsed.SAFARI_RETRY, r.userAgentUsed());
  }

  @Test
  void bothUasFailMeansNoCardSoTheClientShowsTheBrandShell() {
    transport.status(ZH, 403);
    final LinkPreviewFetcher.Result r = fetch(ZH);
    assertEquals(LinkPreviewFetcher.Outcome.HTTP_STATUS, r.outcome());
    assertNull(r.card());
    assertEquals(LinkPreviewFetcher.UserAgentUsed.SAFARI_RETRY, r.userAgentUsed());
    assertEquals(2, r.attempts());

    final LinkPreviewService service = new LinkPreviewService(fetcher,
        new TellomiLinkPreviewConfiguration(true, null, null, null, null));
    final String other = "https://zhuanlan.zhihu.com/p/1";
    transport.html(other, "<title>知乎 - 有问题，就会有答案</title>");
    assertTrue(service.preview(LinkPreviewUrls.parse(other).orElseThrow()) instanceof LinkPreviewService.Answer.NotAvailable,
        "两次都失败 = 没有卡片（204），客户端出品牌壳");
  }

  @Test
  void theRetryHappensExactlyOnce() {
    transport.status(ZH, 403);
    fetch(ZH);
    assertEquals(2, transport.pageRequests(ZH), "默认 UA 一次 + Safari 一次，不再多");
    assertEquals(List.of("WhatsApp/2", LinkPreviewProviders.SAFARI_UA), transport.userAgents);

    transport.requested.clear();
    transport.userAgents.clear();
    transport.html(ZH, "<title>知乎</title>");
    final LinkPreviewFetcher.Result r = fetch(ZH);
    assertEquals(LinkPreviewFetcher.Outcome.NO_TITLE, r.outcome());
    assertEquals(2, transport.pageRequests(ZH));
  }

  @Test
  void noRetryWhenTheDefaultUaWorksOrIsAlreadySafari() {
    transport.html(ZH, "<title>一个回答 - 知乎</title>");
    final LinkPreviewFetcher.Result ok = fetch(ZH);
    assertEquals(LinkPreviewFetcher.UserAgentUsed.DEFAULT, ok.userAgentUsed());
    assertEquals(1, ok.attempts());
    assertEquals(1, transport.pageRequests(ZH));

    transport.html(WX, "<title>微信公众平台</title>");
    final LinkPreviewFetcher.Result wx = fetch(WX);
    assertEquals(LinkPreviewFetcher.Outcome.NO_TITLE, wx.outcome());
    assertEquals(1, wx.attempts(), "公众号默认就是 Safari，不重试");
    assertEquals(1, transport.pageRequests(WX));
  }

  @Test
  void noRetryForOtherFailures() {
    transport.status(ZH, 404);
    assertEquals(1, fetch(ZH).attempts(), "404 不重试");
    transport.routes.put(ZH, new IOException("reset"));
    assertEquals(LinkPreviewFetcher.Outcome.NETWORK, fetch(ZH).outcome());
    assertEquals(1, fetch(ZH).attempts(), "网络错误不重试");
    dns.put("www.zhihu.com", "10.0.0.1");
    final LinkPreviewFetcher.Result forbidden = fetch(ZH);
    assertEquals(LinkPreviewFetcher.Outcome.FORBIDDEN_ADDRESS, forbidden.outcome());
    assertEquals(1, forbidden.attempts(), "内网地址不重试");
  }

  @Test
  void retryRulesAsATable() {
    final LinkPreviewProviders.Provider zhihu = LinkPreviewProviders.ZHIHU;
    assertTrue(LinkPreviewFetcher.shouldRetryWithSafari(zhihu, LinkPreviewFetcher.Outcome.NO_TITLE, 200));
    assertTrue(LinkPreviewFetcher.shouldRetryWithSafari(zhihu, LinkPreviewFetcher.Outcome.LEFT_PROVIDER, 302));
    assertTrue(LinkPreviewFetcher.shouldRetryWithSafari(zhihu, LinkPreviewFetcher.Outcome.HTTP_STATUS, 403));
    assertFalse(LinkPreviewFetcher.shouldRetryWithSafari(zhihu, LinkPreviewFetcher.Outcome.HTTP_STATUS, 404));
    assertFalse(LinkPreviewFetcher.shouldRetryWithSafari(zhihu, LinkPreviewFetcher.Outcome.HTTP_STATUS, 500));
    assertFalse(LinkPreviewFetcher.shouldRetryWithSafari(zhihu, LinkPreviewFetcher.Outcome.TIMEOUT, 0));
    assertFalse(LinkPreviewFetcher.shouldRetryWithSafari(zhihu, LinkPreviewFetcher.Outcome.NOT_HTML, 200));
    assertFalse(LinkPreviewFetcher.shouldRetryWithSafari(LinkPreviewProviders.WEIXIN_MP, LinkPreviewFetcher.Outcome.NO_TITLE, 200));
    assertFalse(LinkPreviewFetcher.shouldRetryWithSafari(zhihu, LinkPreviewFetcher.Outcome.OK, 200));
  }

  private static final String TB = "https://item.taobao.com/item.htm?id=674169489573";
  private static final String TB_IMG = "https://img.alicdn.com/i1/a.jpg";

  @Test
  void aPlaceholderTitleWithoutAnImageAfterTheSafariRetryIsNoCard() {
    // 2026-10-19 成都出口：WhatsApp/2 没标题，Safari 拿到 `<title>商品详情页</title>`、没有图
    transport.html(TB, "<head></head>");
    transport.safariHtml(TB, "<title>商品详情页</title>");
    final LinkPreviewFetcher.Result r = fetch(TB);
    assertEquals(LinkPreviewFetcher.Outcome.NO_TITLE, r.outcome());
    assertNull(r.card());
    assertEquals(LinkPreviewFetcher.UserAgentUsed.SAFARI_RETRY, r.userAgentUsed());
    assertEquals(2, r.attempts());
    assertEquals(2, transport.pageRequests(TB), "只重试一次");

    final LinkPreviewService service = new LinkPreviewService(fetcher,
        new TellomiLinkPreviewConfiguration(true, null, null, null, null));
    final String other = "https://detail.tmall.com/item.htm?id=540349415064";
    transport.html(other, "<title> 商品详情页 </title>");
    assertTrue(service.preview(LinkPreviewUrls.parse(other).orElseThrow()) instanceof LinkPreviewService.Answer.NotAvailable,
        "占位标题 + 没图 = 没有卡片（204），客户端出品牌壳");
  }

  @Test
  void aPlaceholderTitleWithTheDefaultUaTriggersTheSafariRetry() {
    transport.html(TB, "<title>商品详情页</title>");
    transport.safariHtml(TB, "<title>某某店 新款外套-淘宝网</title>");
    final LinkPreviewFetcher.Result r = fetch(TB);
    assertEquals(LinkPreviewFetcher.Outcome.OK, r.outcome());
    assertEquals("某某店 新款外套-淘宝网", r.card().title());
    assertEquals(LinkPreviewFetcher.UserAgentUsed.SAFARI_RETRY, r.userAgentUsed());
    assertEquals(List.of("WhatsApp/2", LinkPreviewProviders.SAFARI_UA), transport.userAgents);
  }

  @Test
  void aPlaceholderTitleWithARealImageIsStillACard() throws Exception {
    transport.html(TB, "<title>商品详情页</title><meta property=\"og:image\" content=\"" + TB_IMG + "\">");
    transport.image(TB_IMG, png(800, 800));
    final LinkPreviewFetcher.Result r = fetch(TB);
    assertEquals(LinkPreviewFetcher.Outcome.OK, r.outcome());
    assertEquals("商品详情页", r.card().title());
    assertNotNull(r.card().image());
    assertEquals(1, r.attempts());
  }

  @Test
  void aPlaceholderTitleWhoseImageFailsCountsAsNoTitle() {
    transport.html(TB, "<title>商品详情页</title><meta property=\"og:image\" content=\"" + TB_IMG + "\">");
    // 图片 404（FakeTransport 默认）
    final LinkPreviewFetcher.Result r = fetch(TB);
    assertEquals(LinkPreviewFetcher.Outcome.NO_TITLE, r.outcome());
    assertEquals(2, r.attempts());
  }

  @Test
  void placeholderTitlesArePerProvider() {
    assertTrue(LinkPreviewProviders.TAOBAO.isPlaceholderTitle("商品详情页"));
    assertTrue(LinkPreviewProviders.TAOBAO.isPlaceholderTitle("  商品详情页\n"));
    assertTrue(LinkPreviewProviders.TMALL.isPlaceholderTitle("商品详情页"));
    assertFalse(LinkPreviewProviders.TAOBAO.isPlaceholderTitle("商品详情页 - 某某旗舰店新款外套"));
    assertFalse(LinkPreviewProviders.ZHIHU.isPlaceholderTitle("商品详情页"));
    assertFalse(LinkPreviewProviders.WEIXIN_MP.isPlaceholderTitle("商品详情页"));
    for (final LinkPreviewProviders.Provider p : LinkPreviewProviders.ALL) {
      if (p != LinkPreviewProviders.TAOBAO && p != LinkPreviewProviders.TMALL) {
        assertTrue(p.placeholderTitles().isEmpty(), p.id());
      }
    }
  }
}
