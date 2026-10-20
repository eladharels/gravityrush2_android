#!/usr/bin/env bash
# Assemble the debug APK with the Gradle launcher.
#
# Bionic only: the launcher's JNI libraries are built by Gradle externalNativeBuild from
# app/src/main/cpp, the runtime payload comes from stage_bionic_runtime.sh (also wired into the
# Gradle preDebugBuild hook). The glibc/proot runtime, its rootfs and the fexdroid X11 bridges
# are gone, so this no longer validates against a glibc runtime manifest.
set -euo pipefail

cd -- "$(dirname -- "$0")/../../.."
root=$PWD
project=$root/android-launcher
apk=$project/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

[[ -x $project/gradlew ]] || { echo "build_kotlin_launcher: missing Gradle wrapper" >&2; exit 1; }
[[ -f $root/out/android-debug/assets/runtime.payload ]] || {
    echo "build_kotlin_launcher: missing debug runtime payload; build the runtime first" >&2
    exit 1
}
# JNI 库由 externalNativeBuild 从源码产出，Gradle 原生追踪其输入输出，无需再强制
# 重跑 merge/rerun —— 源码变化会自然触发 APK 重建。
"$project/gradlew" -p "$project" :app:assembleDebug
[[ -f $apk ]] || { echo "build_kotlin_launcher: APK was not generated" >&2; exit 1; }
echo "Built $apk (Bionic)"
