/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.tellomi.linkpreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageThumbnailerTest {

  @Test
  void largeImagesAreScaledAndReencodedAsJpeg() throws Exception {
    final ImageThumbnailer.Thumbnail t = ImageThumbnailer.thumbnail(LinkPreviewFetcherTest.png(1200, 2400)).orElseThrow();
    assertEquals("image/jpeg", t.contentType());
    assertEquals(400, t.width());
    assertEquals(800, t.height());
    final BufferedImage back = ImageIO.read(new ByteArrayInputStream(t.bytes()));
    assertEquals(400, back.getWidth());
  }

  @Test
  void smallImagesKeepTheirSize() throws Exception {
    final ImageThumbnailer.Thumbnail t = ImageThumbnailer.thumbnail(LinkPreviewFetcherTest.png(300, 200)).orElseThrow();
    assertEquals(300, t.width());
    assertEquals(200, t.height());
  }

  @Test
  void gifIsAccepted() throws Exception {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "gif", out);
    assertTrue(ImageThumbnailer.thumbnail(out.toByteArray()).isPresent());
  }

  @Test
  void garbageSvgAndTinyImagesAreDropped() throws Exception {
    assertFalse(ImageThumbnailer.thumbnail("<html>not an image</html>".getBytes(StandardCharsets.UTF_8)).isPresent());
    assertFalse(ImageThumbnailer.thumbnail("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes()).isPresent());
    assertFalse(ImageThumbnailer.thumbnail(LinkPreviewFetcherTest.png(8, 8)).isPresent());
    final byte[] png = LinkPreviewFetcherTest.png(100, 100);
    final byte[] truncated = java.util.Arrays.copyOf(png, 40);
    assertFalse(ImageThumbnailer.thumbnail(truncated).isPresent());
  }

  @Test
  void webpPassesThroughOnlyWhenSmall() {
    final byte[] small = new byte[1024];
    System.arraycopy("RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.US_ASCII), 0, small, 0, 12);
    final ImageThumbnailer.Thumbnail t = ImageThumbnailer.thumbnail(small).orElseThrow();
    assertEquals("image/webp", t.contentType());
    final byte[] big = new byte[ImageThumbnailer.MAX_PASSTHROUGH_BYTES + 1];
    System.arraycopy(small, 0, big, 0, 12);
    assertFalse(ImageThumbnailer.thumbnail(big).isPresent());
  }
}
