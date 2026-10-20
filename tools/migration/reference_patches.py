#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""compile_plan with an explicit patch list, for the parity test.

scripts/patches.py's CLI goes through the launcher's selection rules (patch_names() drops the
resolution patches when render_resolution is native, adds the texture fix, enforces the mutual
exclusions). Those rules are the launcher's, not the compiler's, and they would hide which side is
wrong. This harness calls compile_plan() directly with the names given, so the comparison is
compiler against compiler.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / 'scripts'))
import patches  # noqa: E402


def main(argv):
    if len(argv) < 3:
        print('usage: reference_patches.py <game-dir> <prepared-out> <out-dir> [names...]\n'
              '   (no names: every patch in the XML)', file=sys.stderr)
        return 2
    game, prepared, out = Path(argv[0]), Path(argv[1]), Path(argv[2])
    out.mkdir(parents=True, exist_ok=True)
    # compile_plan reads the prepared ELF out of `out`; the preparation steps ran elsewhere.
    (out / 'eboot.elf').write_bytes((prepared / 'eboot.elf').read_bytes())
    identity = patches.patch_profile_status(game)
    version = identity['app_version']
    profile = patches.game_profile(identity['title_id'], app_version=version)
    names = argv[3:]
    if not names:
        names = sorted(patches.available_patches(profile[1], profile[2]))
    report, writes = patches.compile_plan(game, out, names, requested=names, env={})
    patches.publish_plan(out, report, writes)
    print('%s %s: %s; %d writes' % (identity['title_id'], version, report['reason'], len(writes)))
    for name, why in report['skipped'].items():
        print('  skipped %s: %s' % (name, why))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
