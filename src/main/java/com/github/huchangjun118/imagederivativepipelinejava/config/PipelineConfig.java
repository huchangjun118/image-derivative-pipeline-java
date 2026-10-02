package com.github.huchangjun118.imagederivativepipelinejava.config;

import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeCache;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.ImageProcessingService;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.ImageProcessor;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.ProcessingExecutor;
import com.github.huchangjun118.imagederivativepipelinejava.storage.OriginalImageStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 派生管线的 Bean 装配。
 */
@Configuration
public class PipelineConfig {

    @Bean
    public ImageProcessor imageProcessor() {
        return new ImageProcessor();
    }

    @Bean
    public OriginalImageStore originalImageStore(PipelineProperties properties) {
        return new OriginalImageStore(Path.of(properties.getOriginalDir()));
    }

    @Bean
    public DerivativeCache derivativeCache(PipelineProperties properties) {
        return new DerivativeCache(Path.of(properties.getCacheDir()));
    }

    @Bean(destroyMethod = "close")
    public ProcessingExecutor processingExecutor(PipelineProperties properties) {
        return new ProcessingExecutor(properties);
    }

    @Bean
    public ImageProcessingService imageProcessingService(PipelineProperties properties,
                                                         OriginalImageStore originalImageStore,
                                                         DerivativeCache derivativeCache,
                                                         ImageProcessor imageProcessor,
                                                         ProcessingExecutor processingExecutor) {
        return new ImageProcessingService(properties, originalImageStore, derivativeCache,
                imageProcessor, processingExecutor);
    }
}
