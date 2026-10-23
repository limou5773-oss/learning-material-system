package com.learningmaterial.system.material;

import com.learningmaterial.system.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class ImageValidator {
    private final long maxBytes;

    public ImageValidator(@Value("${FILE_MAX_IMAGE_BYTES:10485760}") long maxBytes) {
        if (maxBytes < 1 || maxBytes > 30L * 1024 * 1024) throw new IllegalArgumentException("图片上限配置无效");
        this.maxBytes = maxBytes;
    }

    public CheckedImage check(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("INVALID_FILE", "请选择图片文件");
        if (file.getSize() > maxBytes) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "图片超过上传上限");
        String filename = file.getOriginalFilename();
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean jpegExtension = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        boolean pngExtension = lower.endsWith(".png");
        if (!jpegExtension && !pngExtension) throw unsupported();
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw invalid("INVALID_FILE", "图片读取失败"); }
        String mime;
        String extension;
        if (jpegExtension && bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
            && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            mime = "image/jpeg"; extension = "jpg";
        } else if (pngExtension && bytes.length >= 8 && (bytes[0] & 0xff) == 0x89
            && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
            && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) {
            mime = "image/png"; extension = "png";
        } else throw unsupported();
        String declared = file.getContentType();
        if (declared != null && !declared.isBlank() && !declared.equalsIgnoreCase("application/octet-stream")
            && !declared.equalsIgnoreCase(mime)) throw unsupported();
        verifyDecode(bytes, extension);
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256不可用", ex); }
        return new CheckedImage(bytes, mime, extension, hash);
    }

    private static void verifyDecode(byte[] bytes, String extension) {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw unsupported();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, false);
                if (!reader.getFormatName().equalsIgnoreCase(extension.equals("jpg") ? "JPEG" : "PNG")) throw unsupported();
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels < 1 || pixels > 40_000_000L) {
                    throw invalid("IMAGE_DIMENSIONS_INVALID", "图片像素过大或无效");
                }
                if (reader.read(0) == null) throw unsupported();
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ApiException api) throw api;
            throw unsupported();
        }
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "仅支持完整的JPEG或PNG图片，且扩展名需匹配");
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public record CheckedImage(byte[] bytes, String mime, String extension, String hash) { }
}
