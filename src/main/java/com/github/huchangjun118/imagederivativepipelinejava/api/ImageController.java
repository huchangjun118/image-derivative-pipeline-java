package com.github.huchangjun118.imagederivativepipelinejava.api;

import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeParams;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeResult;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.ImageProcessingService;
import com.github.huchangjun118.imagederivativepipelinejava.storage.StoredOriginal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * 图片上传与派生图下载接口。
 *
 * <ul>
 *   <li>POST /api/images：上传原图，返回内容寻址的 imageId</li>
 *   <li>POST /api/images/{id}/derivatives：按参数生成派生图（幂等，重复请求复用结果）</li>
 *   <li>GET  /api/images/{id}/derivatives：读取已生成的派生图，未生成返回 404</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final ImageProcessingService service;

    public ImageController(ImageProcessingService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoredOriginal upload(@RequestParam("file") MultipartFile file) throws IOException {
        return service.upload(file.getBytes());
    }

    /** 直接上传原始字节（Content-Type 为图片类型）。 */
    @PostMapping(consumes = {"image/jpeg", "image/png", "application/octet-stream"})
    public StoredOriginal uploadRaw(@org.springframework.web.bind.annotation.RequestBody byte[] body) {
        return service.upload(body);
    }

    @PostMapping("/{imageId}/derivatives")
    public ResponseEntity<byte[]> derive(@PathVariable String imageId,
                                         @RequestParam int width,
                                         @RequestParam int height,
                                         @RequestParam(defaultValue = "fit") String fit,
                                         @RequestParam(defaultValue = "jpeg") String format,
                                         @RequestParam(defaultValue = "85") int quality) {
        DerivativeParams params = parseParams(width, height, fit, format, quality);
        DerivativeResult result = service.derive(imageId, params);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, result.mediaType())
                .header("X-Cache", result.cacheHit() ? "HIT" : "MISS")
                .body(result.bytes());
    }

    @GetMapping("/{imageId}/derivatives")
    public ResponseEntity<byte[]> getDerivative(@PathVariable String imageId,
                                                @RequestParam int width,
                                                @RequestParam int height,
                                                @RequestParam(defaultValue = "fit") String fit,
                                                @RequestParam(defaultValue = "jpeg") String format,
                                                @RequestParam(defaultValue = "85") int quality) {
        DerivativeParams params = parseParams(width, height, fit, format, quality);
        Optional<DerivativeResult> existing = service.getExisting(imageId, params);
        if (existing.isEmpty()) {
            throw ApiException.notFound("DERIVATIVE_NOT_FOUND",
                    "派生图尚未生成，请先调用 POST /api/images/" + imageId + "/derivatives");
        }
        DerivativeResult result = existing.get();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, result.mediaType())
                .header("X-Cache", "HIT")
                .body(result.bytes());
    }

    private DerivativeParams parseParams(int width, int height, String fit, String format, int quality) {
        DerivativeParams.Fit fitEnum;
        try {
            fitEnum = DerivativeParams.Fit.valueOf(fit.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_FIT",
                    "不支持的 fit 模式: " + fit + "，仅支持 fit/fill");
        }
        return new DerivativeParams(width, height, fitEnum, format, quality);
    }
}
