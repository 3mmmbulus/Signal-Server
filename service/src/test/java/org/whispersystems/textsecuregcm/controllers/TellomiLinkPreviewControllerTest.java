/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.configuration.TellomiLinkPreviewConfiguration;
import org.whispersystems.textsecuregcm.limits.RateLimiter;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.mappers.RateLimitExceededExceptionMapper;
import org.whispersystems.textsecuregcm.tellomi.linkpreview.LinkPreviewFetcher;
import org.whispersystems.textsecuregcm.tellomi.linkpreview.LinkPreviewService;
import org.whispersystems.textsecuregcm.tellomi.linkpreview.LinkPreviewTransport;
import org.whispersystems.textsecuregcm.tests.util.AuthHelper;
import org.whispersystems.textsecuregcm.util.SystemMapper;

@ExtendWith(DropwizardExtensionsSupport.class)
class TellomiLinkPreviewControllerTest {

  private static final String WX = "https://mp.weixin.qq.com/s/_chjr0XiTqoDg3Qg6JzU_g";
  private static final String WX_IMG = "https://mmbiz.qpic.cn/a/0?wx_fmt=jpeg";

  private static final RateLimiters rateLimiters = mock(RateLimiters.class);
  private static final RateLimiter limiter = mock(RateLimiter.class);
  private static final LinkPreviewTransport transport = mock(LinkPreviewTransport.class);

  private static final ResourceExtension resources = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new RateLimitExceededExceptionMapper())
      .addProvider(new AuthValueFactoryProvider.Binder<>(AuthenticatedDevice.class))
      .setMapper(SystemMapper.jsonMapper())
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addResource(new TellomiLinkPreviewController(rateLimiters,
          new LinkPreviewService(new LinkPreviewFetcher(transport,
              host -> new InetAddress[]{InetAddress.getByName("203.119.1.1")}, Clock.systemUTC()),
              // 缓存设 0：每个测试都真的走一遍抓取
              new TellomiLinkPreviewConfiguration(true, Duration.ZERO, Duration.ZERO, 0, null)),
          Runnable::run))
      .build();

  @BeforeEach
  void setUp() throws Exception {
    reset(rateLimiters, limiter, transport);
    when(rateLimiters.getTellomiLinkPreviewLimiter()).thenReturn(limiter);
    when(transport.get(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(new LinkPreviewTransport.Response(404, null, null, new byte[0], false));
  }

  private Response post(final Object body) {
    return resources.getJerseyTest()
        .target("/v1/tellomi/link-preview")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .post(Entity.entity(body, MediaType.APPLICATION_JSON_TYPE));
  }

  private void page(final String url, final String html) throws Exception {
    when(transport.get(org.mockito.ArgumentMatchers.eq(java.net.URI.create(url)), any(), any(),
        org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(new LinkPreviewTransport.Response(200, null, "text/html", html.getBytes(), false));
  }

  @Test
  void returnsOnlyTitleImageAndDomain() throws Exception {
    page(WX, "<meta property=\"og:title\" content=\"标题\"><meta property=\"og:description\" content=\"不该出现\">"
        + "<meta property=\"og:image\" content=\"" + WX_IMG + "\">");
    when(transport.get(org.mockito.ArgumentMatchers.eq(java.net.URI.create(WX_IMG)), any(), any(),
        org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenReturn(new LinkPreviewTransport.Response(200, null, "image/png",
            org.whispersystems.textsecuregcm.tellomi.linkpreview.LinkPreviewFetcherTestSupport.png(1000, 500), false));

    try (final Response response = post(Map.of("url", WX + "?sharer_shareid=me"))) {
      assertEquals(200, response.getStatus());
      assertEquals("no-store", response.getHeaderString("Cache-Control"));
      final JsonNode body = response.readEntity(JsonNode.class);
      final Set<String> keys = new HashSet<>();
      body.fieldNames().forEachRemaining(keys::add);
      assertEquals(Set.of("title", "domain", "provider", "image", "imageContentType", "imageWidth", "imageHeight"), keys);
      assertEquals("标题", body.get("title").asText());
      assertEquals("mp.weixin.qq.com", body.get("domain").asText());
      assertEquals("weixin-mp", body.get("provider").asText());
      assertEquals("image/jpeg", body.get("imageContentType").asText());
      assertEquals(800, body.get("imageWidth").asInt());
      assertNotNull(Base64.getDecoder().decode(body.get("image").asText()));
      assertTrue(!body.toString().contains("sharer") && !body.toString().contains("http"));
    }
  }

  @Test
  void noCardIsNoContent() {
    try (final Response response = post(Map.of("url", "https://www.zhihu.com/question/640359835"))) {
      assertEquals(204, response.getStatus());
    }
  }

  @Test
  void hostOffTheWhitelistIs422() {
    try (final Response response = post(Map.of("url", "https://www.baidu.com/"))) {
      assertEquals(422, response.getStatus());
      assertEquals("not_allowed", response.readEntity(JsonNode.class).get("error").asText());
    }
  }

  @Test
  void malformedIs400() {
    try (final Response response = post(Map.of("url", "http://mp.weixin.qq.com/s/x"))) {
      assertEquals(400, response.getStatus());
    }
    try (final Response response = post(Map.of("url", "not a url"))) {
      assertEquals(400, response.getStatus());
    }
    try (final Response response = post(Map.of("nope", "x"))) {
      assertEquals(422, response.getStatus());   // Dropwizard 的校验失败（url 缺失）
    }
  }

  @Test
  void rateLimitedIs429WithRetryAfter() throws Exception {
    doThrow(new RateLimitExceededException(Duration.ofSeconds(30))).when(limiter).validate(any(UUID.class));
    try (final Response response = post(Map.of("url", WX))) {
      assertEquals(429, response.getStatus());
      assertEquals("30", response.getHeaderString("Retry-After"));
    }
  }

  @Test
  void limiterOutageIs503() throws Exception {
    doThrow(new RuntimeException("redis down")).when(limiter).validate(any(UUID.class));
    try (final Response response = post(Map.of("url", WX))) {
      assertEquals(503, response.getStatus());
      assertEquals("30", response.getHeaderString("Retry-After"));
    }
  }

  @Test
  void requiresAuthentication() {
    try (final Response response = resources.getJerseyTest().target("/v1/tellomi/link-preview").request()
        .post(Entity.entity(Map.of("url", WX), MediaType.APPLICATION_JSON_TYPE))) {
      assertEquals(401, response.getStatus());
    }
  }
}
