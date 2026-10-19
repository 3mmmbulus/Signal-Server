/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.google.api.client.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.api.client.http.javanet.NetHttpTransport;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostRewritingHttpTransportTest {

  private final HostRewritingHttpTransport transport = new HostRewritingHttpTransport(new NetHttpTransport(),
      Map.of("fcm.googleapis.com", URI.create("https://relay.example.com/fcm/"),
          "oauth2.googleapis.com", URI.create("https://relay.example.com:8443")));

  @Test
  void rewritesConfiguredHosts() {
    assertEquals("https://relay.example.com/fcm/v1/projects/p/messages:send",
        transport.rewrite("https://fcm.googleapis.com/v1/projects/p/messages:send"));
    assertEquals("https://relay.example.com:8443/token?a=b",
        transport.rewrite("https://oauth2.googleapis.com/token?a=b"));
  }

  @Test
  void leavesOtherHostsAlone() {
    assertEquals("https://www.googleapis.com/x", transport.rewrite("https://www.googleapis.com/x"));
  }
}
