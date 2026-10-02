package com.github.huchangjun118.imagederivativepipelinejava.derivative;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;

/**
 * 派生结果缓存：同一原图与同一参数组合只计算一次。
 * 文件格式：magic(4) + payloadLength(4) + sha256(32) + payload。
 * 写入采用临时文件 + 原子移动；读取时校验长度与哈希，
 * 损坏或写入不完整的产物不会被当成有效结果返回。
 */
public class DerivativeCache {

    private static final byte[] MAGIC = {'D', 'C', '1', 0};
    private static final int HEADER_LEN = 4 + 4 + 32;

    private final Path dir;

    public DerivativeCache(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建缓存目录: " + dir, e);
        }
    }

    private Path pathFor(String cacheKey) {
        return dir.resolve(cacheKey + ".cache");
    }

    /**
     * 读取缓存。返回空表示未命中或缓存损坏（调用方应重新计算）。
     */
    public Optional<byte[]> get(String cacheKey) {
        Path path = pathFor(cacheKey);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            byte[] raw = Files.readAllBytes(path);
            if (raw.length < HEADER_LEN) {
                return Optional.empty();
            }
            ByteBuffer buf = ByteBuffer.wrap(raw);
            byte[] magic = new byte[4];
            buf.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                return Optional.empty();
            }
            int len = buf.getInt();
            byte[] expectedHash = new byte[32];
            buf.get(expectedHash);
            if (len < 0 || raw.length != HEADER_LEN + len) {
                return Optional.empty();
            }
            byte[] payload = Arrays.copyOfRange(raw, HEADER_LEN, raw.length);
            if (!Arrays.equals(sha256(payload), expectedHash)) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 原子写入缓存。写入失败不影响后续重新计算。
     */
    public void put(String cacheKey, byte[] bytes) {
        Path target = pathFor(cacheKey);
        if (Files.exists(target)) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + bytes.length);
        buf.put(MAGIC);
        buf.putInt(bytes.length);
        buf.put(sha256(bytes));
        buf.put(bytes);
        try {
            Files.createDirectories(dir); // 目录可能被外部清理，写入前确保存在
            Path tmp = Files.createTempFile(dir, "put-", ".tmp");
            try {
                Files.write(tmp, buf.array());
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            // 缓存写入失败不致命：下次请求重新计算
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
