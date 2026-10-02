package com.github.huchangjun118.imagederivativepipelinejava.tests;

import com.github.huchangjun118.imagederivativepipelinejava.image.ExifOrientationExtractor;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EXIF 方向解析测试。
 */
class ExifOrientationExtractorTest {

    private final ExifOrientationExtractor extractor = new ExifOrientationExtractor();

    @Test
    void returnsOneWithoutExif() {
        byte[] jpeg = TestImages.toJpeg(TestImages.rgbImage(20, 20), 0.9f);
        assertEquals(1, extractor.extractOrientation(jpeg));
    }

    @Test
    void readsAllOrientations() {
        BufferedImage img = TestImages.rgbImage(30, 20);
        for (int orientation = 1; orientation <= 8; orientation++) {
            byte[] jpeg = TestImages.jpegWithOrientation(img, orientation);
            assertEquals(orientation, extractor.extractOrientation(jpeg),
                    "方向 " + orientation + " 应被正确解析");
        }
    }

    @Test
    void returnsOneForPng() {
        byte[] png = TestImages.toPng(TestImages.rgbImage(20, 20));
        assertEquals(1, extractor.extractOrientation(png));
    }

    @Test
    void returnsOneForGarbage() {
        assertEquals(1, extractor.extractOrientation(new byte[]{1, 2, 3}));
        assertEquals(1, extractor.extractOrientation(new byte[0]));
    }
}
