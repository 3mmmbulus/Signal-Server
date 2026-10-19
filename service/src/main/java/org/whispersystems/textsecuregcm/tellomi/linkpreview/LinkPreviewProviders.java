/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/// 服务端代抓的白名单（ADR-0063 §6.5.1）。**只有下面这几家**（owner 2026-10-19 定四家：公众号、知乎含专栏、微博、小红书；
/// 产品经理同日批准扩到抖音、淘宝 / 天猫商品页）；加任何一家（或给一家换 UA、加图片主机）都要 owner / 产品经理
/// 批准，改这里、过评审、随服务端发版。客户端随包的同一张表在 `TellomiServerLinkPreview`（iOS）。
///
/// - 页面主机精确匹配（不做通配）；重定向的每一跳都要还在**同一家**的页面主机里，不然这次作废（微博的访客验证页
///   `passport.weibo.com`、`visitor.passport.weibo.cn` 不在表里，所以访客墙 = 拿不到，不会去「过」它）。
/// - 图片主机按后缀匹配（各家图床子域很多），只收 https。
/// - UA：公众号默认用 iPhone Safari 的 UA（ADR-0063 §3.3 第 1 条的例外）；其余各家默认 `WhatsApp/2`。
///   产品经理 2026-10-19 (4)：默认 UA 抓不到（没标题 / 403 / 登录或验证页）就自动用 Safari UA 重试一次，不再逐家审批
///   （[LinkPreviewFetcher#fetch]）。不带 cookie、不登录、不用私有接口、不解验证码。
public final class LinkPreviewProviders {

  public static final String WHATSAPP_UA = "WhatsApp/2";
  public static final String SAFARI_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
      + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";

  /// @param id                 provider id，与注册表 `links/providers/brand/*.toml` 的 id 相同
  /// @param pageHosts          页面主机（精确）
  /// @param imageHostSuffixes  图片主机：等于其中一个、或以 "." + 它结尾
  /// @param userAgent          抓页面和图片用的 UA
  /// @param stripParams        抓取前删掉的查询参数（分享者标识、追踪参数）；`*` 结尾表示前缀
  /// @param blockedPathPrefixes 落到这些路径（登录、验证码、404 页）= 拿不到
  /// @param genericTitles      站点级的通用标题（不是这条内容的）= 当作没标题；`*` 结尾表示前缀
  /// @param placeholderTitles  占位标题：页面模板的标题（比如淘宝 / 天猫的「商品详情页」），**又没有图**时当作没标题
  ///                           （会触发 Safari 重试；重试过了就是没有卡片 → 204 → 品牌壳）。有图时照常出卡。
  ///                           精确匹配（去掉首尾空白）；`*` 结尾表示前缀。2026-10-19 成都出口实测加的。
  public record Provider(String id, Set<String> pageHosts, Set<String> imageHostSuffixes, String userAgent,
                         List<String> stripParams, List<String> blockedPathPrefixes, Set<String> genericTitles,
                         Set<String> placeholderTitles) {

    /// 没有占位标题的旧写法
    public Provider(final String id, final Set<String> pageHosts, final Set<String> imageHostSuffixes,
        final String userAgent, final List<String> stripParams, final List<String> blockedPathPrefixes,
        final Set<String> genericTitles) {
      this(id, pageHosts, imageHostSuffixes, userAgent, stripParams, blockedPathPrefixes, genericTitles, Set.of());
    }

    public boolean isPageHost(final String host) {
      return pageHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    public boolean isImageHost(final String host) {
      final String h = host.toLowerCase(Locale.ROOT);
      for (final String suffix : imageHostSuffixes) {
        if (h.equals(suffix) || h.endsWith("." + suffix)) {
          return true;
        }
      }
      return false;
    }

    public boolean isBlockedPath(final String path) {
      final String p = path == null ? "" : path;
      for (final String prefix : blockedPathPrefixes) {
        if (p.startsWith(prefix)) {
          return true;
        }
      }
      return false;
    }

    public boolean isGenericTitle(final String title) {
      for (final String g : genericTitles) {
        if (g.endsWith("*") ? title.startsWith(g.substring(0, g.length() - 1)) : title.equals(g)) {
          return true;
        }
      }
      return false;
    }

    public boolean isPlaceholderTitle(final String title) {
      final String t = title.strip();
      for (final String g : placeholderTitles) {
        if (g.endsWith("*") ? t.startsWith(g.substring(0, g.length() - 1)) : t.equals(g)) {
          return true;
        }
      }
      return false;
    }

    public boolean isStrippedParam(final String name) {
      for (final String s : stripParams) {
        if (s.endsWith("*") ? name.startsWith(s.substring(0, s.length() - 1)) : name.equals(s)) {
          return true;
        }
      }
      return false;
    }
  }

  public static final Provider WEIXIN_MP = new Provider("weixin-mp",
      Set.of("mp.weixin.qq.com"),
      Set.of("mmbiz.qpic.cn", "mmbiz.qlogo.cn"),
      SAFARI_UA,
      List.of("chksm", "mpshare", "scene", "srcid", "sharer_shareinfo", "sharer_shareinfo_first", "sharer_sharetime",
          "sharer_shareid", "xtrack", "subscene", "clicktime", "enterid", "ascene", "devicetype", "version", "nettype",
          "lang", "exportkey", "pass_ticket", "wx_header", "poc_token", "utm_*"),
      List.of("/mp/wappoc_appmsgcaptcha", "/mp/profile_ext", "/cgi-bin/"),
      Set.of("微信公众平台", "", "微信"));

  public static final Provider ZHIHU = new Provider("zhihu",
      Set.of("www.zhihu.com", "zhihu.com", "zhuanlan.zhihu.com"),
      Set.of("zhimg.com"),
      WHATSAPP_UA,
      List.of("utm_psn", "utmPsn", "share_code", "utm_*"),
      List.of("/signin", "/signup", "/account/unhuman", "/404"),
      Set.of("知乎", "知乎 - 有问题，就会有答案", "安全验证 - 知乎", "知乎，让每一次点击都充满意义 —— 欢迎来到知乎，发现问题背后的世界。"));

  public static final Provider WEIBO = new Provider("weibo",
      Set.of("weibo.com", "www.weibo.com", "m.weibo.cn"),
      Set.of("sinaimg.cn"),
      WHATSAPP_UA,
      List.of("sourceType", "from", "wm", "featurecode", "type", "jumpfrom", "s_channel", "s_trans", "luicode", "lfid",
          "display", "retcode", "sudaref", "utm_*"),
      List.of("/login", "/signup", "/visitor"),
      Set.of("微博", "Sina Visitor System", "新浪微博", "微博-随时随地发现新鲜事", "Sina Visitor System 新浪访客系统"));

  public static final Provider XIAOHONGSHU = new Provider("xiaohongshu",
      Set.of("www.xiaohongshu.com", "xiaohongshu.com", "xhslink.com"),
      Set.of("xhscdn.com"),
      WHATSAPP_UA,
      List.of("xsec_token", "xsec_source", "app_platform", "app_version", "share_from_user_hidden", "type",
          "author_share", "xhsshare", "shareRedId", "apptime", "share_id", "ignoreEngage", "exSource", "utm_*"),
      List.of("/404", "/login", "/website-login", "/sec_"),
      Set.of("小红书", "小红书 - 你访问的页面不见了", "小红书 - 你的生活指南", "小红书_沪ICP备13030189号"));

  /// 抖音：`v.douyin.com` 短链 302 到 `www.iesdouyin.com/share/video/…` 或 `www.douyin.com/video/…`，都在表里。
  /// 2026-10-19 东京出口：页面是 JS 壳，只有「在抖音记录美好生活… - 抖音」这种通用标题（当作没标题）；2026-10-19 成都阿里云出口同样 3/3 没标题。
  public static final Provider DOUYIN = new Provider("douyin",
      Set.of("www.douyin.com", "douyin.com", "www.iesdouyin.com", "iesdouyin.com", "v.douyin.com", "live.douyin.com"),
      Set.of("douyinpic.com", "douyinstatic.com"),
      WHATSAPP_UA,
      List.of("previous_page", "region", "mid", "u_code", "did", "iid", "with_sec_did", "titleType", "share_sign",
          "share_version", "ts", "from_aid", "from_ssr", "from", "source", "utm_*", "modeFrom"),
      List.of("/login", "/passport", "/404"),
      Set.of("抖音", "在抖音记录美好生活*", "抖音-记录美好生活", "抖音 - 记录美好生活", "验证码中间页"));

  /// 淘宝商品页。`m.tb.cn` / `e.tb.cn` / `s.tb.cn` 分享短链回的是 JS 跳转页、没有 Location（服务端不执行 JS），不收；
  /// 国际站 `m.intl.taobao.com`、登录 `login.taobao.com` 不在表里 = 拿不到。
  public static final Provider TAOBAO = new Provider("taobao",
      Set.of("item.taobao.com", "h5.m.taobao.com", "main.m.taobao.com", "market.m.taobao.com"),
      Set.of("alicdn.com"),
      WHATSAPP_UA,
      List.of("spm", "tk", "sku_properties", "ali_refid", "ali_trackid", "scm", "pvid", "utparam", "suid",
          "shareUniqueId", "sourceType", "un", "share_crt_v", "un_site", "ut_sk", "sp_tk", "cpp", "shareurl",
          "short_name", "bxsign", "app", "tbSocialPopKey", "wxsign", "price", "stats_click", "utm_*"),
      List.of("/login", "/member/login", "/punish", "/404"),
      Set.of("淘宝", "淘宝网", "淘宝网 - 淘！我喜欢", "手机淘宝网", "商品详情", "宝贝详情", "验证码拦截"),
      // 2026-10-19 成都出口：Safari 拿到的是 `<title>商品详情页</title>`、没有 og:image —— 模板标题，不是这件商品
      Set.of("商品详情页", "宝贝详情页"));

  /// 天猫商品页（天猫超市同理）。天猫的 App 分享走 m.tb.cn（见淘宝）。
  public static final Provider TMALL = new Provider("tmall",
      Set.of("detail.tmall.com", "detail.m.tmall.com", "chaoshi.detail.tmall.com"),
      Set.of("alicdn.com"),
      WHATSAPP_UA,
      List.of("spm", "tbpm", "_u", "skuId", "ali_refid", "ali_trackid", "scm", "pvid", "utparam", "from", "utm_*"),
      List.of("/login", "/punish", "/404"),
      Set.of("天猫", "天猫tmall.com", "天猫Tmall.com--理想生活上天猫", "商品详情", "验证码拦截"),
      // 同一套商品详情模板（与淘宝同口径）
      Set.of("商品详情页", "宝贝详情页"));

  public static final List<Provider> ALL = List.of(WEIXIN_MP, ZHIHU, WEIBO, XIAOHONGSHU, DOUYIN, TAOBAO, TMALL);

  private LinkPreviewProviders() {
  }

  public static Optional<Provider> forPageHost(final String host) {
    if (host == null) {
      return Optional.empty();
    }
    return ALL.stream().filter(p -> p.isPageHost(host)).findFirst();
  }
}
