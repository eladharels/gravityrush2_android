#!/usr/bin/env bash
# Bionic（Android NDK）构建的第三方依赖，输出到 .local-deps/android/bionic-prefix。
# 版本与旧 glibc（Debian trixie）Android 构建用到的版本刻意一致（也和 PC 构建一致）。
# 源码下载到 .local-deps/android/bionic-src，构建目录 bionic-build。
# 宿主机前置条件：curl、tar、cmake、ninja、make、python3。
# 归因：本脚本是对 Bloodborne-Android（BB）tools/android/build_bionic_deps.sh 的 gow3 移植版，
#       NDK 依赖版本、构建参数与 BB 保持一致，便于逐 commit 对照。
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
root=$PWD/.local-deps/android
# CI activates the conda host environment (ci/environment.yml), whose x86-64 compiler packages
# export CC/CFLAGS/LDFLAGS (-march=nocona, -L$CONDA_PREFIX/lib, ...). FFmpeg's configure and CMake
# take those as flags for the NDK cross compiler, which then cannot build a test program.
unset CC CXX CPP LD AR AS NM RANLIB STRIP OBJCOPY OBJDUMP READELF \
      CFLAGS CXXFLAGS CPPFLAGS LDFLAGS DEBUG_CFLAGS DEBUG_CXXFLAGS DEBUG_CPPFLAGS

# NDK r29：优先 GOW3_NDK 环境变量；其次 .local-deps/android/sdk（构建机本地拷贝）；
# 再回落宿主机 Android SDK 常见位置。默认与 BB 固定版本一致。
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
prefix=$root/bionic-prefix src=$root/bionic-src bld=$root/bionic-build
[[ -f $ndk/build/cmake/android.toolchain.cmake ]] || {
    echo "No NDK r29 at $ndk (set GOW3_NDK or install NDK 29.0.14206865)" >&2
    exit 1
}
mkdir -p "$prefix" "$src" "$bld"
J=${GOW3_JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}
# The cores left to the build: all but the first two on big machines (the workstation this was
# written on has 24), all of them on small ones such as a 4-core CI runner.
ncpu=$(nproc 2>/dev/null || echo 4)
if (( ncpu > 8 )); then build_cpus=2-$((ncpu - 1)); else build_cpus=0-$((ncpu - 1)); fi
# macOS 没有 taskset；Linux 上用它限核避免拖垮机器。
if command -v taskset >/dev/null 2>&1; then
    run() { taskset -c "$build_cpus" nice -n 19 "$@"; }
else
    run() { "$@"; }
fi
# NDK 宿主侧 prebuilt 工具链目录名随宿主平台变化（linux-x86_64 / darwin-x86_64）。
prebuilt=$(find "$ndk/toolchains/llvm/prebuilt" -maxdepth 1 -type d -name "*x86_64" | head -1)
[[ -n $prebuilt ]] || { echo "No NDK llvm prebuilt toolchain under $ndk" >&2; exit 1; }

# 每个下载输入的固定 SHA-256（版本与 BB/glibc sysroot 一致，见下方 fetch 清单）。
# 用 case 而不是关联数组，兼容 macOS 自带的 bash 3.2。
bionic_sha() {
    case "$1" in
        fmt-10.1.1)              echo 78b8c0a72b1c35e4443a7e308df52498252d1cefc2b08c9a97bc9ee6cfe61f8b ;;
        zycore-1.5.2)            echo 943f91eb9ab2a8cc01ab9f8b785e769a273502071e0ee8011cdfcaad93947cec ;;
        zydis-4.1.1)             echo 45c6d4d499a1cc80780f7834747c637509777c01dca1e98c5e7c0bfaccdb1514 ;;
        xxhash-0.8.3)            echo aae608dfe8213dfd05d909a57718ef82f30722c392344583d3f39050c7f29a80 ;;
        miniz-3.1.2)             echo 98468f8924934b723276680f85238b6c78bf1f8b49b4459cc9b7214a20e2e9fb ;;
        SDL3-3.2.10)             echo f87be7b4dec66db4098e9c167b2aa34e2ca10aeb5443bdde95ae03185ed513e0 ;;
        Vulkan-Headers-1.4.363)  echo cbaf687d3c59b9666fe080e7f8396b6b4f4d344a768ee6b337c59852f4526b68 ;;
        SPIRV-Headers-1.4.309)   echo a96f8b4f2dfb18f7432e5c523e220ab0075372a9509e0c25fbff21c76af0de7c ;;
        ffmpeg-7.1.5)            echo de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f ;;
        *)                       echo "" ;;
    esac
}
fetch() { # name url archive-extension
    local dir=$src/$1
    [[ -d $dir ]] && return
    local sha
    sha=$(bionic_sha "$1")
    [[ -n $sha ]] || { echo "No pinned SHA-256 for $1" >&2; exit 1; }
    curl -fsSL "$2" -o "$src/$1.tar" && mkdir -p "$dir.tmp" && \
        echo "$sha  $src/$1.tar" | shasum -a 256 -c - >/dev/null && \
        tar -xf "$src/$1.tar" -C "$dir.tmp" --strip-components=1 \
        && mv "$dir.tmp" "$dir" && rm "$src/$1.tar"
}
fetch_git() { # name url tag commit: a shallow clone of the tag, checked against the pinned commit
    local dir=$src/$1
    [[ -d $dir ]] && return
    rm -rf "$dir.tmp"
    git clone -q --depth 1 --branch "$3" "$2" "$dir.tmp" &&
        [[ $(git -C "$dir.tmp" rev-parse HEAD) == "$4" ]] && mv "$dir.tmp" "$dir" ||
        { echo "fetch_git: $1 is not commit $4" >&2; exit 1; }
}
cm() { # name source-dir extra cmake args...
    local name=$1 sdir=$2; shift 2
    [[ -f $prefix/.done-$name ]] && return
    cmake -S "$sdir" -B "$bld/$name" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$api -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_PREFIX_PATH="$prefix" -DCMAKE_FIND_ROOT_PATH="$prefix" "$@" >/dev/null
    run cmake --build "$bld/$name" -j "$J" >"$bld/$name.log" 2>&1 || { tail -30 "$bld/$name.log" >&2; exit 1; }
    cmake --install "$bld/$name" >/dev/null
    touch "$prefix/.done-$name"
}

# uncharted_android: fmt 11 (the shadPS4 v0.19 core of the Uncharted port includes <fmt/base.h>),
# pinned by its tag commit and fetched with git.
fetch_git fmt-11.2.0 https://github.com/fmtlib/fmt.git 11.2.0 40626af88bd7df9a5fb80be7b25ac85b122d6c21
fetch zycore-1.5.2 https://github.com/zyantific/zycore-c/archive/refs/tags/v1.5.2.tar.gz
fetch zydis-4.1.1 https://github.com/zyantific/zydis/archive/refs/tags/v4.1.1.tar.gz
fetch xxhash-0.8.3 https://github.com/Cyan4973/xxHash/archive/refs/tags/v0.8.3.tar.gz
fetch miniz-3.1.2 https://github.com/richgel999/miniz/archive/refs/tags/3.1.2.tar.gz
fetch SDL3-3.2.10 https://github.com/libsdl-org/SDL/releases/download/release-3.2.10/SDL3-3.2.10.tar.gz
fetch Vulkan-Headers-1.4.363 https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.4.363.tar.gz
fetch SPIRV-Headers-1.4.309 https://github.com/KhronosGroup/SPIRV-Headers/archive/refs/tags/vulkan-sdk-1.4.309.0.tar.gz
fetch ffmpeg-7.1.5 https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz
# gravityrush2_android: libSceFont (src/runtime_gr2_font.c) renders glyphs with FreeType.
fetch_git freetype-2.13.3 https://github.com/freetype/freetype.git VER-2-13-3 42608f77f20749dd6ddc9e0536788eaad70ea4b5

# 仅头文件：vulkan-hpp（NDK 自带 Vulkan 1.3.275 C 头但没有它）与 SPIR-V。
cm vulkan-headers "$src/Vulkan-Headers-1.4.363"
cm spirv-headers "$src/SPIRV-Headers-1.4.309"
cm fmt11 "$src/fmt-11.2.0" -DFMT_TEST=OFF -DFMT_DOC=OFF
cm zycore "$src/zycore-1.5.2" -DBUILD_SHARED_LIBS=OFF
# release 压缩包缺 zycore 子模块：直接用同一份 Zycore 源码树。
[[ -f $src/zydis-4.1.1/dependencies/zycore/CMakeLists.txt ]] || cp -a "$src/zycore-1.5.2/." "$src/zydis-4.1.1/dependencies/zycore/"
cm zydis "$src/zydis-4.1.1" -DBUILD_SHARED_LIBS=OFF -DZYDIS_BUILD_EXAMPLES=OFF -DZYDIS_BUILD_TOOLS=OFF \
    -DZYDIS_BUILD_DOXYGEN=OFF
cm xxhash "$src/xxhash-0.8.3/cmake_unofficial" -DBUILD_SHARED_LIBS=OFF -DXXHASH_BUILD_XXHSUM=OFF
# Static, without its optional libraries (the game's fonts are plain OpenType/TrueType).
cm freetype "$src/freetype-2.13.3" -DBUILD_SHARED_LIBS=OFF -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON     -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
cm miniz "$src/miniz-3.1.2" -DBUILD_SHARED_LIBS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTS=OFF -DINSTALL_PROJECT=ON
# SDL3：共享（gow3-probe 与 libgow3gpu.so 必须共享同一份 SDL），仅 Android 后端；
# window/audio/input 路径是第二阶段（exec 出的进程背后没有 SDLActivity/JNI）。
cm sdl3 "$src/SDL3-3.2.10" -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST_LIBRARY=OFF -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF

# FFmpeg（avplayer/videodec）：静态、PIC、无 programs，只带内置 codec/demuxer。
if [[ ! -f $prefix/.done-ffmpeg ]]; then
    tc=$prebuilt
    mkdir -p "$bld/ffmpeg" && pushd "$bld/ffmpeg" >/dev/null
    "$src/ffmpeg-7.1.5/configure" --prefix="$prefix" --enable-cross-compile --target-os=android --arch=aarch64 \
        --cc="$tc/bin/aarch64-linux-android$api-clang" --cxx="$tc/bin/aarch64-linux-android$api-clang++" \
        --ar="$tc/bin/llvm-ar" --ranlib="$tc/bin/llvm-ranlib" --strip="$tc/bin/llvm-strip" --nm="$tc/bin/llvm-nm" \
        --enable-static --disable-shared --enable-pic --disable-programs --disable-doc --disable-debug \
        --disable-avdevice --disable-avfilter --disable-network --disable-autodetect --disable-mediacodec --disable-jni >"$bld/ffmpeg-configure.log" 2>&1 \
        || { tail -30 "$bld/ffmpeg-configure.log" >&2
             echo "---- ffbuild/config.log (tail) ----" >&2
             tail -60 "$bld/ffmpeg/ffbuild/config.log" >&2 2>/dev/null || true
             exit 1; }
    run make -j"$J" >"$bld/ffmpeg.log" 2>&1 || { tail -30 "$bld/ffmpeg.log" >&2; exit 1; }
    make install >/dev/null
    # configure 的 Android 目标会为 MediaCodec/JNI 链接 libandroid/libmediandk，这里都禁用了。
    # macOS 自带 BSD sed 需要 -i ''（Linux GNU sed 兼容两种写法）。
    if [[ "$(uname -s)" == "Darwin" ]]; then
        sed -i '' 's/ -landroid -lmediandk//' "$prefix"/lib/pkgconfig/libavutil.pc
    else
        sed -i 's/ -landroid -lmediandk//' "$prefix"/lib/pkgconfig/libavutil.pc
    fi
    popd >/dev/null
    touch "$prefix/.done-ffmpeg"
fi
echo "Bionic deps in $prefix"
