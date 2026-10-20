#!/usr/bin/env bash
# The header-only packages build_bionic.sh takes from a host prefix (GOW3_ARM64_HEADERS, by default
# .local-deps/root/usr): tsl-robin-map, VulkanMemoryAllocator, magic_enum and the Boost headers.
# docs/QUICKSTART.md 2.2 installs them by hand on a workstation; this does it for a fresh machine
# such as a CI runner. Header-only: the host compiler only runs CMake's checks.
#
#   robin-map, VMA   pinned tags from GitHub, installed with CMake
#   magic_enum       the gpu/third_party/magic_enum submodule
#   Boost            linked from a host prefix that has it (CONDA_PREFIX in CI: ci/environment.yml
#                    boost-cpp), or GOW3_BOOST_PREFIX
#
# usage: stage_header_deps.sh [prefix]
set -euo pipefail
cd -- "$(dirname -- "$0")/../../.."
prefix=${1:-$PWD/.local-deps/root/usr}
work=$PWD/.local-deps/header-src
mkdir -p "$prefix" "$work"

install_git() { # name url tag cmake-args...
    local name=$1 url=$2 tag=$3; shift 3
    [[ -d $work/$name ]] || git clone -q --depth 1 --branch "$tag" "$url" "$work/$name"
    cmake -S "$work/$name" -B "$work/$name/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$prefix" "$@" >/dev/null
    cmake --build "$work/$name/build" >/dev/null
    cmake --install "$work/$name/build" >/dev/null
    echo "header deps: $name $tag"
}

[[ -d $prefix/share/cmake/tsl-robin-map ]] ||
    install_git robin-map https://github.com/Tessil/robin-map.git v1.4.0
[[ -d $prefix/share/cmake/VulkanMemoryAllocator ]] ||
    install_git vma https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator.git v3.2.1
if [[ ! -d $prefix/share/cmake/magic_enum ]]; then
    [[ -f gpu/third_party/magic_enum/CMakeLists.txt ]] || { echo "magic_enum submodule missing" >&2; exit 1; }
    cmake -S gpu/third_party/magic_enum -B "$work/magic_enum-build" -G Ninja \
        -DCMAKE_INSTALL_PREFIX="$prefix" -DMAGIC_ENUM_OPT_BUILD_EXAMPLES=OFF \
        -DMAGIC_ENUM_OPT_BUILD_TESTS=OFF -DMAGIC_ENUM_OPT_INSTALL=ON >/dev/null
    cmake --install "$work/magic_enum-build" >/dev/null
    # magic_enum installs its package under lib/cmake or share/cmake depending on the version.
    if [[ ! -d $prefix/share/cmake/magic_enum ]]; then
        found=$(find "$prefix" -type d -path '*/cmake/magic_enum' | head -1)
        [[ -n $found ]] && mkdir -p "$prefix/share/cmake" && ln -sfn "$found" "$prefix/share/cmake/magic_enum"
    fi
    echo "header deps: magic_enum (submodule)"
fi

if ! compgen -G "$prefix/lib/cmake/Boost-*" >/dev/null; then
    boost_from=${GOW3_BOOST_PREFIX:-${CONDA_PREFIX:-}}
    config=$(ls -d "$boost_from"/lib/cmake/Boost-* 2>/dev/null | sort -V | tail -1 || true)
    [[ -n $boost_from && -n $config && -d $boost_from/include/boost ]] || {
        echo "No Boost to link: set GOW3_BOOST_PREFIX to a prefix with include/boost and lib/cmake/Boost-*" >&2
        exit 1; }
    mkdir -p "$prefix/include" "$prefix/lib/cmake"
    ln -sfn "$boost_from/include/boost" "$prefix/include/boost"
    for dir in "$boost_from"/lib/cmake/Boost-* "$boost_from"/lib/cmake/boost_headers-*; do
        [[ -d $dir ]] && ln -sfn "$dir" "$prefix/lib/cmake/$(basename "$dir")"
    done
    echo "header deps: Boost from $boost_from ($(basename "$config"))"
fi
