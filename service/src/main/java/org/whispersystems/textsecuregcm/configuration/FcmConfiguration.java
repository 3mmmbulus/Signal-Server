/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Map;
import javax.annotation.Nullable;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretString;

/**
 * @param credentials   service account JSON
 * @param hostOverrides optional (Tellomi): map of Google API host (e.g. {@code fcm.googleapis.com},
 *                      {@code oauth2.googleapis.com}) to a relay base URI (e.g. {@code https://fcm-relay.example.com}).
 *                      Empty/absent (the default) means talk to Google directly, unchanged from upstream.
 */
public record FcmConfiguration(@NotNull SecretString credentials, @Nullable Map<String, URI> hostOverrides) {

  public FcmConfiguration(final SecretString credentials) {
    this(credentials, null);
  }

  public Map<String, URI> hostOverridesOrEmpty() {
    return hostOverrides == null ? Map.of() : hostOverrides;
  }
}
