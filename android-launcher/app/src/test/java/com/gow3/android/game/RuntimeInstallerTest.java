package com.gow3.android.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * RuntimeInstaller's failure paths, on the JVM. ensure() needs a Context with APK assets plus
 * /system/bin/tar, so the device-side steps cannot run here - but the two gates that decide
 * whether a payload may ever reach the device (validateRuntime) and the transactional swap
 * (promote) are plain file logic, and those are what 7.2 asks to have proven.
 */
public class RuntimeInstallerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String MANIFEST =
            // java_package 必须是统一后的 bridge 包（consolidate-android-launcher-architecture：
            // ro.cobrabm.fexdroid → com.gow3.android.game），validateRuntime 按它拒绝异包 payload。
            "{\"schema_version\": 1, \"application_id\": \"com.gow3.android.bionic\","
            + " \"java_package\": \"com.gow3.android.game\"}";

    /** A minimal but complete Bionic runtime tree, the shape the installer accepts. */
    private File bionicTree(File root) throws IOException {
        write(root, "runtime-manifest.json", MANIFEST);
        write(root, "gow3port/bionic/bin/gow3-probe", "#!/system/bin/sh\n");
        write(root, "gow3port/bionic/bin/kgsl-chip-id", "elf");
        write(root, "gow3port/bionic/lib/libgow3cpu.so", "elf");
        write(root, "gow3port/bionic/lib/libgow3gpu.so", "elf");
        write(root, "gow3port/run-bionic.sh", "#!/system/bin/sh\n");
        write(root, "home/gow3port/.keep", "");
        makeExecutable(root, "gow3port/bionic/bin/gow3-probe");
        makeExecutable(root, "gow3port/bionic/bin/kgsl-chip-id");
        makeExecutable(root, "gow3port/run-bionic.sh");
        return root;
    }

    private static void write(File root, String relative, String content) throws IOException {
        File file = new File(root, relative);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void makeExecutable(File root, String relative) {
        assertTrue(new File(root, relative).setExecutable(true, false));
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        assertTrue(file.delete());
    }

    // ---- validateRuntime: what may ever be installed ----

    @Test
    public void completeBionicTreeIsAccepted() throws Exception {
        File tree = bionicTree(tmp.newFolder());
        RuntimeInstaller.validateRuntime(tree, "com.gow3.android.bionic");
    }

    /** debug 变体包名带 ".debug" 后缀（applicationIdSuffix），payload 仍按基础 id 发布，必须放行。 */
    @Test
    public void debugSuffixedApplicationIdAcceptsBaseManifest() throws Exception {
        File tree = bionicTree(tmp.newFolder());
        RuntimeInstaller.validateRuntime(tree, "com.gow3.android.bionic.debug");
    }

    /** 后缀只在尾部剥：中间带 .debug 的异包 id 仍要拒绝。 */
    @Test
    public void foreignApplicationIdIsRefusedEvenWithDebugInMiddle() throws Exception {
        File tree = bionicTree(tmp.newFolder());
        assertThrows(IllegalStateException.class,
                () -> RuntimeInstaller.validateRuntime(tree, "com.gow3.android.debug.other"));
    }

    @Test
    public void manifestOfAnotherApplicationIdIsRefused() throws Exception {
        File tree = bionicTree(tmp.newFolder());
        // 旧 glibc 包的 manifest：Bionic 包里装它，payload 会写进另一个包的路径契约
        write(tree, "runtime-manifest.json", MANIFEST.replace("com.gow3.android.bionic", "com.gow3.android"));
        assertThrows(IllegalStateException.class,
                () -> RuntimeInstaller.validateRuntime(tree, "com.gow3.android.bionic"));
    }

    @Test
    public void missingManifestIsRefused() throws Exception {
        File tree = bionicTree(tmp.newFolder());
        assertTrue(new File(tree, "runtime-manifest.json").delete());
        assertThrows(IllegalStateException.class,
                () -> RuntimeInstaller.validateRuntime(tree, "com.gow3.android.bionic"));
    }

    @Test
    public void incompleteBionicTreeIsRefused() throws Exception {
        for (String missing : new String[]{
                "gow3port/bionic/bin/gow3-probe", "gow3port/bionic/bin/kgsl-chip-id",
                "gow3port/bionic/lib/libgow3gpu.so", "gow3port/run-bionic.sh", "home/gow3port"}) {
            File tree = bionicTree(tmp.newFolder());
            deleteRecursively(new File(tree, missing));
            assertThrows("缺少 " + missing + " 应被拒绝", IllegalStateException.class,
                    () -> RuntimeInstaller.validateRuntime(tree, "com.gow3.android.bionic"));
        }
    }

    @Test
    public void loaderWithoutExecuteBitIsRefused() throws Exception {
        File tree = bionicTree(tmp.newFolder());
        assertTrue(new File(tree, "gow3port/bionic/bin/gow3-probe").setExecutable(false));
        assertThrows(IllegalStateException.class,
                () -> RuntimeInstaller.validateRuntime(tree, "com.gow3.android.bionic"));
    }

    @Test
    public void nestedRootfsIsRefused() throws Exception {
        File tree = bionicTree(tmp.newFolder());
        write(tree, "gow3port/rootfs/bin/sh", "x");
        assertThrows(IllegalStateException.class,
                () -> RuntimeInstaller.validateRuntime(tree, "com.gow3.android.bionic"));
    }

    /** 8.4 的收口：glibc 形状的 payload 必须被拒绝，而不是像以前那样回落校验后装进设备。 */
    @Test
    public void glibcShapedPayloadIsRefused() throws Exception {
        File tree = tmp.newFolder();
        write(tree, "runtime-manifest.json", MANIFEST);
        write(tree, "rootfs/bin/sh", "elf");
        write(tree, "rootfs/usr/bin/Xvfb", "elf");
        write(tree, "rootfs/usr/libexec/fxshmd", "elf");
        write(tree, "rootfs/usr/bin/FEX", "elf");
        write(tree, "rootfs/usr/bin/python3.13", "elf");
        write(tree, "rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", "elf");
        write(tree, "gow3port/arm64/bin/gow3-probe", "elf");
        write(tree, "gow3port/arm64/lib/libgow3cpu.so", "elf");
        write(tree, "gow3port/arm64/lib/libgow3gpu.so", "elf");
        write(tree, "gow3port/arm64/lsfg/liblsfg-vk.so", "elf");
        write(tree, "gow3port/arm64/lsfg/VkLayer_LS_frame_generation.json", "{}");
        write(tree, "home/gow3port/.keep", "");
        makeExecutable(tree, "rootfs/bin/sh");
        makeExecutable(tree, "rootfs/usr/bin/Xvfb");
        makeExecutable(tree, "rootfs/usr/libexec/fxshmd");
        makeExecutable(tree, "rootfs/usr/bin/FEX");
        makeExecutable(tree, "rootfs/usr/bin/python3.13");
        makeExecutable(tree, "gow3port/arm64/bin/gow3-probe");
        makeExecutable(tree, "rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1");
        assertThrows("glibc payload 装上也跑不了（启动分支已删），必须拒绝", IllegalStateException.class,
                () -> RuntimeInstaller.validateRuntime(tree, "com.gow3.android.bionic"));
    }

    // ---- promote: the transactional swap ----

    @Test
    public void promoteReplacesOldRuntimeAndPublishesMarker() throws Exception {
        File files = tmp.newFolder();
        File oldPort = new File(files, "gow3port");
        write(files, "gow3port/stale.txt", "old");
        write(files, "runtime-manifest.json", "{}");
        File stage = bionicTree(tmp.newFolder());

        RuntimeInstaller.promote(files, stage, "a".repeat(64));

        assertFalse("旧 gow3port 应被新树替换", new File(files, "gow3port/stale.txt").exists());
        assertTrue(new File(files, "gow3port/bionic/bin/gow3-probe").isFile());
        assertEquals("a".repeat(64),
                new String(Files.readAllBytes(new File(files, ".runtime.sha256").toPath()),
                        StandardCharsets.US_ASCII).trim());
        assertFalse("回滚目录在成功后应被清掉", new File(files, ".runtime-previous").exists());
    }

    /** stage 缺 gow3port 时 promote 必须抛错，且把搬走的旧树原样搬回来——不留下半安装状态。 */
    @Test
    public void promoteFailureRollsBackToPreviousRuntime() throws Exception {
        File files = tmp.newFolder();
        write(files, "gow3port/stale.txt", "old-runtime");
        write(files, "home/gow3port/.keep", "");
        write(files, "runtime-manifest.json", "{}");
        File stage = tmp.newFolder(); // 故意不完整：没有 gow3port

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RuntimeInstaller.promote(files, stage, "b".repeat(64)));

        // promote 的失败点是 stage/gow3port 缺失：此时旧 gow3port/home/manifest 已被搬进
        // .runtime-previous，回滚必须把它们原样搬回来。
        assertTrue(error.getMessage().contains("Cannot promote gow3port"));
        assertTrue("旧 gow3port 应被搬回来", new File(files, "gow3port/stale.txt").isFile());
        assertTrue(new File(files, "home/gow3port").isDirectory());
        assertTrue(new File(files, "runtime-manifest.json").isFile());
        assertFalse("新树不得留下半安装状态", new File(files, "gow3port/bionic").exists());
        assertFalse("不得在失败时写 marker", new File(files, ".runtime.sha256").exists());
        assertFalse("回滚目录应被清空", new File(files, ".runtime-previous").exists()
                && new File(files, ".runtime-previous").list().length > 0);
    }
}
