package com.liubai.diary.share;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

final class ShareImage {
    private ShareImage() {}

    static byte[] render(Path path, boolean crop, int rotation) {
        try {
            BufferedImage original = ImageIO.read(path.toFile());
            if (original == null) throw new IOException("Unrecognized image");
            int width = original.getWidth();
            int height = original.getHeight();
            if (crop) {
                int cropWidth = Math.max(1, Math.min(width, height * 16 / 9));
                int cropHeight = Math.max(1, Math.min(height, width * 9 / 16));
                original = original.getSubimage((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight);
            }
            double angle = Math.toRadians(rotation);
            double sin = Math.abs(Math.sin(angle));
            double cos = Math.abs(Math.cos(angle));
            double rotatedWidth = original.getWidth() * cos + original.getHeight() * sin;
            double rotatedHeight = original.getWidth() * sin + original.getHeight() * cos;
            double scale = Math.min(1, 2048 / Math.max(rotatedWidth, rotatedHeight));
            BufferedImage display = new BufferedImage(Math.max(1, (int) Math.ceil(rotatedWidth * scale - 1e-9)),
                    Math.max(1, (int) Math.ceil(rotatedHeight * scale - 1e-9)), BufferedImage.TYPE_INT_ARGB);
            Graphics2D drawing = display.createGraphics();
            try {
                drawing.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                drawing.translate(display.getWidth() / 2.0, display.getHeight() / 2.0);
                drawing.rotate(angle);
                drawing.scale(scale, scale);
                drawing.translate(-original.getWidth() / 2.0, -original.getHeight() / 2.0);
                drawing.drawImage(original, 0, 0, null);
            } finally {
                drawing.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(display, "png", output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to render approved image", e);
        }
    }
}
