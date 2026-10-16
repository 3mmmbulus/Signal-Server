/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.whispersystems.textsecuregcm.asn.AsnInfoProvider;
import org.whispersystems.textsecuregcm.asn.AsnInfoProviderImpl;
import org.whispersystems.textsecuregcm.auth.TellomiFeedTokens;
import org.whispersystems.textsecuregcm.filters.RemoteAddressFilter;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;

/**
 * B-9, ASN-table part: the region decision with a REAL parsed ASN table (iptoasn.com TSV, the format the `asnTable` S3
 * object has) instead of a mocked provider, and the visibility of "the table is not loaded" (PR #13 debt: with an empty
 * table a mainland IP can never be recognised).
 */
class TellomiFeedRegionAsnTableTest {

  // range_start range_end AS_number country_code AS_description (tab separated), as in AsnInfoProviderImplTest
  private static final String TSV = String.join("\n",
      "1.2.4.0\t1.2.4.255\t4134\tCN\tCHINANET-BACKBONE",
      "2408::\t2408:ffff:ffff:ffff:ffff:ffff:ffff:ffff\t4837\tcn\tCHINA169-BACKBONE",
      "8.8.8.0\t8.8.8.255\t15169\tUS\tGOOGLE",
      "2001:4860::\t2001:4860:ffff:ffff:ffff:ffff:ffff:ffff\t15169\tUS\tGOOGLE-V6") + "\n";

  private static final AsnInfoProvider TABLE = AsnInfoProviderImpl.fromTsv(
      new ByteArrayInputStream(TSV.getBytes(StandardCharsets.UTF_8)));

  private SimpleMeterRegistry registry;

  @BeforeEach
  void addRegistry() {
    registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
  }

  @AfterEach
  void removeRegistry() {
    Metrics.removeRegistry(registry);
  }

  private static TellomiFeedController controller(final AsnInfoProvider provider, final AtomicInteger providerCalls) {
    return new TellomiFeedController(mock(AccountsManager.class), mock(RateLimiters.class),
        new TellomiFeedTokens("signing-key-for-asn-table-tests-0001".getBytes(StandardCharsets.UTF_8),
            "pseudonym-key-for-asn-table-tests-02".getBytes(StandardCharsets.UTF_8)),
        List.of("tellomi.cn"), () -> {
      providerCalls.incrementAndGet();
      return provider;
    }, Clock.systemUTC());
  }

  private static Account account(final String number) {
    final Account account = mock(Account.class);
    when(account.getNumber()).thenReturn(Optional.ofNullable(number));
    return account;
  }

  private static ContainerRequestContext request(final String remoteAddress, final String forwardedHost) {
    final ContainerRequestContext context = mock(ContainerRequestContext.class);
    when(context.getProperty(RemoteAddressFilter.REMOTE_ADDRESS_ATTRIBUTE_NAME)).thenReturn(remoteAddress);
    when(context.getHeaderString("X-Forwarded-Host")).thenReturn(forwardedHost);
    return context;
  }

  private double regionCount(final String region, final String via) {
    final var counter = registry.find(name(TellomiFeedController.class, "region")).tags("region", region, "via", via)
        .counter();
    return counter == null ? 0 : counter.count();
  }

  private double asnTableEmptyCount() {
    final var counter = registry.find(name(TellomiFeedController.class, "asnTableEmpty")).counter();
    return counter == null ? 0 : counter.count();
  }

  @ParameterizedTest
  @CsvSource({
      "1.2.4.77, cn",            // IPv4 in a CN range
      "2408:8000::1, cn",        // IPv6 in a CN range (the table writes the code in lower case: matched case-insensitively)
      "8.8.8.8, global",         // IPv4 in a non-CN range
      "2001:4860:4860::8888, global",
      "9.9.9.9, global",         // not in the table at all
      "zz::zz, global"            // unparsable (IPv6-literal syntax, so no DNS lookup): no exception, no CN
  })
  void regionFromRealTable(final String ip, final String expected) {
    final TellomiFeedController controller = controller(TABLE, new AtomicInteger());

    assertThat(controller.region(account("+14150000000"), request(ip, null))).isEqualTo(expected);
  }

  @Test
  void asnDecisionIsCountedAndTableIsNotReportedEmpty() {
    final TellomiFeedController controller = controller(TABLE, new AtomicInteger());

    controller.region(account("+14150000000"), request("1.2.4.77", null));
    controller.region(account("+14150000000"), request("8.8.8.8", null));

    assertThat(regionCount("cn", "asn")).isEqualTo(1);
    assertThat(regionCount("global", "default")).isEqualTo(1);
    assertThat(asnTableEmptyCount()).isZero();
  }

  @Test
  void emptyTableNeverRecognisesAMainlandIpAndSaysSo() {
    final TellomiFeedController controller = controller(AsnInfoProvider.EMPTY, new AtomicInteger());

    // Documents the known limitation (PR #13): a CN address is "global" while the table is not loaded ...
    assertThat(controller.region(account("+14150000000"), request("1.2.4.77", null))).isEqualTo("global");
    assertThat(controller.region(account("+14150000000"), request("1.2.4.78", null))).isEqualTo("global");
    // ... but it is now visible: one count per decision that had to rely on the empty table.
    assertThat(asnTableEmptyCount()).isEqualTo(2);
    assertThat(regionCount("global", "default")).isEqualTo(2);
  }

  @Test
  void numberAndHostDecideWithoutTheAsnTable() {
    final AtomicInteger providerCalls = new AtomicInteger();
    final TellomiFeedController controller = controller(AsnInfoProvider.EMPTY, providerCalls);

    assertThat(controller.region(account("+8613800138000"), request("9.9.9.9", null))).isEqualTo("cn");
    assertThat(controller.region(account("+14150000000"), request("9.9.9.9", "chat.tellomi.cn"))).isEqualTo("cn");

    assertThat(providerCalls).hasValue(0);
    assertThat(asnTableEmptyCount()).isZero();
    assertThat(regionCount("cn", "number")).isEqualTo(1);
    assertThat(regionCount("cn", "host")).isEqualTo(1);
  }

  @Test
  void noRemoteAddressMeansNoAsnLookupAndNoEmptyTableCount() {
    final AtomicInteger providerCalls = new AtomicInteger();
    final TellomiFeedController controller = controller(AsnInfoProvider.EMPTY, providerCalls);

    assertThat(controller.region(account("+14150000000"), request(null, null))).isEqualTo("global");

    assertThat(providerCalls).hasValue(0);
    assertThat(asnTableEmptyCount()).isZero();
  }
}
