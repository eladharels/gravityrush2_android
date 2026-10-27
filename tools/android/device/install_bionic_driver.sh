#!/usr/bin/env bash
# Install a Vulkan driver pack (an adrenotools-style Turnip zip) into the launcher and select it.
#
# The launcher does not ship a driver manager: run-bionic.sh reads gpu_driver from gow3.ini and
# resolves it to files/drivers/<name>/driver.lib, whose content is the library file name next to
# it. The game's renderer resolves every Vulkan entry point through gow3_vk_get_instance_proc_addr()
# (gpu/shim/vk_driver_loader.cpp), which dlopens exactly that library, so the driver here is the one
# the game itself runs on - not only the smoke test.
#
# usage: install_bionic_driver.sh <pack.zip> [--name turnip] [--device serial] [--disable]
set -euo pipefail
cd -- "$(dirname -- "$0")/../../.."

pack=
name=turnip
serial=
enable=1
while [[ $# -gt 0 ]]; do
    case $1 in
        --name) name=$2; shift 2 ;;
        --device) serial=$2; shift 2 ;;
        --disable) enable=0; shift ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) [[ -z $pack ]] || { echo "unexpected argument $1" >&2; exit 2; }; pack=$1; shift ;;
    esac
done
[[ -n $pack ]] || { echo "usage: install_bionic_driver.sh <pack.zip> [--name turnip] [--device serial]" >&2; exit 2; }
[[ -f $pack ]] || { echo "no such pack: $pack" >&2; exit 2; }
adb_dev=(adb)
[[ -n $serial ]] && adb_dev=(adb -s "$serial")
pkg=com.gravityrush2.android

# The pack holds the driver library and a meta.json naming it; pick the library the pack declares,
# falling back to the single .so inside.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -q "$pack" -d "$work"
lib=$(python3 - "$work" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
declared = None
meta = root / 'meta.json'
if meta.is_file():
    try:
        declared = json.loads(meta.read_text()).get('libraryName')
    except ValueError:
        declared = None
if declared and (root / declared).is_file():
    print(declared)
else:
    candidates = sorted(p.name for p in root.glob('*.so'))
    print(candidates[0] if len(candidates) == 1 else '')
PY
)
[[ -n $lib ]] || { echo "cannot tell which library the pack carries" >&2; exit 1; }
so=$(find "$work" -name "$lib" -type f | head -1)
[[ -n $so ]] || { echo "pack does not contain $lib" >&2; exit 1; }

# The loader accepts three shapes (vkGetInstanceProcAddr, an ICD's vk_icdGetInstanceProcAddr, or an
# Android Vulkan HAL exporting HMI); say which one this is so an unexpected driver is obvious.
say_shape() {
    local symbols
    symbols=$(llvm-nm -D --defined-only "$so" 2>/dev/null || nm -gU "$so" 2>/dev/null || true)
    if [[ $symbols == *vk_icdGetInstanceProcAddr* ]]; then echo "ICD (vk_icdGetInstanceProcAddr)"
    elif [[ $symbols == *vkGetInstanceProcAddr* ]]; then echo "driver (vkGetInstanceProcAddr)"
    elif [[ $symbols == *HMI* ]]; then echo "Android HAL (HMI)"
    else echo "unknown - the loader will refuse it"; fi
}

remote_dir="files/drivers/$name"
"${adb_dev[@]}" shell "run-as $pkg sh -c 'mkdir -p $remote_dir'"
"${adb_dev[@]}" push "$so" /data/local/tmp/gow3-driver.so >/dev/null
"${adb_dev[@]}" shell "run-as $pkg sh -c 'cat /data/local/tmp/gow3-driver.so > $remote_dir/$lib && chmod 600 $remote_dir/$lib'"
"${adb_dev[@]}" shell "run-as $pkg sh -c 'echo $lib > $remote_dir/driver.lib'"

ini=files/gow3data/gow3.ini
if [[ $enable == 1 ]]; then
    "${adb_dev[@]}" shell "run-as $pkg sh -c 'mkdir -p files/gow3data; grep -q ^gpu_driver= $ini && sed -i s/^gpu_driver=.*/gpu_driver=$name/ $ini || echo gpu_driver=$name >> $ini'"
else
    "${adb_dev[@]}" shell "run-as $pkg sh -c 'grep -q ^gpu_driver= $ini && sed -i s/^gpu_driver=.*/gpu_driver=system/ $ini || echo gpu_driver=system >> $ini'"
fi

echo "driver $lib -> $remote_dir  [$(say_shape)]"
echo "gpu_driver=$([ $enable == 1 ] && echo "$name" || echo system)"
"${adb_dev[@]}" shell "run-as $pkg sh -c 'grep ^gpu_driver $ini'" 2>/dev/null || true
echo "The next launch reports 'GPU driver: ...' in files/gow3data/logs/android-game.log."
