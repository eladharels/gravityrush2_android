#!/usr/bin/env bash
# Frame generation engine (change add-native-frame-generation): libbloodframe.so — the LSFG engine
# for the Bionic game process, ported from Bloodborne-Android's tools/android/build_bloodframe.sh.
# It lives in its own repository, https://github.com/ducvd89/bloodframe (MIT; it carries the history
# of GameNative's MIT lsfg-vk Android fork it is based on, lsfg-vk 1.0 by PancakeTAS), and is built
# here at a pinned commit with that repository's build-android.sh. Not a Vulkan layer: a single
# shared library with the small C API of tools/android/lsfg/gow3_lsfg_api.h; libgow3gpu dlopen()s it
# only when frame generation is on (gpu/shim/gow3_lsfg.cpp).
# Lossless Scaling's shaders come from the player's own Lossless.dll at run time, never the APK.
# Output: out/bionic/lib/libbloodframe.so and out/bionic/licenses/bloodframe-*.LICENSE*,
# bloodframe-source.txt; build_bionic.sh runs this, build_release_runtime.py packages them.
# tools/android/lsfg/gow3_lsfg_api.h is the game's copy of the C API header (libgow3gpu is built before
# this runs); this script fails when it differs from the header of the pinned commit, so a pin bump
# must copy android/gow3_lsfg.h over it.
# Prerequisite: tools/android/build_bionic_deps.sh (the NDK).
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
BLOODFRAME_REPO=https://github.com/ducvd89/bloodframe.git
BLOODFRAME_COMMIT=c29027555cfa8c9fc73c20f03816230becb9f469
root=$PWD/.local-deps/android
src=$root/src/bloodframe
# NDK 解析：GOW3_NDK > 本机 Android SDK（与 Gradle 同源）> BB 式 $root/sdk 布局。
if [[ -z ${GOW3_NDK:-} ]]; then
    for cand in "$HOME/Library/Android/sdk/ndk/29.0.14206865" "$HOME/Library/Android/sdk/ndk/"*; do
        [[ -f $cand/build/cmake/android.toolchain.cmake ]] && { GOW3_NDK=$cand; break; }
    done
fi
ndk=${GOW3_NDK:-$root/sdk/ndk/29.0.14206865}
api=${GOW3_ANDROID_API:-30}
J=${GOW3_JOBS:-22}
out=$PWD/out/bionic
build=$out/bloodframe-build
[[ -f $ndk/build/cmake/android.toolchain.cmake ]] || { echo "No NDK: $ndk (tools/android/build_bionic_deps.sh)" >&2; exit 1; }
if [[ ! -d $src/.git ]]; then
    mkdir -p "$root/src"
    git clone -q "$BLOODFRAME_REPO" "$src" ||
        { echo "Cannot clone $BLOODFRAME_REPO (network?)" >&2; exit 1; }
fi
git -C "$src" cat-file -e "$BLOODFRAME_COMMIT^{commit}" 2>/dev/null ||
    git -C "$src" fetch -q origin main
git -C "$src" -c advice.detachedHead=false checkout -q "$BLOODFRAME_COMMIT"
[[ $(git -C "$src" rev-parse HEAD) == "$BLOODFRAME_COMMIT" ]] ||
    { echo "$src is not at $BLOODFRAME_COMMIT" >&2; exit 1; }
# 上游 build-android.sh 硬编码 linux-x86_64 工具链目录：按宿主替换（macOS=darwin-x86_64）。
if [[ "$(uname -s)" == Darwin ]]; then
    perl -pi -e 's/linux-x86_64/darwin-x86_64/g' "$src/build-android.sh"
fi

# The engine's host header must match the pin (the API is the contract gow3_lsfg.cpp compiles
# against). gow3_lsfg_api.h 与上游头仅前缀不同（Bb→Gow3、BB_LSFG→GOW3_LSFG）且带 gow3 归因
# 注释：剥离注释行、归一化前缀后逐字节比较（只比较声明契约）。
mkdir -p "$build"
norm() { perl -pe 's/^\s*\/\/.*$//; s/\bBb/Gow3/g; s/\bBB_LSFG/GOW3_LSFG/g; s/\bbb_lsfg_/gow3_lsfg_/g; s/^\s+$//' "$1"; }
norm "$src/android/bb_lsfg.h" > "$build/.bb_lsfg.normalized"
norm tools/android/lsfg/gow3_lsfg_api.h > "$build/.gow3_lsfg_api.normalized"
cmp -s "$build/.bb_lsfg.normalized" "$build/.gow3_lsfg_api.normalized" ||
    { echo "tools/android/lsfg/gow3_lsfg_api.h differs from the pinned commit's android/bb_lsfg.h (normalized)" >&2; exit 1; }
# A build tree made from another source (an older layout) is not reused.
if [[ -f $build/CMakeCache.txt ]] && ! grep -qx "CMAKE_HOME_DIRECTORY:INTERNAL=$src" "$build/CMakeCache.txt"; then
    rm -rf "$build"
fi
# bloodframe 的 build-android.sh 用 nproc（Linux）；macOS 垫一个 sysctl shim。
shim="$build/path-shim"
mkdir -p "$shim"
if ! command -v nproc >/dev/null 2>&1; then
    printf '#!/bin/sh\nsysctl -n hw.ncpu\n' > "$shim/nproc"
    chmod +x "$shim/nproc"
    export PATH="$shim:$PATH"
fi
if ! realpath -m / 2>/dev/null | grep -q '^/'; then
    # macOS 的 realpath 无 -m/--：垫一个支持 `realpath [-m] -- path` 的实现。
    { printf '#!/bin/sh\nm=0\np=\nfor a in "$@"; do\n  case $a in -m|--m) m=1 ;; --) ;; *) p=$a ;; esac\ndone\n'
      printf 'case $p in /*) ;; *) p="$PWD/$p" ;; esac\n'
      printf '[ -e "$p" ] && { cd "$(dirname "$p")" && printf "%%s/%%s\n" "$(pwd)" "$(basename "$p")"; exit 0; }\n'
      printf '[ $m = 1 ] && { mkdir -p "$p" && cd "$p" && pwd; exit 0; }\nexit 1\n'
    } > "$shim/realpath"
    chmod +x "$shim/realpath"
    export PATH="$shim:$PATH"
fi
nice -n 19 "$src/build-android.sh" --ndk "$ndk" --api "$api" --out "$out" --build-dir "$build" --jobs "$J"
