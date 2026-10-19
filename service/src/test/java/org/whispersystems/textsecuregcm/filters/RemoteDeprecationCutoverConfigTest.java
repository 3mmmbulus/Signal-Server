/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.filters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.net.HttpHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.whispersystems.textsecuregcm.auth.AccountAuthenticator;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.tests.util.FakeDynamicConfigurationManager;
import org.whispersystems.textsecuregcm.util.ua.UserAgentUtil;

/// 成都切换（2026-11-15）的强制升级：把运维要贴进 `dynamicConfig` 的那几段 YAML 原样钉在这里。
///
/// 产品决定（2026-10-19）：三端的最低版本都按平台分开配，缺省全关；切换当天只开 Desktop（`minimumVersions.DESKTOP`，
/// Desktop 的 User-Agent 没有 `Build/` 段，只能按版本号）；Android / iOS 只有在 `grpc.chat.tellomi.app` 的 Cloudflare 转发
/// 测试不通过时才开（`minimumBuilds.ANDROID` / `minimumBuilds.IOS`，按构建号，热修包也分得开）。
/// 机制本身（HTTP 499、gRPC `UPGRADE_REQUIRED`、websocket 建连）由 `RemoteDeprecationFilterTest` /
/// `RemoteDeprecationFilterBuildNumberTest` 守着；这里只守「这几段配置贴进去以后，拦的正好是该拦的」。
///
/// 下面的版本号 / 构建号是示例：`0.1.16` 代表 11-05 发出的 .cn Desktop，`178101` / `40` 代表那一版 Android / iOS 的构建号。
/// 真值切换前由产品经理 / tellomi测试 给出（见 REPORT-desktop-min-version.md）。
class RemoteDeprecationCutoverConfigTest {

  private static final String REQUIRED_CONFIG = """
      captcha:
        scoreFloor: 1.0
      """;

  /// 切换当天：只开 Desktop
  private static final String CUTOVER_DESKTOP_ONLY = """
      remoteDeprecation:
        minimumVersions:
          DESKTOP: 0.1.16
      """;

  /// 条件步骤：grpc.chat.tellomi.app 经 Cloudflare 的测试不通过时，再加上手机端（Desktop 那一条保留）
  private static final String CUTOVER_DESKTOP_AND_MOBILE = """
      remoteDeprecation:
        minimumVersions:
          DESKTOP: 0.1.16
        minimumBuilds:
          ANDROID: 178101
          IOS: 40
      """;

  // 三端真实发的 User-Agent 形状（Desktop ts/util/getUserAgent.node.ts；Android StandardUserAgentInterceptor；
  // iOS HttpHeaders.swift）。Android versionCode = canonicalVersionCode * 100 + 热修号，0.1.31 → 178001。
  private static final String DESKTOP_OLD_MAC = "Signal-Desktop/0.1.15 macOS 25.0.0";
  private static final String DESKTOP_OLD_WINDOWS = "Signal-Desktop/0.1.11 Windows 10.0.22000";
  private static final String DESKTOP_OLD_BARE = "Signal-Desktop/0.1.13";
  private static final String DESKTOP_NEW = "Signal-Desktop/0.1.16 macOS 25.0.0";
  private static final String DESKTOP_NEWER = "Signal-Desktop/0.2.0 macOS 25.0.0";
  private static final String ANDROID_OLD = "Signal-Android/0.1.31 Android/34 Build/178001";
  private static final String ANDROID_NEW = "Signal-Android/0.1.32 Android/34 Build/178101";
  private static final String IOS_OLD = "Signal-iOS/0.1.2 iOS/26.0 Build/37";
  private static final String IOS_NEW = "Signal-iOS/0.1.3 iOS/26.0 Build/40";
  private static final String THIRD_PARTY = "curl/8.0.1";

  private static RemoteDeprecationFilter filter(final String remoteDeprecationYaml) {
    final DynamicConfiguration configuration = DynamicConfigurationManager
        .parseConfiguration(REQUIRED_CONFIG + remoteDeprecationYaml, DynamicConfiguration.class)
        .orElseThrow(() -> new AssertionError("configuration should parse: " + remoteDeprecationYaml));

    return new RemoteDeprecationFilter(mock(AccountsManager.class), mock(AccountAuthenticator.class),
        new FakeDynamicConfigurationManager<>(configuration));
  }

  private static boolean blocks(final String remoteDeprecationYaml, final String userAgent) {
    return filter(remoteDeprecationYaml).shouldBlock(UserAgentUtil.maybeParseUserAgentString(userAgent), null);
  }

  @ParameterizedTest
  @MethodSource
  void cutover(final String yaml, final String userAgent, final boolean expectBlock) {
    assertThat(blocks(yaml, userAgent)).as(userAgent).isEqualTo(expectBlock);
  }

  private static Stream<Arguments> cutover() {
    return Stream.of(
        // 缺省（没有 remoteDeprecation 段）：谁都不拦
        Arguments.argumentSet("缺省 / 旧 Desktop", "", DESKTOP_OLD_MAC, false),
        Arguments.argumentSet("缺省 / 旧 Android", "", ANDROID_OLD, false),
        Arguments.argumentSet("缺省 / 旧 iOS", "", IOS_OLD, false),

        // 切换当天：只有低于最低版本的 Desktop 被拦，手机端一个都不动
        Arguments.argumentSet("只开 Desktop / 旧 Desktop（mac）", CUTOVER_DESKTOP_ONLY, DESKTOP_OLD_MAC, true),
        Arguments.argumentSet("只开 Desktop / 旧 Desktop（Windows）", CUTOVER_DESKTOP_ONLY, DESKTOP_OLD_WINDOWS, true),
        Arguments.argumentSet("只开 Desktop / 旧 Desktop（UA 没有系统段）", CUTOVER_DESKTOP_ONLY, DESKTOP_OLD_BARE, true),
        Arguments.argumentSet("只开 Desktop / 新 Desktop（等于最低版本）", CUTOVER_DESKTOP_ONLY, DESKTOP_NEW, false),
        Arguments.argumentSet("只开 Desktop / 更新的 Desktop", CUTOVER_DESKTOP_ONLY, DESKTOP_NEWER, false),
        Arguments.argumentSet("只开 Desktop / 旧 Android", CUTOVER_DESKTOP_ONLY, ANDROID_OLD, false),
        Arguments.argumentSet("只开 Desktop / 旧 iOS", CUTOVER_DESKTOP_ONLY, IOS_OLD, false),
        Arguments.argumentSet("只开 Desktop / 第三方 UA", CUTOVER_DESKTOP_ONLY, THIRD_PARTY, false),

        // 条件步骤：再加手机端
        Arguments.argumentSet("三端 / 旧 Desktop", CUTOVER_DESKTOP_AND_MOBILE, DESKTOP_OLD_MAC, true),
        Arguments.argumentSet("三端 / 新 Desktop", CUTOVER_DESKTOP_AND_MOBILE, DESKTOP_NEW, false),
        Arguments.argumentSet("三端 / 旧 Android", CUTOVER_DESKTOP_AND_MOBILE, ANDROID_OLD, true),
        Arguments.argumentSet("三端 / 新 Android（等于最低构建号）", CUTOVER_DESKTOP_AND_MOBILE, ANDROID_NEW, false),
        Arguments.argumentSet("三端 / 旧 iOS", CUTOVER_DESKTOP_AND_MOBILE, IOS_OLD, true),
        Arguments.argumentSet("三端 / 新 iOS（等于最低构建号）", CUTOVER_DESKTOP_AND_MOBILE, IOS_NEW, false),
        Arguments.argumentSet("三端 / 第三方 UA", CUTOVER_DESKTOP_AND_MOBILE, THIRD_PARTY, false));
  }

  /// HTTP 路径上旧 Desktop 真的收到 499、请求不往下走；新 Desktop 照常放行
  @Test
  void desktopOnlyHttp499() throws Exception {
    final RemoteDeprecationFilter filter = filter(CUTOVER_DESKTOP_ONLY);

    final HttpServletRequest oldRequest = mock(HttpServletRequest.class);
    when(oldRequest.getHeader(HttpHeaders.USER_AGENT)).thenReturn(DESKTOP_OLD_MAC);
    final HttpServletResponse oldResponse = mock(HttpServletResponse.class);
    final FilterChain oldChain = mock(FilterChain.class);

    filter.doFilter(oldRequest, oldResponse, oldChain);

    verify(oldResponse).sendError(499);
    verify(oldChain, never()).doFilter(any(), any());

    for (final String userAgent : new String[]{DESKTOP_NEW, ANDROID_OLD, IOS_OLD}) {
      final HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getHeader(HttpHeaders.USER_AGENT)).thenReturn(userAgent);
      final HttpServletResponse response = mock(HttpServletResponse.class);
      final FilterChain chain = mock(FilterChain.class);

      filter.doFilter(request, response, chain);

      verify(response, never()).sendError(any(Integer.class));
      verify(chain).doFilter(request, response);
    }
  }
}
