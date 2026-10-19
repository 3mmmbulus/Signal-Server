/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.auth.Auth;
import io.micrometer.core.instrument.Metrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;
import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.tellomi.linkpreview.LinkPreviewFetcher;
import org.whispersystems.textsecuregcm.tellomi.linkpreview.LinkPreviewService;
import org.whispersystems.textsecuregcm.tellomi.linkpreview.LinkPreviewUrls;

/// Tellomi（ADR-0063 §6.5，owner 2026-10-19）：`POST /v1/tellomi/link-preview`——手机端抓不到的几家（公众号、知乎、
/// 微博、小红书；产品经理同日加了抖音、淘宝 / 天猫商品页）由服务端代抓，只回卡片要的标题、缩略图、域名。**默认关**：没开时这个路由不注册（404）。
///
/// 链接放在请求体里，不放路径 / 查询串：nginx 和请求日志只看得到固定的路径。不记日志（见 [LinkPreviewService]）。
@Path("/v1/tellomi/link-preview")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Tellomi")
public class TellomiLinkPreviewController {

  static final int BUSY_RETRY_AFTER_SECONDS = 5;
  static final int LIMITER_UNAVAILABLE_RETRY_AFTER_SECONDS = 30;
  private static final String LIMITER_UNAVAILABLE_COUNTER_NAME = "tellomi.linkPreview.limiterUnavailable";

  private final RateLimiters rateLimiters;
  private final LinkPreviewService service;
  private final Executor executor;

  public TellomiLinkPreviewController(final RateLimiters rateLimiters, final LinkPreviewService service,
      final Executor executor) {
    this.rateLimiters = rateLimiters;
    this.service = service;
    this.executor = executor;
  }

  public record PreviewRequest(@NotNull @Size(min = 1, max = LinkPreviewUrls.MAX_URL_LENGTH) @JsonProperty("url") String url) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PreviewResponse(@JsonProperty("title") String title,
                                @JsonProperty("domain") String domain,
                                @JsonProperty("provider") String provider,
                                @Nullable @JsonProperty("image") String image,
                                @Nullable @JsonProperty("imageContentType") String imageContentType,
                                @Nullable @JsonProperty("imageWidth") Integer imageWidth,
                                @Nullable @JsonProperty("imageHeight") Integer imageHeight) {

    static PreviewResponse of(final LinkPreviewFetcher.Card card) {
      if (card.image() == null) {
        return new PreviewResponse(card.title(), card.domain(), card.provider(), null, null, null, null);
      }
      return new PreviewResponse(card.title(), card.domain(), card.provider(),
          Base64.getEncoder().encodeToString(card.image().bytes()), card.image().contentType(),
          card.image().width() > 0 ? card.image().width() : null,
          card.image().height() > 0 ? card.image().height() : null);
    }
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Builds a link card for a whitelisted site the phone cannot fetch",
      description = """
          Only mp.weixin.qq.com, zhihu, weibo, xiaohongshu, douyin and taobao / tmall item pages. Returns the title, a server-made thumbnail (base64) and
          the domain; nothing else. The server keeps no record of who asked for which link.
          """)
  @ApiResponse(responseCode = "200", description = "The card", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "204", description = "The site gave no card (login wall, captcha, error, timeout)")
  @ApiResponse(responseCode = "400", description = "Not an https URL of the accepted shape")
  @ApiResponse(responseCode = "422", description = "The host is not on the server-fetch whitelist. Body {\"error\":\"not_allowed\"}")
  @ApiResponse(responseCode = "429", description = "Too many previews from this account. Retry-After says when")
  @ApiResponse(responseCode = "503", description = "The server is busy or its rate limiter is down. Retry-After says when")
  public CompletableFuture<Response> preview(@Auth final AuthenticatedDevice auth,
      @NotNull @Valid final PreviewRequest request) throws RateLimitExceededException {

    try {
      rateLimiters.getTellomiLinkPreviewLimiter().validate(auth.accountIdentifier());
    } catch (final RateLimitExceededException e) {
      throw e;
    } catch (final RuntimeException e) {
      // 限流器自己坏了（Redis 不可用）：不放行，免得服务端变成没人管的抓取器
      Metrics.counter(LIMITER_UNAVAILABLE_COUNTER_NAME).increment();
      return CompletableFuture.completedFuture(unavailable(LIMITER_UNAVAILABLE_RETRY_AFTER_SECONDS));
    }

    final Optional<LinkPreviewUrls.Target> target = LinkPreviewUrls.parse(request.url());
    if (target.isEmpty()) {
      return CompletableFuture.completedFuture(isWellFormedHttps(request.url())
          ? Response.status(422).entity(Map.of("error", "not_allowed")).header("Cache-Control", "no-store").build()
          : Response.status(400).header("Cache-Control", "no-store").build());
    }

    try {
      return CompletableFuture.supplyAsync(() -> toResponse(service.preview(target.get())), executor);
    } catch (final RejectedExecutionException e) {
      return CompletableFuture.completedFuture(unavailable(BUSY_RETRY_AFTER_SECONDS));
    }
  }

  private static Response toResponse(final LinkPreviewService.Answer answer) {
    return switch (answer) {
      case LinkPreviewService.Answer.Found found ->
          Response.ok(PreviewResponse.of(found.card())).header("Cache-Control", "no-store").build();
      case LinkPreviewService.Answer.NotAvailable ignored ->
          Response.noContent().header("Cache-Control", "no-store").build();
      case LinkPreviewService.Answer.Busy ignored -> unavailable(BUSY_RETRY_AFTER_SECONDS);
    };
  }

  private static Response unavailable(final int retryAfterSeconds) {
    return Response.status(503).header("Retry-After", retryAfterSeconds).header("Cache-Control", "no-store").build();
  }

  /// 形状对、只是主机不在白名单：422（客户端据此知道「这家不归服务端」，不是自己发错了）
  private static boolean isWellFormedHttps(final String raw) {
    try {
      final java.net.URI uri = new java.net.URI(raw);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null;
    } catch (final java.net.URISyntaxException e) {
      return false;
    }
  }
}
