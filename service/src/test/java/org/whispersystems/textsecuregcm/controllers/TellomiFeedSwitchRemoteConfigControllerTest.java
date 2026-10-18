/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.whispersystems.textsecuregcm.storage.TellomiFeedSwitchTest.CN_NUMBER;
import static org.whispersystems.textsecuregcm.storage.TellomiFeedSwitchTest.accountWithNumber;

import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.configuration.TellomiFeedSwitchConfiguration;
import org.whispersystems.textsecuregcm.entities.RemoteConfigurationResponse;
import org.whispersystems.textsecuregcm.mappers.DeviceLimitExceededExceptionMapper;
import org.whispersystems.textsecuregcm.storage.RemoteConfig;
import org.whispersystems.textsecuregcm.storage.RemoteConfigs;
import org.whispersystems.textsecuregcm.storage.RemoteConfigsManager;
import org.whispersystems.textsecuregcm.storage.TellomiFeedSwitch;
import org.whispersystems.textsecuregcm.tests.util.AuthHelper;

/** Tellomi（F-14）：真实的 `GET /v2/config`（真 {@link RemoteConfigsManager} + 开关）里 `global.feed.enabled` 的值。 */
@ExtendWith(DropwizardExtensionsSupport.class)
class TellomiFeedSwitchRemoteConfigControllerTest {

  private static final RemoteConfigs REMOTE_CONFIGS = mock(RemoteConfigs.class);

  static {
    when(REMOTE_CONFIGS.getAll()).thenReturn(List.of(new RemoteConfig("always.true", 100, Set.of(), null, null, null)));
  }

  private static RemoteConfigsManager manager(final boolean enabled, final List<String> regions) {
    return new RemoteConfigsManager(REMOTE_CONFIGS, Map.of("maxGroupSize", "42"), new TellomiFeedSwitch(
        new TellomiFeedSwitchConfiguration(enabled, regions, List.of(AuthHelper.VALID_UUID)),
        aci -> Optional.of(accountWithNumber(CN_NUMBER))));
  }

  private static final ResourceExtension ON = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new AuthValueFactoryProvider.Binder<>(AuthenticatedDevice.class))
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addProvider(new DeviceLimitExceededExceptionMapper())
      .addResource(new RemoteConfigController(manager(true, List.of("cn"))))
      .build();

  private static final ResourceExtension OFF_BY_DEFAULT = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new AuthValueFactoryProvider.Binder<>(AuthenticatedDevice.class))
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addProvider(new DeviceLimitExceededExceptionMapper())
      .addResource(new RemoteConfigController(manager(false, null)))
      .build();

  private static Map<String, String> fetch(final ResourceExtension resources) {
    final Response response = resources.getJerseyTest()
        .target("/v2/config/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .header("User-Agent", "Signal-Android/7.6.2 Android/34 libsignal/0.46.0")
        .get();
    assertThat(response.getStatus()).isEqualTo(200);
    return response.readEntity(RemoteConfigurationResponse.class).config();
  }

  @Test
  void theHttpConfigCarriesTrueForABetaAccountInAnOpenRegion() {
    assertThat(fetch(ON)).containsEntry("global.feed.enabled", "true").containsEntry("global.maxGroupSize", "42");
  }

  @Test
  void theHttpConfigCarriesFalseByDefault() {
    assertThat(fetch(OFF_BY_DEFAULT)).containsEntry("global.feed.enabled", "false").containsEntry("global.maxGroupSize", "42");
  }
}
