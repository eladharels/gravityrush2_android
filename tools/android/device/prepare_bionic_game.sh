#!/usr/bin/env bash
# Derive the Bionic runtime inputs for one installed game, then deploy them next to the runtime.
#
# The patch chain is the glibc behaviour baseline: prepare.py -> link_libc.py -> link_modules.py ->
# content_profile.py -> launch_env.py, i.e. patches.compile_plan()/publish_plan(). Those steps
# produce boot-linked.bin, content.bin, patches.bin (one G3PATCH2 blob) and launch.env. The build
# packages only the runtime plus the patch rule XML; the player's game derivatives are produced
# here, before the run, and deployed separately - they never enter the APK.
#
# usage: prepare_bionic_game.sh <game-dir> [--device <serial>] [--out <dir>]
#   <game-dir>  the player's installed game (needs eboot.bin, sce_sys, sce_module)
#   --device    adb target; omit to only derive into --out (default out/bionic-game/<title>)
#   --out       override the derivation directory
#
# Run it again whenever the game, its version, or the patch selection changes: run-bionic.sh
# verifies the game identity against launch.env and refuses to start on a mismatch.
set -euo pipefail
cd -- "$(dirname -- "$0")/../../.."

game=
device=
out=
while [[ $# -gt 0 ]]; do
    case $1 in
        --device) device=$2; shift 2 ;;
        --out) out=$2; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) [[ -z $game ]] || { echo "prepare_bionic_game: unexpected argument $1" >&2; exit 2; }
           game=$1; shift ;;
    esac
done
[[ -n $game ]] || { echo "usage: prepare_bionic_game.sh <game-dir> [--device serial] [--out dir]" >&2; exit 2; }
game=$(cd -- "$game" && pwd)
[[ -f $game/eboot.bin ]] || { echo "prepare_bionic_game: $game/eboot.bin is missing" >&2; exit 2; }

if [[ -z $out ]]; then
    title=$(python3 - "$game" <<'PY'
import sys
sys.path.insert(0, 'scripts')
from prepare import load_sfo
import pathlib
print(load_sfo(pathlib.Path(sys.argv[1])/'sce_sys'/'param.sfo')['TITLE_ID'])
PY
    )
    out="out/bionic-game/$title"
fi
mkdir -p "$out"

# The five preparation steps, in the same order and with the same arguments run-gow3.sh uses on
# the device. --base-dmem 7104 matches run-gow3.sh.
python3 scripts/prepare.py "$game" --out "$out"
python3 scripts/link_libc.py "$game" --out "$out"
python3 scripts/link_modules.py "$game" --out "$out"
python3 scripts/content_profile.py "$game" --out "$out" --sku "${GOW3_CONTENT_SKU:-full}"

# The settings the app uses live in the device config; derive with a copy of it so the host run and
# the run on the device agree on graphics options and patch selection.
config="$out/gow3.ini"
[[ -f $config ]] || cp tools/android/config/gow3-default.ini "$config"
python3 scripts/launch_env.py --game "$game" --config "$config" \
    --state "$out/graphics_state.json" --out "$out" --base-dmem 7104 --shell > "$out/launch.env"

for f in boot-linked.bin content.bin patches.bin patch_status.json launch.env; do
    [[ -s $out/$f ]] || { echo "prepare_bionic_game: $out/$f is missing or empty" >&2; exit 1; }
done

if [[ -z $device ]]; then
    echo "Bionic game inputs: $out (not deployed; pass --device to install)"
    exit 0
fi

adb -s "$device" shell "run-as com.gow3.android.bionic mkdir -p files/gow3port/data"
for f in boot-linked.bin content.bin patches.bin patch_status.json launch.env; do
    adb -s "$device" push "$out/$f" "/data/local/tmp/gow3-$f" >/dev/null
    adb -s "$device" shell "run-as com.gow3.android.bionic sh -c 'cat /data/local/tmp/gow3-$f > files/gow3port/data/$f'"
done
adb -s "$device" shell "run-as com.gow3.android.bionic ls -la files/gow3port/data"
echo "Bionic game inputs deployed to $device: $(python3 -c "
import json;r=json.load(open('$out/patch_status.json'))
print(r['identity']['title_id'], r['identity']['app_version'], r['reason'], '%d writes' % r['write_count'])")"
