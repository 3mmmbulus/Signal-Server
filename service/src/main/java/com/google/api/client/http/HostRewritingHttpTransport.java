/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.google.api.client.http;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * Wraps another {@link HttpTransport} and rewrites the scheme/host/port of requests whose host appears in
 * {@code hostRewrites}. Path and query are preserved. Lives in this package because
 * {@link HttpTransport#buildRequest} is protected.
 *
 * <p>Tellomi: used to send FCM/OAuth traffic through a relay (e.g. a Cloudflare Worker) from networks that cannot
 * reach Google directly. Credentials never leave the server; only the short-lived access token and the signed JWT
 * assertion transit the relay.
 */
public final class HostRewritingHttpTransport extends HttpTransport {

  private final HttpTransport delegate;
  private final Map<String, URI> hostRewrites;

  public HostRewritingHttpTransport(final HttpTransport delegate, final Map<String, URI> hostRewrites) {
    this.delegate = delegate;
    this.hostRewrites = Map.copyOf(hostRewrites);
  }

  public String rewrite(final String url) {
    final URI original = URI.create(url);
    final URI target = original.getHost() == null ? null : hostRewrites.get(original.getHost().toLowerCase());
    if (target == null) {
      return url;
    }
    final String basePath = target.getRawPath() == null ? "" : target.getRawPath().replaceAll("/+$", "");
    final StringBuilder sb = new StringBuilder()
        .append(target.getScheme()).append("://").append(target.getRawAuthority())
        .append(basePath)
        .append(original.getRawPath() == null ? "" : original.getRawPath());
    if (original.getRawQuery() != null) {
      sb.append('?').append(original.getRawQuery());
    }
    return sb.toString();
  }

  @Override
  protected LowLevelHttpRequest buildRequest(final String method, final String url) throws IOException {
    return delegate.buildRequest(method, rewrite(url));
  }

  @Override
  public boolean supportsMethod(final String method) throws IOException {
    return delegate.supportsMethod(method);
  }

  @Override
  public boolean isMtls() {
    return delegate.isMtls();
  }

  @Override
  public void shutdown() throws IOException {
    delegate.shutdown();
  }
}
