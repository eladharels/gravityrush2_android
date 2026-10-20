#!/usr/bin/env bash
# Parity gate for the C++ game-preparation port (android-launcher/app/src/main/cpp/gow3prepare)
# against the Python reference (scripts/*.py), which stays the behaviour baseline.
#
# Both sides read the same XML (through tools/migration/make_patch_input.py) and are given the same
# selection, then their patches.bin is compared byte for byte. Every patch is also run on its own,
# so a failure names the patch instead of the whole set.
#
# usage: run_prepare_golden.sh <game-dir> [title-id] [app-version] [--keep]
#   <game-dir>     a game folder with eboot.bin, sce_sys and sce_module
#   [title-id]     default: read from sce_sys/param.sfo
#   [app-version]  default: read from sce_sys/param.sfo
set -uo pipefail
cd -- "$(dirname -- "$0")/../.."
root=$PWD

game=${1:-}
[[ -n $game ]] || { echo "usage: run_prepare_golden.sh <game-dir> [title] [version]" >&2; exit 2; }
game=$(cd -- "$game" && pwd)
title=${2:-}
version=${3:-}
keep=0
[[ ${4:-} == --keep ]] && keep=1

if [[ -z $title || -z $version ]]; then
    read -r title version < <(python3 - "$game" <<'PY'
import sys, pathlib
sys.path.insert(0, 'scripts')
from prepare import read_sfo
sfo = read_sfo(pathlib.Path(sys.argv[1]) / 'sce_sys' / 'param.sfo') or {}
print(sfo.get('TITLE_ID', ''), sfo.get('APP_VER', ''))
PY
)
fi
[[ -n $title && -n $version ]] || { echo "cannot read the game identity" >&2; exit 2; }

xml=$root/patches/God_of_War_III_Remastered_$title.xml
[[ -f $xml ]] || { echo "no patch XML for $title: $xml" >&2; exit 2; }
work=$(mktemp -d)
[[ $keep == 1 ]] || trap 'rm -rf "$work"' EXIT

# --- the C++ side, built for the host: no NDK needed to run the parity check
binary=$work/gow3_golden
clang++ -std=c++17 -O1 -Wall -Wextra -Wno-unused-parameter \
    -I "$root/android-launcher/app/src/main/cpp/gow3prepare" \
    "$root/tools/migration/gow3_prepare_golden.cpp" \
    "$root/android-launcher/app/src/main/cpp/gow3prepare/gow3_prepare.cpp" \
    -o "$binary" || { echo "the parity test does not build" >&2; exit 1; }

input=$work/input.txt
python3 "$root/tools/migration/make_patch_input.py" "$xml" "$input" "$version" >/dev/null

# --- the Python side: the preparation steps that do not depend on the selection, once
py=$work/python
mkdir -p "$py"
python3 scripts/prepare.py "$game" --out "$py" >"$py/prepare.log" 2>&1 || {
    echo "prepare.py failed:"; tail -5 "$py/prepare.log"; exit 1; }
python3 scripts/link_libc.py "$game" --out "$py" >"$py/libc.log" 2>&1 || {
    echo "link_libc.py failed:"; tail -5 "$py/libc.log"; exit 1; }
python3 scripts/link_modules.py "$game" --out "$py" >"$py/modules.log" 2>&1 || {
    echo "link_modules.py failed:"; tail -5 "$py/modules.log"; exit 1; }
python3 scripts/content_profile.py "$game" --out "$py" --sku full >"$py/content.log" 2>&1 || {
    echo "content_profile.py failed:"; tail -5 "$py/content.log"; exit 1; }

# --- the reference for one selection: compile_plan() with the names given, so the launcher's own
# selection rules (patch_names) stay out of the comparison.
reference_run() { # name out_dir
    local name=$1 out=$2
    if [[ -n $name ]]; then
        python3 "$root/tools/migration/reference_patches.py" "$game" "$py" "$out" "$name" \
            >"$out/reference.log" 2>&1
    else
        python3 "$root/tools/migration/reference_patches.py" "$game" "$py" "$out" \
            >"$out/reference.log" 2>&1
    fi
}

pass=0
fail=0
failed_patches=()

check() { # name
    local name=$1
    local out=$work/run-$(echo "$name" | tr -c 'A-Za-z0-9' '_')
    mkdir -p "$out"
    reference_run "$name" "$out"
    if [[ ! -f $out/patches.bin ]]; then
        echo "FAIL  $name: the reference produced no patches.bin"
        sed -n '1,3p' "$out/launch.err"
        fail=$((fail + 1)); failed_patches+=("$name"); return
    fi
    printf '%s\n' "$name" > "$out/selection.txt"
    if "$binary" "$game/eboot.bin" "$input" "$out/patches.bin" "" "$out/selection.txt" \
        >"$out/native.log" 2>&1; then
        printf 'ok    %s (%s bytes)\n' "$name" "$(wc -c <"$out/patches.bin" | tr -d ' ')"
        pass=$((pass + 1))
    else
        printf 'FAIL  %s\n' "$name"
        sed -n '1,8p' "$out/native.log" | sed 's/^/        /'
        fail=$((fail + 1)); failed_patches+=("$name")
    fi
}

echo "== $title $version: per-patch parity =="
while IFS= read -r line; do
    [[ $line == P\ * ]] || continue
    check "${line#P }"
done < "$input"

echo "== $title $version: the whole catalogue at once =="
out=$work/run-all
mkdir -p "$out"
reference_run "" "$out"
if [[ ! -f $out/patches.bin ]]; then
    echo "FAIL  all: the reference produced no patches.bin"
    sed -n '1,5p' "$out/reference.log" | sed 's/^/        /'
    fail=$((fail + 1))
    grep -v '^$' "$input" | sed -n 's/^P //p' > "$out/selection.txt"
    printf '\n== %s %s: %s passed, %s failed ==\n' "$title" "$version" "$pass" "$fail"
    exit 1
fi
grep -v '^$' "$input" | sed -n 's/^P //p' > "$out/selection.txt"
if "$binary" "$game/eboot.bin" "$input" "$out/patches.bin" "" "$out/selection.txt" \
    >"$out/native.log" 2>&1; then
    printf 'ok    all %s patches (%s bytes)\n' "$(wc -l <"$out/selection.txt" | tr -d ' ')" \
        "$(wc -c <"$out/patches.bin" | tr -d ' ')"
    pass=$((pass + 1))
else
    echo "FAIL  all"
    sed -n '1,20p' "$out/native.log" | sed 's/^/        /'
    fail=$((fail + 1))
fi

echo
echo "== $title $version: $pass passed, $fail failed =="
if [[ $fail -gt 0 ]]; then
    if [[ ${#failed_patches[@]} -gt 0 ]]; then
        printf '   %s\n' "${failed_patches[@]}"
    fi
    exit 1
fi
