/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.whispersystems.textsecuregcm.configuration.TellomiLinkPreviewConfiguration;
import org.whispersystems.textsecuregcm.controllers.TellomiLinkPreviewController;

class LinkPreviewServiceTest {

  private static final String WX = "https://mp.weixin.qq.com/s/_chjr0XiTqoDg3Qg6JzU_g";

  private static LinkPreviewService service(final LinkPreviewFetcherTest.FakeTransport t, final Integer concurrency) {
    return new LinkPreviewService(
        new LinkPreviewFetcher(t, host -> new InetAddress[]{InetAddress.getByName("203.119.1.1")}, Clock.systemUTC()),
        new TellomiLinkPreviewConfiguration(true, null, null, null, concurrency));
  }

  @Test
  void secondAskIsServedFromTheCacheEvenWithOtherSharerParams() {
    final LinkPreviewFetcherTest.FakeTransport t = new LinkPreviewFetcherTest.FakeTransport();
    t.html(WX, "<meta property=\"og:title\" content=\"t\">");
    final LinkPreviewService s = service(t, null);
    assertInstanceOf(LinkPreviewService.Answer.Found.class, s.preview(LinkPreviewUrls.parse(WX + "?sharer_shareid=a").orElseThrow()));
    assertInstanceOf(LinkPreviewService.Answer.Found.class, s.preview(LinkPreviewUrls.parse(WX + "?sharer_shareid=b").orElseThrow()));
    assertEquals(1, t.requested.size());
  }

  @Test
  void failuresAreCachedBriefly() {
    final LinkPreviewFetcherTest.FakeTransport t = new LinkPreviewFetcherTest.FakeTransport();
    final LinkPreviewService s = service(t, null);
    final LinkPreviewUrls.Target target = LinkPreviewUrls.parse("https://www.zhihu.com/question/1").orElseThrow();
    assertInstanceOf(LinkPreviewService.Answer.NotAvailable.class, s.preview(target));
    assertInstanceOf(LinkPreviewService.Answer.NotAvailable.class, s.preview(target));
    assertEquals(1, t.requested.size());
    assertFalse(s.cachedForTest(target).isPresent());
  }

  @Test
  void busyWhenAllPermitsAreTaken() throws Exception {
    final CountDownLatch inside = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final LinkPreviewFetcherTest.FakeTransport t = new LinkPreviewFetcherTest.FakeTransport() {
      @Override
      public Response get(final java.net.URI uri, final String ua, final String accept, final int max,
          final java.time.Duration timeout, final String stop) throws java.io.IOException {
        inside.countDown();
        try {
          release.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return new Response(404, null, null, new byte[0], false);
      }
    };
    final LinkPreviewService s = service(t, 1);
    final AtomicReference<LinkPreviewService.Answer> first = new AtomicReference<>();
    final Thread th = new Thread(() -> first.set(s.preview(LinkPreviewUrls.parse(WX).orElseThrow())));
    th.start();
    inside.await();
    assertInstanceOf(LinkPreviewService.Answer.Busy.class,
        s.preview(LinkPreviewUrls.parse("https://www.zhihu.com/question/2").orElseThrow()));
    release.countDown();
    th.join();
    assertInstanceOf(LinkPreviewService.Answer.NotAvailable.class, first.get());
  }

  /// 隐私规则的一道机械检查：这几个类不许有 logger 字段（不写日志 = 不会把 URL 写进日志）
  @Test
  void noLoggersInTheLinkPreviewPath() {
    for (final Class<?> c : new Class<?>[]{LinkPreviewService.class, LinkPreviewFetcher.class, LinkPreviewUrls.class,
        LinkPreviewTransport.Jdk.class, HtmlMeta.class, ImageThumbnailer.class, AddressGuard.class,
        TellomiLinkPreviewController.class}) {
      for (final Field f : c.getDeclaredFields()) {
        assertFalse(f.getType().getName().startsWith("org.slf4j") && Modifier.isStatic(f.getModifiers()),
            c.getSimpleName() + "." + f.getName());
        assertFalse(f.getType().getName().startsWith("org.slf4j"), c.getSimpleName() + "." + f.getName());
      }
    }
    assertTrue(true);
  }
}
