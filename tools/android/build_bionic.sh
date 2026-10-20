#!/usr/bin/env bash
# Bionic（Android NDK）构建原生游戏进程，这是 Android app 的唯一 runtime：
# libgow3cpu.so（FEXCore）、libgow3gpu.so（GPU/HLE）、gow3-probe（runtime 可执行）、
# kgsl-chip-id。输出：out/bionic/{bin,lib}。
# 前置：tools/android/build_bionic_deps.sh（NDK + 第三方库）。
# STL：全部 c++_shared（libgow3cpu/libgow3gpu/gow3-probe 共享同一份 libc++_shared.so；
# 静态 libc++ 出现在多个 DSO 里会让每个都有自己的 exception/RTTI/new 状态）。
# 归因：本脚本是 Bloodborne-Android（BB）tools/android/build_bionic.sh 的 gow3 移植版。
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
out=out/bionic
root=$PWD/.local-deps/android
# As build_bionic_deps.sh: the conda host environment's x86-64 compiler flags must not reach the NDK.
unset CC CXX CPP LD AR AS NM RANLIB STRIP OBJCOPY OBJDUMP READELF \
      CFLAGS CXXFLAGS CPPFLAGS LDFLAGS DEBUG_CFLAGS DEBUG_CXXFLAGS DEBUG_CPPFLAGS

# NDK r29：优先 GOW3_NDK，其次 .local-deps/android/sdk，再回落宿主机 Android SDK。
ndk=${GOW3_NDK:-}
if [[ -z $ndk || ! -f "$ndk/build/cmake/android.toolchain.cmake" ]]; then
    for candidate in \
        "$root/sdk/ndk/29.0.14206865" \
        "$ANDROID_HOME/ndk/29.0.14206865" \
        "$ANDROID_SDK_ROOT/ndk/29.0.14206865" \
        "$HOME/Library/Android/sdk/ndk/29.0.14206865" \
        "$HOME/Android/Sdk/ndk/29.0.14206865"; do
        if [[ -f "$candidate/build/cmake/android.toolchain.cmake" ]]; then
            ndk=$candidate
            break
        fi
    done
fi
api=${GOW3_ANDROID_API:-30}
prefix=$root/bionic-prefix
# 仅头文件包（magic_enum、robin-map、VMA、Boost）来自宿主构建的依赖。
headers=${GOW3_ARM64_HEADERS:-$PWD/.local-deps/root/usr}
toolchain=$ndk/build/cmake/android.toolchain.cmake
llvm=$(find "$ndk/toolchains/llvm/prebuilt" -maxdepth 1 -type d -name "*x86_64" | head -1)
J=${GOW3_JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}
# The cores left to the build: all but the first two on big machines (the workstation this was
# written on has 24), all of them on small ones such as a 4-core CI runner.
ncpu=$(nproc 2>/dev/null || echo 4)
if (( ncpu > 8 )); then build_cpus=2-$((ncpu - 1)); else build_cpus=0-$((ncpu - 1)); fi
run() { if command -v taskset >/dev/null 2>&1; then taskset -c "$build_cpus" nice -n 19 "$@"; else "$@"; fi; }
[[ -f $toolchain ]] || { echo "No NDK: $ndk (tools/android/build_bionic_deps.sh)" >&2; exit 1; }
[[ -f $prefix/.done-ffmpeg ]] || { echo "No Bionic deps in $prefix (tools/android/build_bionic_deps.sh)" >&2; exit 1; }
[[ -d $headers/share/cmake/tsl-robin-map ]] || { echo "No header-only packages in $headers" >&2; exit 1; }
mkdir -p "$out"
ndk_cmake=(-DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$api -DANDROID_STL=c++_shared)

# FEX：无 glibc jemalloc hook（仅 glibc；它服务于 thunks，本构建不用）。
if [[ ! -f $out/fex/build.ninja ]]; then
    cmake -S third_party/FEX -B "$out/fex" -G Ninja "${ndk_cmake[@]}" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_PROJECT_FEX_INCLUDE="$PWD/cpu/fex_project.cmake" \
        -DBUILD_TESTING=OFF -DBUILD_THUNKS=OFF -DBUILD_FEXCONFIG=OFF -DBUILD_STEAM_SUPPORT=OFF \
        -DBUILD_FEX_LINUX_TESTS=OFF -DENABLE_ZYDIS=OFF -DTUNE_ARCH=armv8.2-a -DTUNE_CPU=generic \
        -DENABLE_JEMALLOC_GLIBC_ALLOC=OFF >/dev/null
fi
run ninja -C "$out/fex" -j "$J" gow3cpu > "$out/fex-build.log" 2>&1 || { grep -E "error|FAILED" "$out/fex-build.log" | head -40 >&2; exit 1; }

# GPU 库：不链接 libvulkan（gpu/shim/vk_driver_loader.cpp dlopen 驱动），无 X11。
export PKG_CONFIG_PATH=$prefix/lib/pkgconfig:$prefix/share/pkgconfig
export PKG_CONFIG_LIBDIR=$PKG_CONFIG_PATH
# Boost 仅头文件：优先宿主构建的依赖树，其次宿主机（Homebrew）已装的 CMake 包，
# 与旧 glibc 构建（build_arm64.sh 用 GOW3_BOOST_DIR 指向 host 包）一致。
boost_dir=${GOW3_BOOST_DIR:-}
boost_headers_dir=${GOW3_BOOST_HEADERS_DIR:-}
if [[ -z $boost_dir ]]; then
    for candidate in \
        "$headers/lib/cmake/Boost-1.87.0" \
        "$headers/lib/cmake/Boost-1.92.0" \
        "$(brew --prefix 2>/dev/null)/lib/cmake/Boost-1.92.0"; do
        if [[ -f "$candidate/BoostConfig.cmake" ]]; then
            boost_dir=$candidate
            break
        fi
    done
fi
if [[ -z $boost_headers_dir ]]; then
    for candidate in \
        "$headers/lib/cmake/boost_headers-1.87.0" \
        "$headers/lib/cmake/boost_headers-1.92.0" \
        "$(brew --prefix 2>/dev/null)/lib/cmake/boost_headers-1.92.0"; do
        if [[ -f "$candidate/boost_headers-config.cmake" ]]; then
            boost_headers_dir=$candidate
            break
        fi
    done
fi
[[ -n $boost_dir && -f $boost_dir/BoostConfig.cmake ]] || {
    echo "No Boost CMake package (set GOW3_BOOST_DIR or install Boost headers)" >&2; exit 1; }
if [[ ! -f $out/gpu/build.ninja ]]; then
    cmake -S gpu -B "$out/gpu" -G Ninja "${ndk_cmake[@]}" \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo -DGOW3_PGO=off -DGOW3_LTO=OFF \
        "-DCMAKE_FIND_ROOT_PATH=$prefix;$headers" -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
        -DCMAKE_PREFIX_PATH="$prefix;$headers" \
        -Dtsl-robin-map_DIR="$headers/share/cmake/tsl-robin-map" \
        -DVulkanMemoryAllocator_DIR="$headers/share/cmake/VulkanMemoryAllocator" \
        -DBoost_DIR="$boost_dir" \
        -Dboost_headers_DIR="$boost_headers_dir" \
        -Dmagic_enum_DIR="$headers/share/cmake/magic_enum" >/dev/null
fi
run ninja -C "$out/gpu" -j "$J" gow3gpu > "$out/gpu-build.log" 2>&1 || { grep -v '^\[' "$out/gpu-build.log" | tail -40 >&2; exit 1; }

# bin/ 和 lib/ 并排，可执行文件经 $ORIGIN 找 lib/。
mkdir -p "$out/bin" "$out/lib"
# 发布产物剥离调试符号（RelWithDebInfo 的 -g 让 libgow3gpu.so 达到 118MB；strip 后与 glibc
# bundle 的 14MB 同量级）。保留编译目录的未 strip 副本供调试。
"$llvm/bin/llvm-strip" -o "$out/lib/libgow3cpu.so" "$out/fex/libgow3cpu.so"
"$llvm/bin/llvm-strip" -o "$out/lib/libgow3gpu.so" "$out/gpu/libgow3gpu.so"
# gow3gpu 在 Android 上不再链接 SDL（AHB present 路径移除）；但 gow3-probe 的 runtime_pad.c
# 仍链接 SDL3（探测/关闭手柄），libSDL3.so 由依赖构建链产出，随 runtime 一起分发。
if "$llvm/bin/llvm-readelf" -d "$out/gpu/libgow3gpu.so" | grep -q 'libSDL3'; then
    cp -f "$prefix/lib/libSDL3.so" "$out/lib/"
fi
if "$llvm/bin/llvm-readelf" -d "$out/bin/gow3-probe" 2>/dev/null | grep -q 'libSDL3'; then
    cp -f "$prefix/lib/libSDL3.so" "$out/lib/"
fi
cp -f "$llvm/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$out/lib/"

cc=("$llvm/bin/aarch64-linux-android$api-clang" -fuse-ld=lld)
mkdir -p "$out/atrac9"
for source in third_party/LibAtrac9/C/src/*.c; do
    "${cc[@]}" -std=c99 -O2 -g -w -fPIC -c "$source" -o "$out/atrac9/$(basename "${source%.c}").o"
done
"$llvm/bin/llvm-ar" rcs "$out/libatrac9.a" "$out"/atrac9/*.o
# PIE（Android 要求；PC 构建用 -no-pie 服务 brk 堆，见 src/probe.c main）、
# -rdynamic：libgow3gpu 对可执行文件解析 runtime_* 符号。只链接 NDK sysroot：
# 无 libvulkan、无我们自己的 loader。runtime_videodec.c 直接调用 avcodec，
# 因此 probe 需要链接 Bionic 前缀里的静态 FFmpeg（与 build_arm64.sh 的 glibc probe 一致）。
export PKG_CONFIG_SYSROOT_DIR=
read -r -a pv_includes <<< "$(pkg-config --cflags libavcodec libavutil libswscale libswresample sdl3)"
read -r -a pv_libraries <<< "$(pkg-config --libs libavcodec libavutil libswscale libswresample sdl3)"
links=(-L"$out/lib" -lgow3gpu -lgow3cpu -Wl,-rpath,'$ORIGIN/../lib' -rdynamic -pie)
"${cc[@]}" -std=c11 -O2 -g -Wall -Wextra -Werror -pthread -I"$prefix/include" -I. -Isrc \
    "${pv_includes[@]}" \
    src/probe.c src/runtime*.c src/vulkan_smoke.c "$out/libatrac9.a" -lm "${links[@]}" \
    "${pv_libraries[@]}" \
    -o "$out/bin/gow3-probe"
"${cc[@]}" -std=c11 -O2 -Wall -Wextra -Werror tools/android/build/kgsl_chip_id.c -pie \
    -o "$out/bin/kgsl-chip-id"
# 帧生成引擎（change add-native-frame-generation）：libbloodframe.so（MIT，钉 commit，
# 首次构建需网络 clone）。runtime-manifest 将其列为必需——失败即失败，不静默缺库。
bash tools/android/build_framegen.sh

echo "Built $out/bin/gow3-probe, $out/lib/libgow3gpu.so, $out/lib/libgow3cpu.so, $out/lib/libbloodframe.so (Bionic, API $api)"
