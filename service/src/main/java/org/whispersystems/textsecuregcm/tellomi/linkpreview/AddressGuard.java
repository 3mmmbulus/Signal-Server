/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/// SSRF 的地址一关（ADR-0063 §6.5.4）：每一跳连之前先解析，**任何一个**解析结果落在下面的段里就整跳作废。
///
/// 和客户端（§6.2）的区别：客户端为了大陆的 fake-ip 代理放过 `198.18.0.0/15`；服务端没有这种代理，**一并拦掉**。
/// 阿里云 ECS 的元数据地址 `100.100.100.200` 落在 `100.64.0.0/10`，拦。
///
/// 解析和连接之间有时间差（JDK HttpClient 没有 DNS 钩子）：只有白名单里的大站主机名会被解析，且只走 https、标准证书校验，
/// DNS rebinding 到内网的地址拿不到这些域名的有效证书，连不上。
public final class AddressGuard {

  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  public static final Resolver SYSTEM = InetAddress::getAllByName;

  private AddressGuard() {
  }

  public static boolean isForbidden(final InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    final byte[] b = address.getAddress();
    if (address instanceof Inet4Address) {
      return isForbiddenV4(b);
    }
    if (address instanceof Inet6Address) {
      // IPv4-mapped ::ffff:a.b.c.d 和 IPv4-compatible ::a.b.c.d
      boolean prefixZero = true;
      for (int i = 0; i < 10; i++) {
        if (b[i] != 0) {
          prefixZero = false;
          break;
        }
      }
      if (prefixZero && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
        return isForbiddenV4(new byte[]{b[12], b[13], b[14], b[15]});
      }
      final int first = b[0] & 0xff;
      if ((first & 0xfe) == 0xfc) {           // fc00::/7 ULA
        return true;
      }
      if (first == 0xfe && (b[1] & 0xc0) == 0x80) {   // fe80::/10
        return true;
      }
      if (first == 0xfe && (b[1] & 0xc0) == 0xc0) {   // fec0::/10（已废弃的 site-local）
        return true;
      }
      if (first == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) {  // 2001:db8::/32 文档段
        return true;
      }
      if (first == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) { // 64:ff9b::/96 NAT64
        return isForbiddenV4(new byte[]{b[12], b[13], b[14], b[15]});
      }
      if (first == 0x20 && b[1] == 0x02) {    // 2002::/16 6to4：嵌的 IPv4 在第 2–5 字节
        return isForbiddenV4(new byte[]{b[2], b[3], b[4], b[5]});
      }
      return false;
    }
    return true;
  }

  private static boolean isForbiddenV4(final byte[] b) {
    final int a0 = b[0] & 0xff;
    final int a1 = b[1] & 0xff;
    return a0 == 0                                   // 0.0.0.0/8
        || a0 == 10                                  // 10/8
        || (a0 == 100 && (a1 & 0xc0) == 64)          // 100.64/10（CGNAT；阿里云元数据 100.100.100.200）
        || a0 == 127                                 // 127/8
        || (a0 == 169 && a1 == 254)                  // 169.254/16
        || (a0 == 172 && (a1 & 0xf0) == 16)          // 172.16/12
        || (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 0)   // 192.0.0/24
        || (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 2)   // 192.0.2/24 TEST-NET-1
        || (a0 == 192 && a1 == 168)                  // 192.168/16
        || (a0 == 198 && (a1 & 0xfe) == 18)          // 198.18/15（服务端不放过）
        || (a0 == 198 && a1 == 51 && (b[2] & 0xff) == 100)  // TEST-NET-2
        || (a0 == 203 && a1 == 0 && (b[2] & 0xff) == 113)   // TEST-NET-3
        || a0 >= 224;                                // 组播 + 240/4 保留 + 广播
  }

  /// 解析 host，全部地址都得是公网，不然抛 [ForbiddenAddressException]。
  public static void check(final Resolver resolver, final String host) throws UnknownHostException {
    final InetAddress[] addresses = resolver.resolve(host);
    if (addresses == null || addresses.length == 0) {
      throw new UnknownHostException();
    }
    for (final InetAddress a : addresses) {
      if (isForbidden(a)) {
        throw new ForbiddenAddressException();
      }
    }
  }

  public static final class ForbiddenAddressException extends UnknownHostException {
    public ForbiddenAddressException() {
      super("forbidden address");
    }
  }
}
