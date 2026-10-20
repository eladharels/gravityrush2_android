package com.gow3.android.game;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import org.junit.Test;

/** PerfHudMetrics 的宿主单测：差分语义、计数解析、GPU 节点探测与位置校验（无 Android 依赖）。 */
public class PerfHudMetricsTest {
    private File tempDir() throws Exception {
        return Files.createTempDirectory("perfhud-test").toFile();
    }

    private static void write(File dir, String name, String text) throws Exception {
        try (FileWriter w = new FileWriter(new File(dir, name))) { w.write(text); }
    }

    // ---- FPS 差分（task 1.2）：正常差分、零差分、负差分按 0、首帧未知按 0 ----

    @Test public void diffFrames_zeroWindowIsZero() {
        assertEquals(0, PerfHudMetrics.diffFrames(0, 0));
    }

    @Test public void diffFrames_countsIncrement() {
        assertEquals(60, PerfHudMetrics.diffFrames(0, 60));
        assertEquals(1, PerfHudMetrics.diffFrames(59, 60)); // 相邻累计值只差 1 帧也有效
    }

    @Test public void diffFrames_negativeDeltaClampsToZero() {
        assertEquals(0, PerfHudMetrics.diffFrames(5, 4)); // 游戏重启后计数器重置
    }

    @Test public void diffFrames_unknownFirstSampleIsZero() {
        assertEquals(0, PerfHudMetrics.diffFrames(-1, 123));
        assertEquals(0, PerfHudMetrics.diffFrames(10, -1));
    }

    // ---- CPU（task 1.2）：/proc/stat 首行解析 + idle/total 差分 ----

    @Test public void cpuTotals_parsesProcStatLine() {
        // user=100 nice=0 system=50 idle=200 iowait=20 irq=0 softirq=30 steal=0
        long[] t = PerfHudMetrics.cpuTotals("cpu  100 0 50 200 20 0 30 0 0 0");
        assertArrayEquals(new long[]{400, 220}, t); // total=400, idle=200+20
    }

    @Test public void cpuTotals_rejectsGarbage() {
        assertNull(PerfHudMetrics.cpuTotals(null));
        assertNull(PerfHudMetrics.cpuTotals("not a stat line"));
        assertNull(PerfHudMetrics.cpuTotals("cpu  1 2"));
    }

    @Test public void cpuUsage_deltaAcrossSamples() {
        long[] prev = {400, 220};
        // 一个窗口后 total+200、idle+150：占用 = (200-150)/200 = 25%
        assertEquals(25, PerfHudMetrics.cpuUsage(prev, new long[]{600, 370}));
    }

    @Test public void cpuUsage_invalidDeltasReturnInvalid() {
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.cpuUsage(null, new long[]{1, 1}));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.cpuUsage(new long[]{100, 50}, new long[]{90, 40})); // 回拨
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.cpuUsage(new long[]{100, 50}, new long[]{100, 50})); // 零窗口
    }

    // ---- 即时百分比节点解析 ----

    @Test public void parsePercent_variousFormats() {
        assertEquals(42, PerfHudMetrics.parsePercent("42 %"));
        assertEquals(37, PerfHudMetrics.parsePercent("37"));
        assertEquals(100, PerfHudMetrics.parsePercent("100"));
        assertEquals(0, PerfHudMetrics.parsePercent("0"));
    }

    @Test public void parsePercent_invalidReturnsInvalid() {
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.parsePercent(null));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.parsePercent("no digits here"));
    }

    // ---- kgsl gpubusy：busy/total 即时比值（内核读取即重置，不跨 tick 差分） ----

    @Test public void gpuBusyRatio_instantWindowRatio() {
        // 对齐 WinlatorHUD：读取即上一个窗口的 busy/total 比值，直接算百分比。
        assertEquals(92, PerfHudMetrics.gpuBusyRatio("1200 1300"));
        assertEquals(0, PerfHudMetrics.gpuBusyRatio("0 1300"));
    }

    @Test public void gpuBusyRatio_invalidInputs() {
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.gpuBusyRatio(null));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.gpuBusyRatio("7"));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.gpuBusyRatio("a b"));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.gpuBusyRatio("10 0")); // GPU 完全空闲：total=0
        assertEquals(100, PerfHudMetrics.gpuBusyRatio("10 5")); // busy>total：按饱和处理（对齐 Winlator clampPercent）
    }

    // ---- MTK mtk_mali/utilization：ACTIVE= token ----

    @Test public void mtkActive_parsesActiveToken() {
        assertEquals(37, PerfHudMetrics.mtkActive("CLK=393000 ACTIVE=37"));
        assertEquals(0, PerfHudMetrics.mtkActive("CLK=0 ACTIVE=0 IDLE=100"));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.mtkActive("CLK=393000"));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.mtkActive(null));
    }

    // ---- cpufreq 负载估算（Winlator 的 CPU 回退方案） ----

    @Test public void cpuFreqLoad_sumsReadableCores() {
        // 8 核：3 个核可读（在线），其余 0（离线/被拒）自动跳过
        long[] cur = {2000000, 1500000, 0, 3000000, 0, 0, 0, 0};
        long[] max = {2800000, 2800000, 2800000, 3500000, 3500000, 3500000, 3500000, 3500000};
        // sum(cur)=6.5M, sum(max)=9.1M → 71%
        assertEquals(71, PerfHudMetrics.cpuFreqLoad(cur, max));
    }

    @Test public void cpuFreqLoad_invalidInputs() {
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.cpuFreqLoad(null, new long[]{1}));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.cpuFreqLoad(new long[]{0, 0}, new long[]{0, 0}));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.cpuFreqLoad(new long[]{1, 2}, new long[]{1}));
    }

    // ---- 游戏进程 stat（同 uid 回退路径） ----

    private static final String GAME_STAT =
        "14556 (gow3-probe) S 12506 1522 0 0 -1 4194304 632068 29969 2 0 8931 1924 11 18 20 0 49 0 46276391 52218003456 544805 18";

    @Test public void procComm_extractsGameName() {
        assertEquals("gow3-probe", PerfHudMetrics.procComm(GAME_STAT));
        assertNull(PerfHudMetrics.procComm(null));
        assertNull(PerfHudMetrics.procComm("no parens here"));
    }

    @Test public void procStatFields_extractsPpidAndCpuTicks() {
        long[] f = PerfHudMetrics.procStatFields(GAME_STAT);
        assertEquals(12506, f[0]);   // ppid == launcher pid
        assertEquals(10855, f[1]);   // utime(8931) + stime(1924)
        assertNull(PerfHudMetrics.procStatFields(null));
        assertNull(PerfHudMetrics.procStatFields("1 (x) S 1 2"));
    }

    @Test public void processCpuUsage_percentOfAllCores() {
        // 一个窗口内游戏进程消耗 400 tick（USER_HZ=100 → 4 核秒），墙钟 1s，8 核 → 50%
        assertEquals(50, PerfHudMetrics.processCpuUsage(1000, 1400, 1_000_000_000L, 2_000_000_000L, 8));
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.processCpuUsage(-1, 100, 1, 2, 8)); // 首帧
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.processCpuUsage(100, 1400, 2_000_000_000L, 1_000_000_000L, 8)); // 墙钟回拨
        assertEquals(PerfHudMetrics.INVALID, PerfHudMetrics.processCpuUsage(100, 1400, 1, 2, 0)); // 核数无效
    }

    // ---- GPU 节点发现（task 1.3）：静态清单 + devfreq/platform 目录扫描 ----

    @Test public void discoverGpuPaths_picksReadableStaticsInPriorityOrder() throws Exception {
        File dir = tempDir();
        write(dir, "b", "10");
        write(dir, "c", "20");
        String[] found = PerfHudMetrics.discoverGpuPaths(new String[]{
            new File(dir, "missing").getAbsolutePath(),
            new File(dir, "b").getAbsolutePath(),
            new File(dir, "c").getAbsolutePath(),
        }, new String[0]);
        assertEquals(2, found.length);
        assertEquals(new File(dir, "b").getAbsolutePath(), found[0]); // 保持优先级顺序
    }

    @Test public void discoverGpuPaths_scansDevfreqNodesByToken() throws Exception {
        File root = tempDir();
        // 命中 token 的 devfreq 节点，其下 load 文件可读 → 入选
        File gpu = new File(root, "1c00000.qcom,kgsl-3d0"); gpu.mkdirs();
        write(gpu, "load", "42");
        // 不含 token 的节点被跳过
        File other = new File(root, "soc@0:usb@100000"); other.mkdirs();
        write(other, "load", "99");
        String[] found = PerfHudMetrics.discoverGpuPaths(new String[0], new String[]{root.getAbsolutePath()});
        assertEquals(1, found.length);
        assertTrue(found[0].endsWith("kgsl-3d0/load"));
    }

    @Test public void discoverGpuPaths_ignoresUnreadableAndEmptyFiles() throws Exception {
        File root = tempDir();
        File gpu = new File(root, "mali0"); gpu.mkdirs();
        write(gpu, "utilisation", ""); // 空文件 canRead 为 true 但没有数值
        new File(gpu, "gpu_load").createNewFile(); // 空文件
        String[] found = PerfHudMetrics.discoverGpuPaths(new String[0], new String[]{root.getAbsolutePath()});
        // 发现阶段只看 canRead（空文件也算可读）；真正的淘汰发生在采样阶段的数值解析。
        assertEquals(2, found.length);
    }

    @Test public void discoverGpuPaths_allUnreadableReturnsEmpty() throws Exception {
        File dir = tempDir();
        assertEquals(0, PerfHudMetrics.discoverGpuPaths(new String[]{
            new File(dir, "x").getAbsolutePath()}, new String[]{dir.getAbsolutePath()}).length);
        assertEquals(0, PerfHudMetrics.discoverGpuPaths(null, null).length);
    }

    @Test public void discoverGpuPaths_recoversWhenNodeAppears() throws Exception {
        // spec「计数节点恢复可读」：全不可读 → 节点出现 → 重新发现选中并读出数值。
        File dir = tempDir();
        String path = new File(dir, "late").getAbsolutePath();
        assertEquals(0, PerfHudMetrics.discoverGpuPaths(
                new String[]{path}, new String[]{dir.getAbsolutePath()}).length);
        write(dir, "late", "55");
        String[] found = PerfHudMetrics.discoverGpuPaths(
                new String[]{path}, new String[0]);
        assertEquals(1, found.length);
        assertEquals(55, PerfHudMetrics.parsePercent(PerfHudMetrics.readFirstLine(path)));
    }

    // ---- 位置校验（task 2.1 / design D4） ----

    private static final float W = 1080, H = 1920, HW = 100, HH = 60, MARGIN = 8;

    @Test public void resolvePosition_missingFallsBackToTopRight() {
        float[] p = PerfHudMetrics.resolvePosition(null, null, W, H, HW, HH, MARGIN);
        assertEquals(W - HW - MARGIN, p[0], 0.01f);
        assertEquals(MARGIN, p[1], 0.01f);
    }

    @Test public void resolvePosition_nanOrInfiniteFallsBack() {
        float[] p = PerfHudMetrics.resolvePosition(Float.NaN, 10f, W, H, HW, HH, MARGIN);
        assertEquals(W - HW - MARGIN, p[0], 0.01f);
        float[] q = PerfHudMetrics.resolvePosition(Float.POSITIVE_INFINITY, 10f, W, H, HW, HH, MARGIN);
        assertEquals(W - HW - MARGIN, q[0], 0.01f);
    }

    @Test public void resolvePosition_fullyOffScreenFallsBack() {
        float[] p = PerfHudMetrics.resolvePosition(1_000_000f, 10f, W, H, HW, HH, MARGIN);
        assertEquals(W - HW - MARGIN, p[0], 0.01f);
        float[] q = PerfHudMetrics.resolvePosition(50f, -500f, W, H, HW, HH, MARGIN); // 完全出上边界
        assertEquals(MARGIN, q[1], 0.01f);
    }

    @Test public void resolvePosition_validPositionKept() {
        float[] p = PerfHudMetrics.resolvePosition(300f, 400f, W, H, HW, HH, MARGIN);
        assertArrayEquals(new float[]{300f, 400f}, p, 0.01f);
    }

    @Test public void resolvePosition_partialOutOfRangeClampedInside() {
        // 稍微越出右/下边界：不算损坏数据，夹回可视范围（spec「不出屏幕可视范围」）。
        float[] p = PerfHudMetrics.resolvePosition(W - 10f, H - 5f, W, H, HW, HH, MARGIN);
        assertEquals(W - HW, p[0], 0.01f);
        assertEquals(H - HH, p[1], 0.01f);
        float[] q = PerfHudMetrics.resolvePosition(-30f, 40f, W, H, HW, HH, MARGIN); // 部分越出左边界
        assertEquals(0f, q[0], 0.01f);
        assertEquals(40f, q[1], 0.01f);
    }

    @Test public void resolvePosition_tinyScreenStillVisible() {
        // HUD 比屏幕还大（极端分屏）：夹紧后仍然贴在 (0,0)，可拖回。
        float[] p = PerfHudMetrics.resolvePosition(5f, 5f, 50f, 40f, HW, HH, MARGIN);
        assertEquals(0f, p[0], 0.01f);
        assertEquals(0f, p[1], 0.01f);
    }

    // ---- 帧生成双帧率段（change add-native-frame-generation，spec「HUD 区分源/上屏」）----

    @Test public void fpsSegment_fgActiveShowsSourceArrowPresented() {
        assertEquals("59→118 LSFG 2x", PerfHudMetrics.fpsSegment(59, 118, 2));
        assertEquals("40→120 LSFG 3x", PerfHudMetrics.fpsSegment(40, 120, 3));
        assertEquals("30→120 LSFG 4x", PerfHudMetrics.fpsSegment(30, 120, 4));
    }

    @Test public void fpsSegment_fgInactiveFallsBackToSingleRate() {
        // 关闭/未生效（multiplier 0/1）与 warm-up（presented==source）都退回单一帧率，
        // 语义与无帧生成能力时一致。
        assertEquals("60", PerfHudMetrics.fpsSegment(60, 60, 0));
        assertEquals("60", PerfHudMetrics.fpsSegment(60, 60, 1));
        assertEquals("59", PerfHudMetrics.fpsSegment(59, 59, 2)); // warm-up：上屏==源
    }
}
