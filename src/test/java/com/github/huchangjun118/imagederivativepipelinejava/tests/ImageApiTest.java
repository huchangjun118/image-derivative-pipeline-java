package com.github.huchangjun118.imagederivativepipelinejava.tests;

import com.github.huchangjun118.imagederivativepipelinejava.image.ExifOrientationExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST 接口端到端测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImageApiTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("pipeline.storage-dir", () -> tempDir.toString());
        registry.add("pipeline.worker-threads", () -> 2);
        registry.add("pipeline.queue-capacity", () -> 8);
        registry.add("pipeline.task-timeout-millis", () -> 30000);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void uploadDerivativeDownloadLifecycle() throws Exception {
        BufferedImage img = TestImages.rgbImage(80, 60);
        byte[] png = TestImages.toPng(img);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", png);

        String imageId = mockMvc.perform(multipart("/api/images").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageId").isNotEmpty())
                .andExpect(jsonPath("$.format").value("PNG"))
                .andExpect(jsonPath("$.width").value(80))
                .andExpect(jsonPath("$.height").value(60))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(imageId, "$.imageId");

        mockMvc.perform(get("/api/images/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(id));

        String derivBody = "{\"width\":40,\"height\":40,\"fitMode\":\"COVER\",\"format\":\"JPEG\",\"quality\":0.8}";
        String derivResp = mockMvc.perform(post("/api/images/" + id + "/derivatives")
                        .contentType("application/json").content(derivBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.derivativeId").isNotEmpty())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String derivativeId = com.jayway.jsonpath.JsonPath.read(derivResp, "$.derivativeId");

        byte[] downloaded = mockMvc.perform(get("/api/derivatives/" + derivativeId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(derivativeId)))
                .andReturn().getResponse().getContentAsByteArray();
        assert downloaded.length > 0;

        // 重复请求命中缓存
        mockMvc.perform(post("/api/images/" + id + "/derivatives")
                        .contentType("application/json").content(derivBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.derivativeId").value(derivativeId));
    }

    @Test
    void uploadWithExifOrientation() throws Exception {
        byte[] jpeg = TestImages.jpegWithOrientation(TestImages.rgbImage(100, 60), 6);
        MockMultipartFile file = new MockMultipartFile("file", "oriented.jpg", "image/jpeg", jpeg);
        String resp = mockMvc.perform(multipart("/api/images").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orientation").value(6))
                .andExpect(jsonPath("$.width").value(60))
                .andExpect(jsonPath("$.height").value(100))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(resp, "$.imageId");
        // 验证解析器与服务层一致
        org.junit.jupiter.api.Assertions.assertEquals(6,
                new ExifOrientationExtractor().extractOrientation(jpeg));
        mockMvc.perform(get("/api/images/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.width").value(60))
                .andExpect(jsonPath("$.height").value(100));
    }

    @Test
    void corruptUploadRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "bad.png", "image/png",
                new byte[]{1, 2, 3, 4, 5});
        mockMvc.perform(multipart("/api/images").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DECODE_FAILED"));
    }

    @Test
    void invalidParamsRejected() throws Exception {
        byte[] png = TestImages.toPng(TestImages.rgbImage(30, 30));
        MockMultipartFile file = new MockMultipartFile("file", "t.png", "image/png", png);
        String resp = mockMvc.perform(multipart("/api/images").file(file))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(resp, "$.imageId");

        mockMvc.perform(post("/api/images/" + id + "/derivatives")
                        .contentType("application/json").content("{\"width\":0,\"height\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        mockMvc.perform(post("/api/images/" + id + "/derivatives")
                        .contentType("application/json")
                        .content("{\"width\":10,\"height\":10,\"format\":\"WEBP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void unknownDerivativeReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/derivatives/" + "a".repeat(64)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DERIVATIVE_NOT_FOUND"));
    }

    @Test
    void statsExposed() throws Exception {
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueCapacity").isNumber())
                .andExpect(jsonPath("$.cacheHits").isNumber())
                .andExpect(jsonPath("$.cacheMisses").isNumber());
    }
}
