package com.github.huchangjun118.imagederivativepipelinejava.web;

import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeParams;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.StoredDerivative;
import com.github.huchangjun118.imagederivativepipelinejava.error.ErrorCode;
import com.github.huchangjun118.imagederivativepipelinejava.error.PipelineException;
import com.github.huchangjun118.imagederivativepipelinejava.image.ImageFormat;
import com.github.huchangjun118.imagederivativepipelinejava.image.SourceImage;
import com.github.huchangjun118.imagederivativepipelinejava.pipeline.ProcessingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片接收与派生图下载接口。
 */
@RestController
@RequestMapping("/api")
public class ImageController {

    private final ProcessingService processingService;

    public ImageController(ProcessingService processingService) {
        this.processingService = processingService;
    }

    /**
     * 上传原图（multipart）。
     */
    @PostMapping("/images")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER, "上传文件为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new PipelineException(ErrorCode.DECODE_FAILED, "上传内容读取失败: " + e.getMessage(), e);
        }
        SourceImage image = processingService.upload(bytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imageId", image.imageId());
        body.put("format", image.format().name());
        body.put("width", image.width());
        body.put("height", image.height());
        body.put("orientation", image.orientation());
        body.put("sizeBytes", image.sizeBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 获取原图元信息。
     */
    @GetMapping("/images/{imageId}")
    public ResponseEntity<Map<String, Object>> getImage(@PathVariable String imageId) {
        SourceImage image = processingService.getImage(imageId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imageId", image.imageId());
        body.put("format", image.format().name());
        body.put("width", image.width());
        body.put("height", image.height());
        body.put("orientation", image.orientation());
        body.put("sizeBytes", image.sizeBytes());
        return ResponseEntity.ok(body);
    }

    /**
     * 生成（或复用）派生图。
     */
    @PostMapping("/images/{imageId}/derivatives")
    public ResponseEntity<Map<String, Object>> createDerivative(@PathVariable String imageId,
                                                                @RequestBody DerivativeRequest request) {
        DerivativeParams params = toParams(request);
        StoredDerivative derivative = processingService.getOrCreateDerivative(imageId, params);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("derivativeId", derivative.derivativeId());
        body.put("imageId", derivative.imageId());
        body.put("width", params.width());
        body.put("height", params.height());
        body.put("fitMode", params.fitMode().name());
        body.put("format", params.format().name());
        body.put("quality", params.quality());
        body.put("sizeBytes", derivative.sizeBytes());
        body.put("downloadUrl", "/api/derivatives/" + derivative.derivativeId());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 下载派生图字节。
     */
    @GetMapping("/derivatives/{derivativeId}")
    public ResponseEntity<byte[]> downloadDerivative(@PathVariable String derivativeId) {
        StoredDerivative derivative = processingService.loadDerivative(derivativeId);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(derivative.path());
        } catch (IOException e) {
            throw new PipelineException(ErrorCode.DERIVATIVE_NOT_FOUND,
                    "派生结果读取失败: " + e.getMessage(), e, null, derivativeId, null);
        }
        String ext = derivative.params().format().extension();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(derivative.params().format().mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"derivative-" + derivativeId + "." + ext + "\"")
                .body(bytes);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return processingService.stats();
    }

    private DerivativeParams toParams(DerivativeRequest request) {
        if (request == null) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER, "派生请求体不能为空");
        }
        if (request.width() <= 0 || request.height() <= 0) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                    "目标尺寸必须为正整数: " + request.width() + "x" + request.height());
        }
        DerivativeParams.FitMode fitMode = DerivativeParams.FitMode.CONTAIN;
        if (request.fitMode() != null && !request.fitMode().isBlank()) {
            try {
                fitMode = DerivativeParams.FitMode.valueOf(request.fitMode().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                        "不支持的 fitMode: " + request.fitMode() + "（可选 CONTAIN/COVER/STRETCH）");
            }
        }
        ImageFormat format = ImageFormat.JPEG;
        if (request.format() != null && !request.format().isBlank()) {
            try {
                format = ImageFormat.valueOf(request.format().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                        "不支持的目标格式: " + request.format() + "（可选 JPEG/PNG）");
            }
        }
        float quality = request.quality() == null ? 0.8f : request.quality();
        return new DerivativeParams(request.width(), request.height(), fitMode, format, quality);
    }

    /**
     * 派生请求体。
     */
    public record DerivativeRequest(
            int width,
            int height,
            String fitMode,
            String format,
            Float quality
    ) {
    }
}
