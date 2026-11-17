package com.liubai.diary.share;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShareImageTest {
    @TempDir Path directory;

    @Test
    void processedImageContainsOnlyTheVisibleCropAndRotation() throws Exception {
        BufferedImage original = new BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 160; y++) {
            for (int x = 0; x < 160; x++) {
                int color = y < 35 || y >= 125 ? Color.RED.getRGB() : x < 80 ? Color.BLUE.getRGB() : Color.GREEN.getRGB();
                original.setRGB(x, y, color);
            }
        }
        Path source = directory.resolve("photo.png");
        ImageIO.write(original, "png", source.toFile());

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(ShareImage.render(source, true, 90)));

        assertEquals(90, result.getWidth());
        assertEquals(160, result.getHeight());
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                assertEquals(y < 80 ? Color.BLUE.getRGB() : Color.GREEN.getRGB(), result.getRGB(x, y));
            }
        }
    }
}
