package com.github.huchangjun118.imagederivativepipelinejava.derivative;

import com.github.huchangjun118.imagederivativepipelinejava.api.ApiException;
import com.github.huchangjun118.imagederivativepipelinejava.config.PipelineProperties;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有界处理执行器：限制并发处理数与排队长度，
 * 支持超时与取消，保证单个超大图或慢请求不会拖垮整批。
 * 队列满时按配置立即拒绝或有限等待后拒绝，拒绝原因可区分。
 */
public class ProcessingExecutor implements AutoCloseable {

    private final PipelineProperties properties;
    private final ArrayBlockingQueue<Runnable> queue;
    private final ThreadPoolExecutor executor;

    public ProcessingExecutor(PipelineProperties properties) {
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "derivative-worker-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = new ThreadPoolExecutor(
                properties.getWorkerThreads(), properties.getWorkerThreads(),
                0L, TimeUnit.MILLISECONDS, queue, factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 提交一个处理任务。队列已满时按配置立即拒绝或有限等待后拒绝。
     */
    public <T> Future<T> submit(Callable<T> task) {
        long deadline = properties.isBlockWhenFull()
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getOfferTimeoutMillis())
                : 0L;
        while (true) {
            try {
                return executor.submit(task);
            } catch (RejectedExecutionException e) {
                if (!properties.isBlockWhenFull() || System.nanoTime() >= deadline) {
                    throw queueSaturated();
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ApiException.tooManyRequests("QUEUE_INTERRUPTED", "排队等待被中断");
                }
            }
        }
    }

    private ApiException queueSaturated() {
        return ApiException.tooManyRequests("QUEUE_FULL",
                "处理队列已满（并发上限 " + properties.getWorkerThreads()
                        + "，排队上限 " + properties.getQueueCapacity() + "），请稍后重试");
    }

    /** 当前排队中的任务数（监控与测试用）。 */
    public int queuedCount() {
        return queue.size();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
