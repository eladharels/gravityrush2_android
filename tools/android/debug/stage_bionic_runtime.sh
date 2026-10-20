#!/usr/bin/env bash
# Stage the Bionic runtime payload for the Gradle debug source set
# (android-launcher/app/build.gradle.kts: out/android-debug/assets).
#
# The Bionic runtime tree (gow3port/bionic) comes from out/bionic (build_bionic.sh):
#   gow3port/bionic/bin/gow3-probe, kgsl-chip-id
#   gow3port/bionic/lib/libgow3cpu.so, libgow3gpu.so, libc++_shared.so, libSDL3.so
#   gow3port/run-bionic.sh, gow3port/gow3.default.ini, gow3port/fex-compatible.json
# Top-level roots match tools/android/runtime/runtime-manifest.bionic.json: gow3port, home.
#
# Idempotent: rebuilds the archive when an input changed (fingerprint below), when
# GOW3_REBUILD_DEBUG_RUNTIME=1, or when it is missing. Without the fingerprint a rebuilt .so or
# an edited run-bionic.sh would still be packaged from the previous archive, so the device keeps
# running code that no longer matches the tree.
set -euo pipefail
cd -- "$(dirname -- "$0")/../../.."

out=out/bionic
payload_dir=out/android-debug/assets
bionic_tree=out/bionic-runtime-tree
staging="$bionic_tree/gow3port"
archive=out/debug/runtime.bionic.tar.gz
stamp=out/debug/runtime.bionic.inputs

# Every file that ends up in the payload. Missing entries are recorded too, so a payload is never
# reused after an input disappears.
inputs=(
    "$out/bin/gow3-probe"
    "$out/bin/kgsl-chip-id"
    "$out/lib/libgow3cpu.so"
    "$out/lib/libgow3gpu.so"
    "$out/lib/libbloodframe.so"
    "$out/lib/libc++_shared.so"
    "$out/lib/libSDL3.so"
    tools/android/device/run-bionic.sh
    tools/android/config/gow3-fex-compatible.json
    tools/android/config/gow3-default.ini
    tools/android/config/gow3-gamecontrollerdb.txt
    tools/android/runtime/runtime-manifest.bionic.json
)
fingerprint() {
    local f
    for f in "${inputs[@]}"; do
        if [[ -f $f ]]; then
            printf '%s  %s\n' "$(shasum -a 256 "$f" | awk '{print $1}')" "$f"
        else
            printf 'missing  %s\n' "$f"
        fi
    done
}
mkdir -p out/debug
current=$(fingerprint)
stale=0
if [[ ! -f $archive || ! -f $stamp || $current != "$(cat "$stamp")" ]]; then
    stale=1
fi
if [[ ${GOW3_REBUILD_DEBUG_RUNTIME:-0} == 1 ]]; then
    stale=1
fi

if [[ $stale == 1 ]]; then
    [[ -x $out/bin/gow3-probe ]] || { echo "stage_bionic_runtime: build out/bionic first (tools/android/build_bionic.sh)" >&2; exit 1; }
    [[ -f $out/lib/libgow3gpu.so ]] || { echo "stage_bionic_runtime: missing libgow3gpu.so" >&2; exit 1; }
    for f in "${inputs[@]}"; do
        [[ -f $f ]] || { echo "stage_bionic_runtime: missing payload input $f" >&2; exit 1; }
    done
    rm -rf "$bionic_tree"
    mkdir -p "$staging/bionic/bin" "$staging/bionic/lib" "$staging/home/gow3port" "$bionic_tree/home/gow3port"
    cp -f "$out/bin/gow3-probe" "$out/bin/kgsl-chip-id" "$staging/bionic/bin/"
    cp -f "$out/lib/libgow3cpu.so" "$out/lib/libgow3gpu.so" "$out/lib/libbloodframe.so" \
          "$out/lib/libc++_shared.so" "$out/lib/libSDL3.so" "$staging/bionic/lib/"
    cp -f tools/android/device/run-bionic.sh "$staging/run-bionic.sh"
    # 默认设置与 FEX Config 输入（run-bionic.sh 从 gow3port/ 读取），与 glibc 打包同名。
    cp -f tools/android/config/gow3-fex-compatible.json "$staging/fex-compatible.json"
    cp -f tools/android/config/gow3-default.ini "$staging/gow3.default.ini"
    cp -f tools/android/config/gow3-gamecontrollerdb.txt "$staging/gow3-gamecontrollerdb.txt"
    # 补丁派生产物（boot-linked.bin、content.bin、patches.bin）不进 payload：构建期只打包
    # runtime 与补丁规则 XML（APK assets），玩家游戏准备与补丁实例化在运行前完成。
    # 顶部级 roots：gow3port、home；payload 里带 Bionic runtime-manifest。
    cp -f tools/android/runtime/runtime-manifest.bionic.json "$bionic_tree/runtime-manifest.json"
    # macOS 的 two-layer 元数据问题，两层都要挡：
    #   1. cp 会在目标上留下 com.apple.provenance 等扩展属性；bsdtar 打包时把每个带 xattr 的
    #      文件写成一对成员（真实文件 + ._同名 AppleDouble），payload 因此多出近一倍成员。
    #      COPYFILE_DISABLE=1 让 bsdtar 不生成这些 AppleDouble 成员。
    #   2. 树里若真出现 ._* / .DS_Store 常规文件，--exclude 兜底。
    # （--no-xattrs 对此无效：那些成员是 tar 自己生成的，不是树里的文件。）
    COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' \
        -czf "$archive" -C "$bionic_tree" gow3port home runtime-manifest.json
    printf '%s\n' "$current" > "$stamp"
    echo "stage_bionic_runtime: rebuilt $archive"
fi

mkdir -p "$payload_dir"
# 内容感知复制：archive 未变时不触碰 payload 的 mtime。Gradle（externalNativeBuild 之后）
# 会正确地 up-to-date 跳过 APK 重建——APK 里是否为当前 payload，由发布脚本与文档中的
# runtime.sha256 摘要比对证明；这里不再无意义地刷新时间戳。
if [[ ! -f $payload_dir/runtime.payload ]] || ! cmp -s "$archive" "$payload_dir/runtime.payload"; then
    cp -f "$archive" "$payload_dir/runtime.payload"
    # 运行时摘要：RuntimeInstaller 校验用（sha256 与 payload 一致）。
    shasum -a 256 "$archive" | awk '{print $1}' > "$payload_dir/runtime.sha256"
fi
echo "Bionic runtime payload: $payload_dir/runtime.payload ($(du -h "$archive" | cut -f1))"
