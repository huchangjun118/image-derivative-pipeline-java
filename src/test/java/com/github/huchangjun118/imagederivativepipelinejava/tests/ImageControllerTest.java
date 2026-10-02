package com.github.huchangjun118.imagederivativepipelinejava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 接口集成测试：上传、派生、下载与错误语义。
 */
@SpringBootTest(properties = {
        "pipeline.cache-dir=build/it/cache",
        "pipeline.original-dir=build/it/originals",
        "pipeline.worker-threads=2",
        "pipeline.queue-capacity=8"
})
@AutoConfigureMockMvc
class ImageControllerTest {

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void cleanDirs() throws Exception {
        deleteRecursively(Path.of("build/it"));
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private String uploadSample() throws Exception {
        byte[] jpeg = TestImages.toJpeg(TestImages.gradient(64, 48));
        MockMultipartFile file = new MockMultipartFile("file", "sample.jpg", "image/jpeg", jpeg);
        MvcResult result = mvc.perform(multipart("/api/images").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.width").value(64))
                .andExpect(jsonPath("$.height").value(48))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"", "").replaceAll("\".*", "");
    }

    @Test
    void uploadDeriveAndDownload() throws Exception {
        String id = uploadSample();

        // 生成派生图：首次 MISS
        MvcResult first = mvc.perform(post("/api/images/{id}/derivatives", id)
                        .param("width", "32").param("height", "24")
                        .param("format", "jpeg").param("quality", "85"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Cache", "MISS"))
                .andReturn();
        byte[] derived = first.getResponse().getContentAsByteArray();
        assertTrue(derived.length > 0);

        // 重复请求：命中缓存且字节一致
        MvcResult second = mvc.perform(post("/api/images/{id}/derivatives", id)
                        .param("width", "32").param("height", "24")
                        .param("format", "jpeg").param("quality", "85"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"))
                .andReturn();
        assertArrayEquals(derived, second.getResponse().getContentAsByteArray());

        // GET 一致读取：与生成结果逐字节一致
        MvcResult downloaded = mvc.perform(get("/api/images/{id}/derivatives", id)
                        .param("width", "32").param("height", "24")
                        .param("format", "jpeg").param("quality", "85"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andReturn();
        assertArrayEquals(derived, downloaded.getResponse().getContentAsByteArray());
    }

    @Test
    void getBeforeDeriveReturns404() throws Exception {
        String id = uploadSample();
        mvc.perform(get("/api/images/{id}/derivatives", id)
                        .param("width", "32").param("height", "24"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DERIVATIVE_NOT_FOUND"));
    }

    @Test
    void invalidParamsReturn400WithCode() throws Exception {
        String id = uploadSample();
        mvc.perform(post("/api/images/{id}/derivatives", id)
                        .param("width", "0").param("height", "24"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DIMENSION"));

        mvc.perform(post("/api/images/{id}/derivatives", id)
                        .param("width", "32").param("height", "24").param("quality", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUALITY"));

        mvc.perform(post("/api/images/{id}/derivatives", id)
                        .param("width", "32").param("height", "24").param("format", "tiff"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FORMAT"));

        mvc.perform(post("/api/images/{id}/derivatives", id)
                        .param("width", "32").param("height", "24").param("fit", "stretch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIT"));
    }

    @Test
    void corruptUploadIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "bad.jpg", "image/jpeg",
                new byte[]{9, 9, 9, 9});
        mvc.perform(multipart("/api/images").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FORMAT"));
    }

    @Test
    void unknownImageReturns404() throws Exception {
        mvc.perform(post("/api/images/{id}/derivatives", "0".repeat(64))
                        .param("width", "32").param("height", "24"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
    }

    @Test
    void rawByteUploadWorks() throws Exception {
        byte[] png = TestImages.toPng(TestImages.gradient(32, 32));
        MvcResult result = mvc.perform(post("/api/images")
                        .contentType("image/png")
                        .content(png))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();
        assertEquals(64, result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"", "").replaceAll("\".*", "").length());
    }
}
