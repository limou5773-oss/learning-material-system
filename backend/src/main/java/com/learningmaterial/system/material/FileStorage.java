package com.learningmaterial.system.material;

import com.learningmaterial.system.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

@Component
public class FileStorage {
    private final Path root;

    public FileStorage(@Value("${STORAGE_ROOT:}") String configuredRoot) {
        String location = configuredRoot.isBlank()
            ? Path.of(System.getProperty("user.home"), ".learning-material-system", "files").toString()
            : configuredRoot;
        root = Path.of(location).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            ownerOnly(root);
        } catch (IOException ex) {
            throw new IllegalStateException("文件存储目录不可用", ex);
        }
    }

    public String store(byte[] data, String extension) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String key = id.substring(0, 2) + "/" + id + "." + extension;
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            ownerOnly(path.getParent());
            Files.write(path, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ownerOnly(path);
            return key;
        } catch (IOException ex) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_FAILED", "文件保存失败");
        }
    }

    public Path open(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_MISSING", "文件暂时无法读取");
        }
        return path;
    }

    public void deleteQuietly(String key) {
        try { Files.deleteIfExists(resolve(key)); } catch (IOException ignored) { }
    }

    private Path resolve(String key) {
        if (key == null || !key.matches("[0-9a-f]{2}/[0-9a-f]{32}\\.(jpg|png|pdf)")) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_KEY_INVALID", "文件暂时无法读取");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("文件路径无效");
        return path;
    }

    private static void ownerOnly(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.isDirectory(path)
                ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) { /* Windows 使用当前账户的目录 ACL */ }
    }
}
