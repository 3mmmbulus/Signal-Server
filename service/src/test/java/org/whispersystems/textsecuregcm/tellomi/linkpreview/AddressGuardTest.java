/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class AddressGuardTest {

  @ParameterizedTest
  @CsvSource({
      "0.0.0.0, true", "10.1.2.3, true", "100.64.0.1, true", "100.100.100.200, true", "100.127.255.255, true",
      "127.0.0.1, true", "169.254.169.254, true", "172.16.0.1, true", "172.31.255.255, true", "192.168.1.1, true",
      "198.18.0.1, true", "198.19.255.254, true", "224.0.0.1, true", "255.255.255.255, true", "192.0.2.1, true",
      "::1, true", "::, true", "fd00::1, true", "fc00::1, true", "fe80::1, true", "::ffff:10.0.0.1, true",
      "::ffff:127.0.0.1, true", "64:ff9b::a00:1, true", "2002:a00:1::1, true", "2001:db8::1, true",
      "1.1.1.1, false", "100.63.255.255, false", "100.128.0.1, false", "172.32.0.1, false", "198.20.0.1, false",
      "47.57.71.77, false", "203.119.0.1, false", "2400:3200::1, false", "::ffff:8.8.8.8, false"
  })
  void classifiesAddresses(final String literal, final boolean forbidden) throws Exception {
    assertEquals(forbidden, AddressGuard.isForbidden(InetAddress.getByName(literal)), literal);
  }

  @Test
  void anyForbiddenAnswerRejectsTheHost() throws Exception {
    final InetAddress pub = InetAddress.getByName("1.1.1.1");
    final InetAddress priv = InetAddress.getByName("10.0.0.1");
    assertThrows(AddressGuard.ForbiddenAddressException.class,
        () -> AddressGuard.check(host -> new InetAddress[]{pub, priv}, "mp.weixin.qq.com"));
    assertDoesNotThrow(() -> AddressGuard.check(host -> new InetAddress[]{pub}, "mp.weixin.qq.com"));
    assertThrows(UnknownHostException.class, () -> AddressGuard.check(host -> new InetAddress[0], "x"));
  }
}
