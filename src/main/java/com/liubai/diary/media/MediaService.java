package com.liubai.diary.media;

import com.liubai.diary.config.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaService {
    public static final long MAX_FILE_SIZE = 12L * 1024 * 1024;
    private final MediaMapper media;
    private final Path root;
    public MediaService(MediaMapper media, @Value("${app.media.root:./var/media}") String root) {
        this.media = media;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }
    @Transactional
    public Uploaded upload(long ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("请选择要上传的图片。");
        if (file.getSize() > MAX_FILE_SIZE) throw ApiException.badRequest("单张图片不能超过 12 MB。");
        try { return storeBytes(ownerId,file.getOriginalFilename(),file.getBytes()); }
        catch (IOException e) { throw new IllegalStateException("Image storage failed",e); }
    }
    @Transactional
    public Uploaded uploadBytes(long ownerId,String name,byte[] bytes) {
        if(bytes==null||bytes.length==0) throw ApiException.badRequest("备份图片内容为空。");
        if(bytes.length>MAX_FILE_SIZE) throw ApiException.badRequest("备份中的单张图片超过 12 MB。");
        return storeBytes(ownerId,name,bytes);
    }
    public List<BackupMedia> exportOwned(long ownerId,Set<Long> referenced) {
        List<BackupMedia> output=new java.util.ArrayList<>();
        long totalBytes=0;
        for(MediaMapper.MediaRow row:media.listOwned(ownerId)) {
            if(!referenced.contains(row.getId())) continue;
            try {
                Path path=safeFile(ownerDirectory(ownerId),row.getStoredName());
                if(!Files.isRegularFile(path)) throw ApiException.badRequest("备份中有引用的图片无法读取，备份已取消。");
                totalBytes+=Files.size(path);
                if(totalBytes>105L*1024*1024) throw ApiException.badRequest("备份所引用的图片超过 105 MB，无法生成单个备份文件。");
                output.add(new BackupMedia(row.getId(),row.getOriginalName(),row.getMediaType(),row.getSha256(),
                        java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path))));
            } catch(IOException e) { throw new IllegalStateException("Backup media could not be read",e); }
        }
        if(output.size()!=referenced.size()) throw ApiException.badRequest("备份中有引用的图片不存在，备份已取消。");
        return output;
    }
    private Uploaded storeBytes(long ownerId,String originalName,byte[] bytes) {
        Path destination = null;
        try {
            String type = detectedType(bytes);
            if (type == null || !validImage(bytes, type)) throw ApiException.badRequest("仅支持有效的 PNG、JPEG 或 GIF 图片。");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            MediaMapper.MediaRow duplicate = media.findByHash(ownerId, hash);
            if (duplicate != null) return response(duplicate);
            String ext = switch (type) { case "image/png" -> ".png"; case "image/gif" -> ".gif"; default -> ".jpg"; };
            String storedName = UUID.randomUUID() + ext;
            Path ownerDirectory = ownerDirectory(ownerId);
            Files.createDirectories(ownerDirectory);
            destination = safeFile(ownerDirectory, storedName);
            Path temp = Files.createTempFile(ownerDirectory, ".upload-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            originalName = safeName(originalName);
            MediaMapper.MediaRow row = new MediaMapper.MediaRow(0, ownerId, hash, originalName, type, bytes.length, storedName);
            try {
                media.insert(row);
            } catch (DuplicateKeyException duplicateRace) {
                Files.deleteIfExists(destination);
                MediaMapper.MediaRow existing = media.findByHash(ownerId, hash);
                if (existing != null) return response(existing);
                throw duplicateRace;
            }
            Path savedPath=destination;
            if(TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if(status==STATUS_ROLLED_BACK) try { Files.deleteIfExists(savedPath); } catch(IOException ignored) {}
                    }
                });
            }
            return response(row);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            if (destination != null) try { Files.deleteIfExists(destination); } catch (IOException ignored) {}
            throw new IllegalStateException("Image storage failed", exception);
        }
    }
    public StoredFile get(long ownerId, long id) {
        MediaMapper.MediaRow row = media.findOwned(id, ownerId);
        if (row == null) throw ApiException.notFound();
        Path path = safeFile(ownerDirectory(ownerId), row.getStoredName());
        if (!Files.isRegularFile(path)) throw ApiException.notFound();
        return new StoredFile(path, row.getMediaType(), row.getFileSize(), row.getOriginalName());
    }
    public boolean owns(long ownerId, long id) { return media.findOwned(id, ownerId) != null; }
    public void deleteStored(MediaMapper.MediaRow row) {
        Path path=safeFile(ownerDirectory(row.getOwnerId()),row.getStoredName());
        try { Files.deleteIfExists(path); } catch(IOException ignored) {}
    }
    private Uploaded response(MediaMapper.MediaRow row) {
        return new Uploaded(row.getId(), "/api/media/" + row.getId(), row.getOriginalName());
    }
    private Path ownerDirectory(long ownerId) {
        Path path = root.resolve(Long.toString(ownerId)).normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("Invalid media owner directory");
        return path;
    }
    private Path safeFile(Path directory, String name) {
        Path file = directory.resolve(name).normalize();
        if (!file.startsWith(directory) || !name.matches("[A-Za-z0-9-]{20,40}\\.(png|gif|jpg)")) throw ApiException.notFound();
        return file;
    }
    private String safeName(String name) {
        String value = name == null ? "image" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isEmpty()) value = "image";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }
    private String detectedType(byte[] b) {
        if (b.length >= 8 && (b[0] & 255) == 137 && b[1] == 80 && b[2] == 78 && b[3] == 71 &&
                b[4] == 13 && b[5] == 10 && b[6] == 26 && b[7] == 10) return "image/png";
        if (b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255) return "image/jpeg";
        if (b.length >= 6) {
            String signature = new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
            if (signature.equals("GIF87a") || signature.equals("GIF89a")) return "image/gif";
        }
        return null;
    }
    private boolean validImage(byte[] bytes, String expectedType) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (input == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                String actualType = format.equals("jpg") || format.equals("jpeg") ? "image/jpeg" : "image/" + format;
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return actualType.equals(expectedType) && width > 0 && height > 0 && (long) width * height <= 40_000_000L
                        && reader.read(0) != null;
            } finally { reader.dispose(); }
        } catch (IOException e) { return false; }
    }
    public record Uploaded(long id, String url, String originalName) {}
    public record StoredFile(Path path, String mediaType, long size, String originalName) {}
    public record BackupMedia(long id,String name,String type,String sha256,String data) {}
}
