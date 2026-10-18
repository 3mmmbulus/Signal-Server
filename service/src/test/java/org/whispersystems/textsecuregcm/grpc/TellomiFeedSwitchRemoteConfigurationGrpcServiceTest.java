/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.whispersystems.textsecuregcm.storage.TellomiFeedSwitchTest.CN_NUMBER;
import static org.whispersystems.textsecuregcm.storage.TellomiFeedSwitchTest.US_NUMBER;
import static org.whispersystems.textsecuregcm.storage.TellomiFeedSwitchTest.accountWithNumber;

import com.google.common.net.InetAddresses;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.chat.remoteconfiguration.GetConfigurationRequest;
import org.signal.chat.remoteconfiguration.RemoteConfigurationGrpc;
import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.badges.BadgeTranslator;
import org.whispersystems.textsecuregcm.configuration.TellomiFeedSwitchConfiguration;
import org.whispersystems.textsecuregcm.controllers.RemoteConfigController;
import org.whispersystems.textsecuregcm.entities.RemoteConfigurationResponse;
import org.whispersystems.textsecuregcm.storage.RemoteConfig;
import org.whispersystems.textsecuregcm.storage.RemoteConfigs;
import org.whispersystems.textsecuregcm.storage.RemoteConfigsManager;
import org.whispersystems.textsecuregcm.storage.TellomiFeedSwitch;

/**
 * Tellomi（F-14）：gRPC `GetConfiguration`（真 {@link RemoteConfigsManager} + 开关）里 `global.feed.enabled` 的值，
 * 并和同一个 manager 的 HTTP `/v2/config` 控制器逐项对比：两条路必须给同一个账号完全一样的配置。
 */
class TellomiFeedSwitchRemoteConfigurationGrpcServiceTest extends
    SimpleBaseGrpcTest<RemoteConfigurationGrpcService, RemoteConfigurationGrpc.RemoteConfigurationBlockingStub> {

  private static final String USER_AGENT = "Signal-Android/7.6.2 Android/34 libsignal/0.46.0";

  // 每个场景换一份开关配置（manager 和 gRPC 服务只在每个测试前建一次）
  private TellomiFeedSwitch currentSwitch;
  private String currentNumber;
  private RemoteConfigController httpController;

  @Override
  protected RemoteConfigurationGrpcService createServiceBeforeEachTest() {
    getMockRequestAttributesInterceptor().setRequestAttributes(
        new RequestAttributes(InetAddresses.forString("127.0.0.1"), USER_AGENT, null));

    final RemoteConfigs remoteConfigs = mock(RemoteConfigs.class);
    when(remoteConfigs.getAll()).thenReturn(List.of(new RemoteConfig("always.true", 100, Set.of(), null, null, null)));

    currentSwitch = null;
    currentNumber = CN_NUMBER;
    final RemoteConfigsManager manager = new RemoteConfigsManager(remoteConfigs, Map.of("maxGroupSize", "42"),
        new TellomiFeedSwitch(new TellomiFeedSwitchConfiguration(false, null, null), aci -> Optional.empty()) {
          @Override
          public Optional<String> valueFor(final UUID accountIdentifier) {
            return currentSwitch.valueFor(accountIdentifier);
          }
        });
    httpController = new RemoteConfigController(manager);

    final BadgeTranslator badgeTranslator = mock(BadgeTranslator.class);
    return new RemoteConfigurationGrpcService(manager, badgeTranslator, List.of());
  }

  private void configure(final boolean enabled, final List<String> regions, final List<UUID> beta, final String number) {
    currentNumber = number;
    currentSwitch = new TellomiFeedSwitch(new TellomiFeedSwitchConfiguration(enabled, regions, beta),
        aci -> Optional.of(accountWithNumber(currentNumber)));
  }

  private Map<String, String> viaGrpc() {
    return authenticatedServiceStub().getConfiguration(GetConfigurationRequest.newBuilder().build())
        .getTaggedConfiguration().getConfiguration().getConfigurationMap();
  }

  private Map<String, String> viaHttp() {
    try (Response response = httpController.getAll(
        new AuthenticatedDevice(AUTHENTICATED_ACI, AUTHENTICATED_DEVICE_ID, Instant.now()), null, USER_AGENT)) {
      return ((RemoteConfigurationResponse) response.getEntity()).config();
    }
  }

  private static Stream<Arguments> scenarios() {
    // name, enabled, regions, aciOnBetaList, number, expected
    return Stream.of(
        Arguments.of("default off", false, null, false, CN_NUMBER, "false"),
        Arguments.of("master off, listed, region open", false, List.of("cn", "global"), true, CN_NUMBER, "false"),
        Arguments.of("on: listed + region", true, List.of("cn"), true, CN_NUMBER, "true"),
        Arguments.of("on: listed + global region", true, List.of("global"), true, US_NUMBER, "true"),
        Arguments.of("off: not listed", true, List.of("cn", "global"), false, CN_NUMBER, "false"),
        Arguments.of("off: region closed (+86, only global)", true, List.of("global"), true, CN_NUMBER, "false"),
        Arguments.of("off: region closed (global number, only cn)", true, List.of("cn"), true, US_NUMBER, "false"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void grpcAndHttpGiveTheSameConfigAndTheSameFeedValue(final String name, final boolean enabled,
      final List<String> regions, final boolean listed, final String number, final String expected) {
    configure(enabled, regions, listed ? List.of(AUTHENTICATED_ACI) : List.of(UUID.randomUUID()), number);

    final Map<String, String> grpc = viaGrpc();
    final Map<String, String> http = viaHttp();

    assertThat(grpc).containsEntry("global.feed.enabled", expected);
    assertThat(http).containsEntry("global.feed.enabled", expected);
    assertThat(grpc).isEqualTo(http);
    assertThat(grpc).containsEntry("global.maxGroupSize", "42").containsEntry("always.true", "true");
  }

  @Test
  void aFailedAccountReadLeavesTheKeyOutOnBothPaths() {
    currentSwitch = new TellomiFeedSwitch(
        new TellomiFeedSwitchConfiguration(true, List.of("cn"), List.of(AUTHENTICATED_ACI)),
        aci -> {
          throw new RuntimeException("dynamodb down");
        });

    final Map<String, String> grpc = viaGrpc();
    final Map<String, String> http = viaHttp();

    assertThat(grpc).doesNotContainKey("global.feed.enabled").containsEntry("global.maxGroupSize", "42");
    assertThat(grpc).isEqualTo(http);
  }
}
