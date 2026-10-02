package com.github.huchangjun118.imagederivativepipelinejava.derivative;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huchangjun118.imagederivativepipelinejava.config.PipelineProperties;
import com.github.huchangjun118.imagederivativepipelinejava.error.ErrorCode;
import com.github.huchangjun118.imagederivativepipelinejava.error.PipelineException;
import com.github.huchangjun118.imagederivativepipelinejava.image.ImageFormat;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 派生图与原图的磁盘存储。
 * <p>
 * 一致性协议：所有产物先写入同目录临时文件、fsync、计算校验和，再通过
 * 原子移动发布；读取时校验校验和与元数据，损坏或写入不完整的产物不会被
 * 当成有效结果。派生结果按最后访问时间与 TTL 过期。
 */
@Component
public class DerivativeStore {

    private static final Logger log = LoggerFactory.getLogger(DerivativeStore.class);
    private static final String ORIGINALS_DIR = "originals";
    private static final String DERIVATIVES_DIR = "derivatives";

    private final PipelineProperties properties;
    private final ObjectMapper objectMapper;

    private Path originalsDir;
    private Path derivativesDir;

    public DerivativeStore(PipelineProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        Path base = properties.getStorageDir().toAbsolutePath().normalize();
        originalsDir = base.resolve(ORIGINALS_DIR);
        derivativesDir = base.resolve(DERIVATIVES_DIR);
        Files.createDirectories(originalsDir);
        Files.createDirectories(derivativesDir);
    }

    public Path originalPath(String imageId) {
        return originalsDir.resolve(imageId + ".bin");
    }

    public Path derivativePath(String derivativeId) {
        return derivativesDir.resolve(derivativeId + ".bin");
    }

    public Path derivativeMetaPath(String derivativeId) {
        return derivativesDir.resolve(derivativeId + ".meta");
    }

    /**
     * 派生图标识：由原图内容标识与规范化参数共同决定。
     */
    public String derivativeId(String imageId, DerivativeParams params) {
        return sha256Hex(imageId + "|" + params.canonical());
    }

    /**
     * 原图内容标识：原始字节的 SHA-256。
     */
    public String originalId(byte[] bytes) {
        return sha256Hex(bytes);
    }

    /**
     * 原子保存原图字节。
     */
    public void storeOriginal(String imageId, byte[] bytes) {
        Path target = originalPath(imageId);
        atomicWrite(target, bytes);
    }

    /**
     * 读取原图字节；文件缺失或校验不符时返回 empty。
     */
    public Optional<byte[]> loadOriginal(String imageId) {
        Path target = originalPath(imageId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            if (!sha256Hex(bytes).equals(imageId)) {
                log.warn("原图校验失败，忽略损坏产物: imageId={}", imageId);
                return Optional.empty();
            }
            return Optional.of(bytes);
        } catch (IOException e) {
            log.warn("原图读取失败: imageId={}, err={}", imageId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 读取有效缓存；文件损坏、校验不符、元数据缺失或已过期时返回 empty。
     */
    public Optional<StoredDerivative> load(String derivativeId) {
        Path bin = derivativePath(derivativeId);
        Path metaFile = derivativeMetaPath(derivativeId);
        if (!Files.exists(bin) || !Files.exists(metaFile)) {
            return Optional.empty();
        }
        DerivativeMeta meta;
        try {
            meta = objectMapper.readValue(metaFile.toFile(), DerivativeMeta.class);
        } catch (IOException e) {
            log.warn("派生图元数据损坏，忽略: derivativeId={}", derivativeId);
            return Optional.empty();
        }
        if (meta == null || meta.sha256 == null || meta.imageId == null
                || !derivativeId.equals(meta.derivativeId)) {
            log.warn("派生图元数据无效，忽略: derivativeId={}", derivativeId);
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (now - meta.lastAccessedAt > properties.getCacheTtlMillis()) {
            log.info("派生图已过期，将重新生成: derivativeId={}", derivativeId);
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(bin);
            if (!sha256Hex(bytes).equals(meta.sha256)) {
                log.warn("派生图校验失败，忽略损坏产物: derivativeId={}", derivativeId);
                return Optional.empty();
            }
            // 续期：更新最后访问时间
            meta.lastAccessedAt = now;
            atomicWrite(metaFile, objectMapper.writeValueAsBytes(meta));
            DerivativeParams params = meta.toParams();
            return Optional.of(new StoredDerivative(derivativeId, meta.imageId, params,
                    bin, bytes.length, meta.createdAt, meta.lastAccessedAt));
        } catch (IOException e) {
            log.warn("派生图读取失败: derivativeId={}, err={}", derivativeId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 原子写入派生图产物与元数据。
     */
    public StoredDerivative store(String imageId, DerivativeParams params, byte[] bytes) {
        String id = derivativeId(imageId, params);
        Path bin = derivativePath(id);
        String checksum = sha256Hex(bytes);
        long now = System.currentTimeMillis();
        DerivativeMeta meta = new DerivativeMeta();
        meta.derivativeId = id;
        meta.imageId = imageId;
        meta.width = params.width();
        meta.height = params.height();
        meta.fitMode = params.fitMode().name();
        meta.format = params.format().name();
        meta.quality = params.quality();
        meta.sha256 = checksum;
        meta.createdAt = now;
        meta.lastAccessedAt = now;
        try {
            atomicWrite(bin, bytes);
            atomicWrite(derivativeMetaPath(id), objectMapper.writeValueAsBytes(meta));
        } catch (IOException e) {
            // 清理可能的残留
            deleteQuietly(bin);
            deleteQuietly(derivativeMetaPath(id));
            throw new PipelineException(ErrorCode.PROCESSING_FAILED,
                    "派生图写入失败: " + e.getMessage(), e, imageId, id, null);
        }
        return new StoredDerivative(id, imageId, params, bin, bytes.length, now, now);
    }

    /**
     * 原子写入：临时文件 + fsync + 原子移动。
     */
    private void atomicWrite(Path target, byte[] bytes) {
        Path parent = target.getParent();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(parent, target.getFileName() + ".tmp-", "");
            Files.write(tmp, bytes);
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicEx) {
                // 某些文件系统不支持原子移动，退化为同目录移动（仍在同一文件系统内）
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
            throw new PipelineException(ErrorCode.PROCESSING_FAILED,
                    "产物写入失败: " + e.getMessage(), e);
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 派生图元数据。
     */
    public static class DerivativeMeta {
        public String derivativeId;
        public String imageId;
        public int width;
        public int height;
        public String fitMode;
        public String format;
        public float quality;
        public String sha256;
        public long createdAt;
        public long lastAccessedAt;

        DerivativeParams toParams() {
            return new DerivativeParams(width, height,
                    DerivativeParams.FitMode.valueOf(fitMode),
                    ImageFormat.valueOf(format), quality);
        }
    }
}
