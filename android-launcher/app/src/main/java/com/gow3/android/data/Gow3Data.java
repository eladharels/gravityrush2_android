package com.gow3.android.data;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;

/** Android 用户数据根；与可由 APK 重新安装的 gow3port runtime 分离。 */
public final class Gow3Data {
    public static final String ROOT_NAME = "gow3data";
    public static final String USER = "user";
    public static final String CONFIG = "gow3.ini";
    public static final String LOGS = "logs";
    public static final String LOSSLESS_DLL = "Lossless.dll";
    private static final String MARKER = ".data-schema";
    private static final String SCHEMA = "1\n";
    /** 一次性 upscaler→off 迁移的幂等标记（change replace-android-fsr3-with-gsr2）。 */
    private static final String UPSCALER_MIGRATION_MARKER = "upscaler_default_off";

    private Gow3Data() {}

    public static File root(Context context) { return root(context.getFilesDir()); }
    public static File root(File files) { return new File(files, ROOT_NAME); }
    public static File user(Context context) { return new File(root(context), USER); }
    public static File user(File files) { return new File(root(files), USER); }
    public static File config(Context context) { return new File(root(context), CONFIG); }
    public static File config(File files) { return new File(root(files), CONFIG); }
    public static File logs(Context context) { return new File(root(context), LOGS); }
    public static File logs(File files) { return new File(root(files), LOGS); }
    public static File lossless(Context context) { return new File(root(context), LOSSLESS_DLL); }
    public static File lossless(File files) { return new File(root(files), LOSSLESS_DLL); }

    /**
     * Creates the stable data root and migrates only missing items from the old runtime tree.
     * The old source is deliberately retained: runtime promotion owns its later cleanup.
     */
    public static synchronized void prepare(File files, File defaultConfig) throws Exception {
        File data = root(files);
        if (!data.exists() && !data.mkdirs()) throw new IOException("Cannot create GOW3 data directory");
        ensureDirectory(new File(data, USER));
        ensureDirectory(new File(data, LOGS));

        File old = new File(files, "gow3port");
        migrateDirectory(new File(old, USER), new File(data, USER));
        migrateFile(new File(old, CONFIG), new File(data, CONFIG));
        migrateFile(new File(old, LOSSLESS_DLL), new File(data, LOSSLESS_DLL));
        migrateDirectory(new File(old, LOGS), new File(data, LOGS));
        if (!new File(data, CONFIG).isFile() && defaultConfig != null && defaultConfig.isFile())
            copyFileAtomically(defaultConfig, new File(data, CONFIG));
        if (!new File(data, CONFIG).isFile()) throw new IOException("Cannot create GOW3 configuration");
        retireShowFps(new File(data, CONFIG));
        switchDefaultUpscalerToOff(new File(data, CONFIG), new File(data, UPSCALER_MIGRATION_MARKER));
        Files.write(new File(data, MARKER).toPath(), SCHEMA.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /**
     * 引擎自带 FPS 显示（gow3.ini 的 show_fps）已退役：帧率显示统一由 launcher 侧
     * 性能 HUD（PerfHudOverlay）提供，UI 已移除该键的全部入口。历史安装的配置可能
     * 残留 show_fps=1（旧默认值），这里固定改写为 0，使引擎 FPS 叠加层不再出现；
     * 键缺失或已为 0 时不写文件（幂等）。改写整文件重写、保留其它行，与
     * Gow3Settings.update 的安全写回形态一致。
     */
    private static void retireShowFps(File config) throws IOException {
        java.util.List<String> lines = Files.readAllLines(config.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            // 精确匹配 show_fps 键（show_fps_old 等前缀相似键不得误伤）
            if (!trimmed.startsWith("show_fps")) continue;
            String rest = trimmed.substring("show_fps".length());
            if (rest.isEmpty() || (rest.charAt(0) != '=' && !Character.isWhitespace(rest.charAt(0)))) continue;
            if (rest.substring(rest.indexOf('=') + 1).trim().equals("0")) break; // 已关闭：不触碰文件
            // 改写为 0 并保留行内注释；gow3.ini 的键由 Gow3Settings/默认模板顶格写入
            lines.set(i, "show_fps=0" + trailingComment(lines.get(i)));
            changed = true;
            break;
        }
        if (!changed) return;
        FileOutputStream out = new FileOutputStream(config);
        try {
            for (String line : lines) {
                out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    /** 保留键值行尾的行内注释（如 `show_fps=1 ; note` → `show_fps=0 ; note`）。 */
    private static String trailingComment(String original) {
        int semi = original.indexOf(';'), hash = original.indexOf('#');
        int cut = semi >= 0 && (hash < 0 || semi < hash) ? semi : hash;
        return cut >= 0 ? " " + original.substring(cut).trim() : "";
    }

    /**
     * 一次性迁移（change replace-android-fsr3-with-gsr2）：超分默认关闭——把 gow3.ini 的
     * upscaler= 行改写为 off（键缺失则追加），只动这一行、保留行内注释与其它行。
     * 背景：FSR 3.1 在 Adreno 上被玩家判定为无效路径，GSR 2 / FSR 3.1 保留为可选项，
     * 默认不开启。无 marker 时执行；ini 写失败时 marker 不落盘、下次启动重试；
     * marker 之后的玩家手动选择不再被覆盖；已为 off 时只补 marker（幂等）。
     */
    private static void switchDefaultUpscalerToOff(File config, File marker) throws IOException {
        if (marker.isFile()) return;
        java.util.List<String> lines = Files.readAllLines(config.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        boolean found = false, changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("upscaler")) continue;
            String rest = trimmed.substring("upscaler".length());
            if (rest.isEmpty() || (rest.charAt(0) != '=' && !Character.isWhitespace(rest.charAt(0)))) continue;
            found = true;
            if (!rest.substring(rest.indexOf('=') + 1).trim().equals("off")) {
                lines.set(i, "upscaler=off" + trailingComment(lines.get(i)));
                changed = true;
            }
            break;
        }
        if (!found) {
            lines.add("upscaler=off");
            changed = true;
        }
        if (changed) {
            FileOutputStream out = new FileOutputStream(config);
            try {
                for (String line : lines) {
                    out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                out.getFD().sync();
            } finally {
                out.close();
            }
        }
        // marker 最后写：ini 写入抛异常时不落盘，迁移在下次启动重试。
        Files.write(marker.toPath(), "1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
    }

    private static void migrateFile(File source, File target) throws Exception {
        if (!source.isFile() || target.exists()) return;
        copyFileAtomically(source, target);
    }

    private static void migrateDirectory(File source, File target) throws Exception {
        if (!source.isDirectory() || target.exists() && hasFiles(target)) return;
        if (!source.isDirectory()) return;
        File staging = new File(target.getParentFile(), target.getName() + ".migrating");
        deleteTree(staging);
        copyTree(source, staging);
        if (target.exists()) {
            deleteTree(target);
            if (target.exists()) { deleteTree(staging); throw new IOException("Cannot replace " + target); }
        }
        if (!staging.renameTo(target)) throw new IOException("Cannot publish " + target);
    }

    private static boolean hasFiles(File root) {
        File[] entries = root.listFiles();
        return entries != null && entries.length > 0;
    }

    private static void copyFileAtomically(File source, File target) throws Exception {
        File temporary = new File(target.getPath() + ".migrating");
        if (temporary.exists() && !temporary.delete()) throw new IOException("Cannot replace " + temporary);
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            out.getFD().sync();
        }
        if (!sameFile(source, temporary)) throw new IOException("User data verification failed: " + source.getName());
        moveAtomically(temporary.toPath(), target.toPath());
    }

    private static boolean sameFile(File a, File b) throws Exception {
        if (a.length() != b.length()) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return java.util.Arrays.equals(hash(a, digest), hash(b, MessageDigest.getInstance("SHA-256")));
    }

    private static byte[] hash(File file, MessageDigest digest) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyTree(File source, File target) throws Exception {
        Files.walkFileTree(source.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.toPath().relativize(dir);
                Files.createDirectories(target.toPath().resolve(relative));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.toPath().relativize(file);
                Files.copy(file, target.toPath().resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteTree(File root) throws Exception {
        if (!root.exists()) return;
        Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
}
