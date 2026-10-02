package com.github.huchangjun118.imagederivativepipelinejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片派生管线的配置项，全部可通过 application.properties 覆盖。
 */
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    /** 上传原图允许的最大字节数。 */
    private long maxUploadBytes = 20L * 1024 * 1024;

    /** 解码后允许的最大像素数（宽*高），防止超大图拖垮进程。 */
    private long maxPixels = 40_000_000L;

    /** 派生图允许的最大边长。 */
    private int maxDimension = 8192;

    /** 派生任务并发处理线程数（处理能力上界）。 */
    private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    /** 等待执行的派生任务队列容量，超出后拒绝。 */
    private int queueCapacity = 64;

    /** 队列已满时是否继续等待（true=有限等待后拒绝，false=立即拒绝）。 */
    private boolean blockWhenFull = false;

    /** 队列已满时最多等待毫秒数，仅在 blockWhenFull=true 时生效。 */
    private long offerTimeoutMillis = 2000;

    /** 单个派生任务的处理超时毫秒数。 */
    private long processingTimeoutMillis = 60_000;

    /** 单个任务允许占用的最大堆内字节数（估算），超出拒绝。 */
    private long maxDecodedBytes = 512L * 1024 * 1024;

    /** 派生图缓存目录。 */
    private String cacheDir = "build/derivative-cache";

    /** 原图存储目录。 */
    private String originalDir = "build/originals";

    /** 缓存版本号，写入缓存键，升级处理逻辑时可整体失效旧缓存。 */
    private int cacheVersion = 1;

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    public long getMaxPixels() {
        return maxPixels;
    }

    public void setMaxPixels(long maxPixels) {
        this.maxPixels = maxPixels;
    }

    public int getMaxDimension() {
        return maxDimension;
    }

    public void setMaxDimension(int maxDimension) {
        this.maxDimension = maxDimension;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public boolean isBlockWhenFull() {
        return blockWhenFull;
    }

    public void setBlockWhenFull(boolean blockWhenFull) {
        this.blockWhenFull = blockWhenFull;
    }

    public long getOfferTimeoutMillis() {
        return offerTimeoutMillis;
    }

    public void setOfferTimeoutMillis(long offerTimeoutMillis) {
        this.offerTimeoutMillis = offerTimeoutMillis;
    }

    public long getProcessingTimeoutMillis() {
        return processingTimeoutMillis;
    }

    public void setProcessingTimeoutMillis(long processingTimeoutMillis) {
        this.processingTimeoutMillis = processingTimeoutMillis;
    }

    public long getMaxDecodedBytes() {
        return maxDecodedBytes;
    }

    public void setMaxDecodedBytes(long maxDecodedBytes) {
        this.maxDecodedBytes = maxDecodedBytes;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public String getOriginalDir() {
        return originalDir;
    }

    public void setOriginalDir(String originalDir) {
        this.originalDir = originalDir;
    }

    public int getCacheVersion() {
        return cacheVersion;
    }

    public void setCacheVersion(int cacheVersion) {
        this.cacheVersion = cacheVersion;
    }
}
