/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/// A stand-in for the content engine's `DELETE /internal/users/{sub}`, on a loopback port. No real network.
final class MockEngine implements AutoCloseable {

  record Seen(String method, String path, String authorization) {
  }

  /// what to answer: status, extra headers, and how long to take
  record Reply(int status, Map<String, String> headers, long delayMillis) {
    static Reply status(final int status) {
      return new Reply(status, Map.of(), 0);
    }

    static Reply status(final int status, final String header, final String value) {
      return new Reply(status, Map.of(header, value), 0);
    }

    static Reply slow(final long millis) {
      return new Reply(200, Map.of(), millis);
    }
  }

  private final HttpServer server;
  final List<Seen> seen = new CopyOnWriteArrayList<>();
  final AtomicInteger hits = new AtomicInteger();
  volatile Function<Integer, Reply> script = n -> Reply.status(200);

  MockEngine() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", exchange -> {
      final int n = hits.incrementAndGet();
      seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
          exchange.getRequestHeaders().getFirst("Authorization")));
      exchange.getRequestBody().readAllBytes();
      final Reply reply = script.apply(n);
      if (reply.delayMillis() > 0) {
        try {
          Thread.sleep(reply.delayMillis());
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
      final byte[] body = "{\"deleted_objects\":0}".getBytes();
      try {
        exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : body.length);
        if (reply.status() != 204) {
          exchange.getResponseBody().write(body);
        }
      } catch (final IOException ignored) {
        // the client gave up (timeout test)
      } finally {
        exchange.close();
      }
    });
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
      final Thread t = new Thread(r, "mock-engine");
      t.setDaemon(true);
      return t;
    }));
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + port();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
