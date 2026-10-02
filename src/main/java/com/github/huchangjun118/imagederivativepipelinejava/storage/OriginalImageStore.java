package com.github.huchangjun118.imagederivativepipelinejava.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 原图存储：按内容哈希寻址，相同字节只保存一份。
 * 写入采用临时文件 + 原子移动，读取端永远只能看到完整文件。
 */
public class OriginalImageStore {

    private final Path dir;

    public OriginalImageStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建原图存储目录: " + dir, e);
        }
    }

    /** 计算内容哈希（同时作为原图标识）。 */
    public static String contentHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 保存原图字节，返回内容寻址的标识。相同内容重复保存返回同一标识。
     */
    public String save(byte[] data) {
        String id = contentHash(data);
        Path target = dir.resolve(id + ".bin");
        if (Files.exists(target)) {
            return id;
        }
        try {
            Files.createDirectories(dir); // 目录可能被外部清理，写入前确保存在
            Path tmp = Files.createTempFile(dir, "upload-", ".tmp");
            try {
                Files.write(tmp, data);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    // 文件系统不支持原子移动时退化为普通移动
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("原图写入失败: " + id, e);
        }
        return id;
    }

    /**
     * 按标识读取原图字节。
     */
    public Optional<byte[]> load(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            return Optional.empty();
        }
        Path target = dir.resolve(id + ".bin");
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
