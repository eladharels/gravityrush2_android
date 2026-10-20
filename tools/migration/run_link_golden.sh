#!/usr/bin/env bash
# Parity gate for the C++ boot-image port (android-launcher/app/src/main/cpp/gow3link) against the
# Python reference (scripts/prepare.py, scripts/link_modules.py), which stays the behaviour baseline.
#
# Both sides build boot.bin (G3PROBE2) and boot-linked.bin (G3PROBE5) from the same game and the
# result is compared byte for byte. boot.bin is checked on its own too, so a failure says whether
# the eboot flattening or the module linking drifted.
#
# usage: run_link_golden.sh <game-dir> [--keep]
set -uo pipefail
cd -- "$(dirname -- "$0")/../.."
root=$PWD

game=${1:-}
[[ -n $game ]] || { echo "usage: run_link_golden.sh <game-dir> [--keep]" >&2; exit 2; }
game=$(cd -- "$game" && pwd)
keep=0
[[ ${2:-} == --keep ]] && keep=1

for module in eboot.bin sce_module/libc.prx sce_module/libSceFios2.prx; do
    [[ -f $game/$module ]] || { echo "missing $game/$module" >&2; exit 2; }
done
work=$(mktemp -d)
[[ $keep == 1 ]] || trap 'rm -rf "$work"' EXIT

# --- the C++ side, built for the host: no NDK needed to run the parity check
binary=$work/gow3_link_golden
clang++ -std=c++17 -O1 -Wall -Wextra -Wno-unused-parameter -Wno-unused-but-set-variable \
    -I "$root/android-launcher/app/src/main/cpp/gow3link" \
    -I "$root/android-launcher/app/src/main/cpp/gow3prepare" \
    "$root/tools/migration/gow3_link_golden.cpp" \
    "$root/android-launcher/app/src/main/cpp/gow3link/gow3_link.cpp" \
    "$root/android-launcher/app/src/main/cpp/gow3prepare/gow3_prepare.cpp" \
    -o "$binary" || { echo "the parity test does not build" >&2; exit 1; }

# --- the Python side
py=$work/python
mkdir -p "$py"
python3 scripts/prepare.py "$game" --out "$py" >"$py/prepare.log" 2>&1 || {
    echo "prepare.py failed:"; tail -5 "$py/prepare.log"; exit 1; }
python3 scripts/link_modules.py "$game" --out "$py" >"$py/modules.log" 2>&1 || {
    echo "link_modules.py failed:"; tail -5 "$py/modules.log"; exit 1; }

if "$binary" "$game" "$py"; then
    echo "PASS: boot.bin and boot-linked.bin match scripts/*.py byte for byte"
    exit 0
fi
echo "FAIL: the C++ boot image differs from the Python reference"
exit 1
