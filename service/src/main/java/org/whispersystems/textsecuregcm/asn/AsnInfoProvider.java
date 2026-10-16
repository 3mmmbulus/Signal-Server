/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.asn;

import java.util.Optional;
import javax.annotation.Nonnull;

public interface AsnInfoProvider {

  /// Gets ASN information for an IP address.
  ///
  /// @param ipString a string representation of an IP address
  ///
  /// @return ASN information for the given IP address or empty if no ASN information was found for the given IP address
  Optional<AsnInfo> lookup(@Nonnull String ipString);

  /// Tellomi（B-9）：表里一条都没有——还没载入、载入失败（[#EMPTY]），或载入的就是个空表（香港的 `asnTable` 目前指向内部桶里
  /// 一个空的 gzip）。这几种情况都认不出任何 IP，动态接口要按「表是空的」降级。
  default boolean isEmpty() {
    return false;
  }

  AsnInfoProvider EMPTY = new AsnInfoProvider() {
    @Override
    public Optional<AsnInfo> lookup(@Nonnull final String ipString) {
      return Optional.empty();
    }

    @Override
    public boolean isEmpty() {
      return true;
    }
  };
}
