package com.liubai.diary.auth;

import com.liubai.diary.config.ApiException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class ProfileAvatar {
    private static final int SIZE = 160;

    private ProfileAvatar() {}

    static byte[] thumbnail(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024) {
            throw ApiException.badRequest("头像请选择 2 MB 以内的 PNG 或 JPEG 图片。");
        }
        try (var input = new MemoryCacheImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName()) && !"jpeg".equalsIgnoreCase(reader.getFormatName()))
                    throw invalidImage();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > 4096 || height > 4096 || width < 1 || height < 1)
                    throw ApiException.badRequest("头像的宽和高均须在 1 至 4096 像素之间。");
                BufferedImage original = reader.read(0);
                int side = Math.min(width, height);
                int left = (width - side) / 2;
                int top = (height - side) / 2;
                BufferedImage thumbnail = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                var graphics = thumbnail.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(original, 0, 0, SIZE, SIZE, left, top, left + side, top + side, null);
                } finally {
                    graphics.dispose();
                }
                var output = new ByteArrayOutputStream();
                ImageIO.write(thumbnail, "png", output);
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidImage();
        }
    }

    private static ApiException invalidImage() {
        return ApiException.badRequest("无法读取头像，请上传有效的 PNG 或 JPEG 图片。");
    }
}
