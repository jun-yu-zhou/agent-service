package com.example.agentservice.docextract;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 印章定位的纯本地单测，不调用 OCR。 */
class SealLocatorTest {

    @Test
    void locatesSaturatedRedAndBlueSeals() {
        BufferedImage page = whitePage(1000, 1400);
        Graphics2D graphics = page.createGraphics();
        graphics.setColor(new Color(200, 30, 30));
        graphics.fillOval(100, 100, 400, 400);
        graphics.setColor(new Color(30, 60, 200));
        graphics.fillOval(550, 900, 300, 300);
        graphics.dispose();

        List<Rectangle> boxes = SealLocator.locate(page);

        assertEquals(2, boxes.size(), "浓红章与蓝章都应命中");
    }

    @Test
    void loosePassCatchesFaintSealWhenStrictMisses() {
        BufferedImage page = whitePage(1000, 1400);
        Graphics2D graphics = page.createGraphics();
        // 淡粉章：红通道只比绿蓝高 25，严档（60）应落空、宽档（25）应命中
        graphics.setColor(new Color(225, 200, 200));
        graphics.fillOval(300, 400, 360, 360);
        graphics.dispose();

        List<Rectangle> boxes = SealLocator.locate(page);

        assertEquals(1, boxes.size(), "淡章应由宽档兜住");
        assertTrue(boxes.get(0).width >= 300 && boxes.get(0).height >= 300);
    }

    @Test
    void filtersRedBannerAndTinyDot() {
        BufferedImage page = whitePage(1000, 1400);
        Graphics2D graphics = page.createGraphics();
        graphics.setColor(new Color(200, 30, 30));
        graphics.fillRect(100, 100, 700, 40);
        graphics.fillOval(800, 800, 60, 60);
        graphics.dispose();

        List<Rectangle> boxes = SealLocator.locate(page);

        assertTrue(boxes.isEmpty(), "红头横条（过扁）与小圆点（过小）都应被过滤");
    }

    @Test
    void plainTextPageHasNoSeal() {
        assertTrue(SealLocator.locate(whitePage(1000, 1400)).isEmpty());
    }

    private static BufferedImage whitePage(int width, int height) {
        BufferedImage page = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = page.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return page;
    }
}
