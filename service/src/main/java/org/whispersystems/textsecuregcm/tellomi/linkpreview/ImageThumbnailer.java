/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/// 卡片图：服务端下好、缩成缩略图再交给客户端，**客户端不直连原站的图床**（不然图床会看到用户 IP，§6.5.2）。
///
/// - 先按魔数认格式（JPEG / PNG / GIF / WebP），不认的丢掉；
/// - JPEG / PNG / GIF：先读尺寸（不解码像素），超过 [#MAX_SOURCE_PIXELS] 丢掉（解压炸弹）；长边缩到 [#MAX_EDGE]，
///   重新编码成 JPEG（顺带去掉 EXIF 等元数据）；
/// - WebP（JDK 没有解码器）：不超过 [#MAX_PASSTHROUGH_BYTES] 就原样交给客户端（客户端本来就会校验、重编码图片），否则丢掉。
public final class ImageThumbnailer {

  public static final int MAX_EDGE = 800;
  public static final long MAX_SOURCE_PIXELS = 40_000_000L;
  public static final int MAX_PASSTHROUGH_BYTES = 512 * 1024;
  public static final int MIN_EDGE = 16;

  public record Thumbnail(byte[] bytes, String contentType, int width, int height) {
  }

  private ImageThumbnailer() {
  }

  enum Format { JPEG, PNG, GIF, WEBP }

  static Optional<Format> sniff(final byte[] b) {
    if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
      return Optional.of(Format.JPEG);
    }
    if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
      return Optional.of(Format.PNG);
    }
    if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
      return Optional.of(Format.GIF);
    }
    if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
        && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
      return Optional.of(Format.WEBP);
    }
    return Optional.empty();
  }

  public static Optional<Thumbnail> thumbnail(final byte[] source) {
    final Optional<Format> format = sniff(source);
    if (format.isEmpty()) {
      return Optional.empty();
    }
    if (format.get() == Format.WEBP) {
      return source.length <= MAX_PASSTHROUGH_BYTES
          ? Optional.of(new Thumbnail(source, "image/webp", 0, 0))
          : Optional.empty();
    }
    try (final ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
      if (iis == null) {
        return Optional.empty();
      }
      final Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (!readers.hasNext()) {
        return Optional.empty();
      }
      final ImageReader reader = readers.next();
      try {
        reader.setInput(iis, true, true);
        final int w = reader.getWidth(0);
        final int h = reader.getHeight(0);
        if (w < MIN_EDGE || h < MIN_EDGE || (long) w * h > MAX_SOURCE_PIXELS) {
          return Optional.empty();
        }
        final BufferedImage src = reader.read(0);
        final double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
        final int tw = Math.max(1, (int) Math.round(w * scale));
        final int th = Math.max(1, (int) Math.round(h * scale));
        final BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = dst.createGraphics();
        try {
          g.setColor(java.awt.Color.WHITE);   // 透明 PNG 铺白底
          g.fillRect(0, 0, tw, th);
          g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
          g.drawImage(src, 0, 0, tw, th, null);
        } finally {
          g.dispose();
        }
        return Optional.of(new Thumbnail(encodeJpeg(dst), "image/jpeg", tw, th));
      } finally {
        reader.dispose();
      }
    } catch (final IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static byte[] encodeJpeg(final BufferedImage image) throws IOException {
    final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (final ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      final ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(0.82f);
      writer.write(null, new IIOImage(image, null, null), param);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }
}
