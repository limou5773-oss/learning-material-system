package com.learningmaterial.system.util;

import com.learningmaterial.system.common.ApiException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PdfValidator {
    private final long maxBytes;

    public PdfValidator(@Value("${FILE_MAX_PDF_BYTES:31457280}") long maxBytes) {
        if (maxBytes < 1 || maxBytes > 32L * 1024 * 1024) throw new IllegalArgumentException("PDF上限配置无效");
        this.maxBytes = maxBytes;
    }

    public CheckedPdf check(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("INVALID_FILE", "请选择PDF文件");
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "PDF超过上传上限");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "文件扩展名必须为.pdf");
        }
        String declared = file.getContentType();
        if (declared != null && !declared.isBlank() && !declared.equalsIgnoreCase("application/pdf")
            && !declared.equalsIgnoreCase("application/x-pdf")
            && !declared.equalsIgnoreCase("application/octet-stream")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "文件声明类型不是PDF");
        }
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw invalid("INVALID_FILE", "PDF读取失败"); }
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D'
            || bytes[3] != 'F' || bytes[4] != '-') {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "文件内容不是PDF");
        }
        int pages;
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw invalid("PDF_ENCRYPTED", "加密PDF暂不支持，请先移除密码");
            pages = document.getNumberOfPages();
            if (pages < 1 || pages > 100) throw invalid("PDF_PAGE_COUNT", "PDF页数必须在1到100页之间");
        } catch (InvalidPasswordException ex) {
            throw invalid("PDF_ENCRYPTED", "加密PDF暂不支持，请先移除密码");
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ApiException api) throw api;
            throw invalid("PDF_DAMAGED", "PDF损坏或无法解析");
        }
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256不可用", ex); }
        return new CheckedPdf(bytes, hash, pages);
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public record CheckedPdf(byte[] bytes, String hash, int pages) { }
}
