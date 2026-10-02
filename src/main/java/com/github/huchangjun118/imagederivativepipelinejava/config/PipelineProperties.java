package com.github.huchangjun118.imagederivativepipelinejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 图片处理流水线配置项。
 */
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    /** 产物存储根目录。 */
    private Path storageDir = Path.of("data");

    /** 上传原图大小上限（字节）。 */
    private long maxUploadBytes = 20L * 1024 * 1024;

    /** 原图单边像素上限。 */
    private int maxSourceDimension = 8000;

    /** 原图总像素上限（宽*高）。 */
    private long maxSourcePixels = 40_000_000L;

    /** 派生图单边像素上限。 */
    private int maxTargetDimension = 4096;

    /** 派生图总像素上限。 */
    private long maxTargetPixels = 16_000_000L;

    /** 处理线程数（并发上界）。 */
    private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors());

    /** 等待队列容量（超过即拒绝）。 */
    private int queueCapacity = 128;

    /** 单任务处理超时（毫秒）。 */
    private long taskTimeoutMillis = 30_000L;

    /** 派生结果过期时间（毫秒），按最后访问时间续期。 */
    private long cacheTtlMillis = 7L * 24 * 60 * 60 * 1000L;

    public Path getStorageDir() { return storageDir; }
    public void setStorageDir(Path storageDir) { this.storageDir = storageDir; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public int getMaxSourceDimension() { return maxSourceDimension; }
    public void setMaxSourceDimension(int maxSourceDimension) { this.maxSourceDimension = maxSourceDimension; }
    public long getMaxSourcePixels() { return maxSourcePixels; }
    public void setMaxSourcePixels(long maxSourcePixels) { this.maxSourcePixels = maxSourcePixels; }
    public int getMaxTargetDimension() { return maxTargetDimension; }
    public void setMaxTargetDimension(int maxTargetDimension) { this.maxTargetDimension = maxTargetDimension; }
    public long getMaxTargetPixels() { return maxTargetPixels; }
    public void setMaxTargetPixels(long maxTargetPixels) { this.maxTargetPixels = maxTargetPixels; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public long getTaskTimeoutMillis() { return taskTimeoutMillis; }
    public void setTaskTimeoutMillis(long taskTimeoutMillis) { this.taskTimeoutMillis = taskTimeoutMillis; }
    public long getCacheTtlMillis() { return cacheTtlMillis; }
    public void setCacheTtlMillis(long cacheTtlMillis) { this.cacheTtlMillis = cacheTtlMillis; }
}
