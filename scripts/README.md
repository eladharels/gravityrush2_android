# scripts/

The Python reference implementation. Nothing here runs on the device.

Two consumers:

- **Desktop** (`run.sh`, `run.py`): the native Linux/Windows build still drives the game through
  these.
- **Parity gate** (`tools/migration/run_prepare_golden.sh`, `run_link_golden.sh`): the launcher's
  `libgow3prepare` and `libgow3link` are ports of `patches.py`, `content_profile.py`,
  `launch_env.py`, `prepare.py` and `link_modules.py`, and those tests compare the two
  implementations **byte for byte**. That comparison is the only evidence the ports are faithful.

So do not delete these as "the PC-side scripts the Android migration replaced". The Android
migration replaced their *use*, not their role: it made the device independent of them, and the
golden tests are what keep the C++ honest against them.

| File | Also ported to C++ for Bionic | Role now |
| --- | --- | --- |
| `prepare.py` | `gow3_link.cpp` (`PrepareBoot`) | reference for the boot image |
| `link_modules.py` | `gow3_link.cpp` (`LinkBoot`) | reference for the boot image |
| `link_libc.py` | — | superseded by `link_modules.py` (its own docstring says so); `boot-libc.bin` is read by nothing |
| `patches.py` | `gow3_prepare.cpp` | reference for the G3PATCH2 blob |
| `content_profile.py` | `gow3_prepare.cpp` (`ContentProfile`) | reference for `content.bin` |
| `launch_env.py` | — (Kotlin writes `launch.env`) | desktop |
| `game_identity.py`, `game_update.py`, `graphics_options.py`, `mods.py`, `save_backup.py`, `verify_game_patches.py` | — | desktop |

Removed 2026-10-17: `texture_diagnostics.py` — it only fed the retired glibc `run-gow3.sh`'s
`texture_integrity.json` log line. Under Bionic the same facts live in `patch_status.json`
(written by the device) and the game log. |

Regenerating the parity evidence needs a game directory:

```sh
bash tools/migration/run_prepare_golden.sh <game-dir>   # patches.bin, content.bin
bash tools/migration/run_link_golden.sh <game-dir>      # boot.bin, boot-linked.bin
```
