package io.majiaoyu.docextract;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 印章区域定位：在整页渲染图上按颜色标记章印像素（红/品红/蓝/紫），网格聚类出候选框。
 *
 * <p>阈值自适应：先严档（排除红头标题、淡彩水印等浅色干扰），落空再放宽一档捕获淡章；
 * 宁可多送检不可漏检——误检的代价只是一次额外的 OCR 调用，漏检丢的是内容。
 * 候选框按近圆形过滤（宽高比、最小边长），横条红头与整页水印连片会被排除。</p>
 */
final class SealLocator {

    /** 颜色判定的通道差阈值与对应热点密度要求：先严后宽两档，宽档密度放宽以捕获被背景稀释的章。 */
    private static final int[] CHROMA_GAPS = {60, 25};
    private static final double[] HOT_RATIOS = {0.10, 0.05};
    /** 聚类网格边长（300dpi 下约 5mm，章径的十分之一量级）。 */
    private static final int GRID_CELL = 64;
    /** 候选框最短边（px），过滤图标、单个水印字级噪声。 */
    private static final int MIN_SIDE = 180;
    /** 候选框最长边占页面上限，过滤整页水印连片。 */
    private static final double MAX_SIDE_PAGE_RATIO = 0.85;
    /** 候选框宽高比范围：章接近圆形，红头横条等长条形排除在外。 */
    private static final double MIN_ASPECT = 0.4;
    private static final double MAX_ASPECT = 2.5;
    /** 构成候选至少需要的热点格数（约 3x3）。 */
    private static final int MIN_HOT_CELLS = 9;
    /** 像素采样步长：检测是本地毫秒级操作，隔行抽样足够。 */
    private static final int SAMPLE_STEP = 2;

    private SealLocator() {
    }

    static List<Rectangle> locate(BufferedImage page) {
        // 严宽两档取并集：严档抓浓章，宽档补淡章/被背景稀释的章，重叠框只保留严档结果。
        List<Rectangle> merged = new ArrayList<>(locate(page, CHROMA_GAPS[0], HOT_RATIOS[0]));
        for (Rectangle looseBox : locate(page, CHROMA_GAPS[1], HOT_RATIOS[1])) {
            if (merged.stream().noneMatch(strictBox -> strictBox.intersects(looseBox))) {
                merged.add(looseBox);
            }
        }
        return merged;
    }

    private static List<Rectangle> locate(BufferedImage page, int chromaGap, double hotRatio) {
        int cols = (page.getWidth() + GRID_CELL - 1) / GRID_CELL;
        int rows = (page.getHeight() + GRID_CELL - 1) / GRID_CELL;
        int[][] hits = new int[rows][cols];
        for (int y = 0; y < page.getHeight(); y += SAMPLE_STEP) {
            for (int x = 0; x < page.getWidth(); x += SAMPLE_STEP) {
                if (isSealColor(page.getRGB(x, y), chromaGap)) {
                    hits[y / GRID_CELL][x / GRID_CELL]++;
                }
            }
        }
        int samplesPerCell = (GRID_CELL / SAMPLE_STEP) * (GRID_CELL / SAMPLE_STEP);
        boolean[][] hot = new boolean[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                hot[row][col] = hits[row][col] > samplesPerCell * hotRatio;
            }
        }
        return mergeClusters(page, hot);
    }

    /** 红/品红系、蓝/紫系章印均覆盖；紫 = 红蓝双高绿低。 */
    private static boolean isSealColor(int rgb, int chromaGap) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 70 || max - min < chromaGap) {
            return false;
        }
        return (r - g >= chromaGap && r - b >= chromaGap / 2)
                || (b - g >= chromaGap && b - r >= chromaGap / 2)
                || (r - g >= chromaGap && b - g >= chromaGap);
    }

    private static List<Rectangle> mergeClusters(BufferedImage page, boolean[][] hot) {
        List<Rectangle> boxes = new ArrayList<>();
        int rows = hot.length;
        int cols = rows == 0 ? 0 : hot[0].length;
        boolean[][] visited = new boolean[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (!hot[row][col] || visited[row][col]) {
                    continue;
                }
                int minRow = row;
                int maxRow = row;
                int minCol = col;
                int maxCol = col;
                int cells = 0;
                Deque<int[]> queue = new ArrayDeque<>();
                visited[row][col] = true;
                queue.add(new int[]{row, col});
                while (!queue.isEmpty()) {
                    int[] cell = queue.poll();
                    cells++;
                    minRow = Math.min(minRow, cell[0]);
                    maxRow = Math.max(maxRow, cell[0]);
                    minCol = Math.min(minCol, cell[1]);
                    maxCol = Math.max(maxCol, cell[1]);
                    int[][] neighbors = {{cell[0] - 1, cell[1]}, {cell[0] + 1, cell[1]},
                            {cell[0], cell[1] - 1}, {cell[0], cell[1] + 1}};
                    for (int[] neighbor : neighbors) {
                        int nr = neighbor[0];
                        int nc = neighbor[1];
                        if (nr < 0 || nr >= rows || nc < 0 || nc >= cols
                                || !hot[nr][nc] || visited[nr][nc]) {
                            continue;
                        }
                        visited[nr][nc] = true;
                        queue.add(neighbor);
                    }
                }
                if (cells < MIN_HOT_CELLS) {
                    continue;
                }
                Rectangle box = new Rectangle(minCol * GRID_CELL, minRow * GRID_CELL,
                        (maxCol - minCol + 1) * GRID_CELL, (maxRow - minRow + 1) * GRID_CELL);
                if (isPlausibleSeal(box, page)) {
                    boxes.add(box);
                }
            }
        }
        return boxes;
    }

    private static boolean isPlausibleSeal(Rectangle box, BufferedImage page) {
        if (box.width < MIN_SIDE || box.height < MIN_SIDE) {
            return false;
        }
        int pageMaxSide = Math.max(page.getWidth(), page.getHeight());
        if (Math.max(box.width, box.height) > pageMaxSide * MAX_SIDE_PAGE_RATIO) {
            return false;
        }
        double aspect = (double) box.width / box.height;
        return aspect >= MIN_ASPECT && aspect <= MAX_ASPECT;
    }
}
