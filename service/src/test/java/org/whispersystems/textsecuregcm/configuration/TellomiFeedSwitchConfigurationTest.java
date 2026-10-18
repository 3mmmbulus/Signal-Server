/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.dropwizard.jackson.Jackson;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tellomi（F-14）：tellomiFeedSwitch 配置的默认值与校验——默认全是关。 */
class TellomiFeedSwitchConfigurationTest {

  // what Dropwizard parses server.yml with
  private static final ObjectMapper YAML = Jackson.newObjectMapper(new YAMLFactory());

  @Test
  void anEmptyBlockIsOffWithNoRegionsAndNoBetaAccounts() throws Exception {
    final TellomiFeedSwitchConfiguration config = YAML.readValue("{}", TellomiFeedSwitchConfiguration.class);

    assertFalse(config.enabled());
    assertEquals(List.of(), config.regions());
    assertEquals(List.of(), config.betaAcis());
  }

  @Test
  void aFullBlockIsRead() throws Exception {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final TellomiFeedSwitchConfiguration config = YAML.readValue("""
        enabled: true
        regions: [CN, " global ", cn]
        betaAcis:
          - %s
          - %s
        """.formatted(a, b), TellomiFeedSwitchConfiguration.class);

    assertTrue(config.enabled());
    assertEquals(List.of("cn", "global"), config.regions(), "trimmed, lower-cased, de-duplicated");
    assertEquals(List.of(a, b), config.betaAcis());
  }

  @Test
  void anUnknownRegionIsRejected() {
    assertThrows(JsonMappingException.class, () -> YAML.readValue("""
        enabled: true
        regions: [cn, eu]
        """, TellomiFeedSwitchConfiguration.class));
  }

  @Test
  void aBetaEntryThatIsNotAnAciIsRejected() {
    assertThrows(JsonMappingException.class, () -> YAML.readValue("""
        enabled: true
        regions: [cn]
        betaAcis: [not-an-aci]
        """, TellomiFeedSwitchConfiguration.class));
  }

  @Test
  void anEmptyBetaEntryIsRejected() {
    assertThrows(JsonMappingException.class, () -> YAML.readValue("""
        enabled: true
        regions: [cn]
        betaAcis: [null]
        """, TellomiFeedSwitchConfiguration.class));
  }
}
