# Gravity Rush 2 on Android: status and next steps

This repository is `eladharels/uncharted_android` (the Uncharted: The Nathan Drake Collection
Android port: x86-64 game code under FEXCore's JIT, shadPS4-based Vulkan renderer, Kotlin
launcher) turned into a Gravity Rush 2 app. It was started on 2026-10-27 from uncharted_android
`main` at d77e6c1. Read `ANDROID_PORT.md` and `HANDOFF.md` for how the port works; they still
describe Uncharted.

**No game files or Sony art belong in this repository.** The player's own dump stays on the phone.

## The app

- Application id `com.gravityrush2.android` (installs next to `com.uncharted.android`), name
  "Gravity Rush 2", launcher activity `com.gow3.android.MainActivity`.
- `GravityRush2.kt`: the game folder is recognised by `eboot.bin` + `Gravite2_Chunk0.psarc`. The
  game starts with `GOW3_FRAME_STATS=1 GOW3_BIG_CORES=1 GOW3_DRAW_PIPE=1`. Naughty Dog's job-spin
  patch and the `GOW3_UC3_RES` resolution patch are not used: both only run when their env var is
  set and they find their byte pattern.
- `run-bionic.sh`: `TU_DEBUG=nocb,sysmem` by default (the Turnip setting that stopped Uncharted's
  command-processor faults), native 1920x1080 output scaled to the screen. `gr2-env.txt` in the game
  folder overrides options, one `NAME=value` per line (`GOW3_*`, `TU_*`, `FD_*`, `IR3_*`, `MESA_*`).
- Game folder: the system folder picker showed an empty storage root on the Galaxy S24 Ultra, for
  adb and for the owner alike (the phone has a work profile). With no folder set, the launcher
  finds the game under `<storage>/games/<any>/` or `<any>/Game/` on its own. An adb start can
  name the folder:
  `am start -n com.gravityrush2.android/com.gow3.android.MainActivity --es game_dir <path>`.
  Storage permission over adb:
  `pm grant com.gravityrush2.android android.permission.READ_EXTERNAL_STORAGE` (and `WRITE_`).
- Logs: `/sdcard/Android/data/com.gravityrush2.android/files/logs/`, logcat tag
  `GravityRush2Game`. `tools/android/autorun.sh [--apk FILE] [--seconds N] [--env '...']` installs,
  starts, measures and pulls the logs.
- CI: the same workflow as Uncharted. Artifacts are named `gravityrush2-android-*`. The repo is
  public, because GitHub Actions does not start on this account's private repos (billing). Its
  signing key is new and made for this app: `G:\Games\gravityrush2_signing\` (outside the repo,
  never commit it). Its values are the repo secrets `ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.

## The game files (on the phone)

`/storage/emulated/0/games/Gravity.Rush.2-P2P/Game`: CUSA03694 with update 01.11, laid out the
way HayatoG/gr2_pc's PC package expects it:

- `eboot.bin` (28 MB, sha256 `151e6d48…6299`): a fake-signed SELF, read by `scripts/prepare.py`'s
  `parse_self` without changes. It is an ET_SCE_DYNEXEC with entry 0xa0, code 0x165c518 bytes and
  data up to 0x1b7c5e0.
- `sce_module/`: `libc.prx`, `libSceFios2.prx`, `libSceJobManager.prx`, `libSceNpToolkit.prx`.
  `sysdata/libSceSecure.prx`.
- `Gravite2_Chunk0/1.psarc` and `Gravite2_Patch_Chunk0/1.psarc`: the content. `args.txt`:
  `-region A -disable_sce_dbg -enter_world LaunchScript root -contentid UP9000-CUSA03694_00-GRAVITYRUSH20000`.
- The executable's 55 needed modules include, besides Uncharted's set: libSceJobManager,
  libSceSecure, libSceAudio3d, libSceNgs2, libSceNpToolkit, libSceJson2, libSceFont/FontFt,
  libScePlayGo(Dialog), libSceNpWebApi/Auth/Trophy/Commerce/Matching2/Tus/Utility/ScoreRanking/Sns,
  libSceHttp, libSceSsl, libSceShareUtility, libSceContentExport/Search/Delete, libSceJpegEnc/Dec,
  libScePngDec and libSceScreenShot.

## First boot (2026-10-27, build c9040e7, Galaxy S24 Ultra)

The run took 5 seconds:

1. The launcher found the game folder by itself, prepared the image ("no-patches") and started
   the game process.
2. The renderer came up and presented to the app. Note that it ran on the **stock Qualcomm
   driver** ("GPU driver: system (stock)"): the new app has no Turnip driver yet.
3. The image was mapped: 9 segments, 84,410 relocations, 242 native bindings, 327 HLE symbols.
4. It stopped in the dump's own `libc.prx`, linked module 0, while it initialises, before the
   game's entry point:

```
STOP: first unsupported PS4 import: NWtTN10cJzE#libSceLibcInternalExt (index 878)
Caller in linked module 0 (libc.prx): +0x25513
Native libc initialization is incomplete; game entry has not run.
```

`NWtTN10cJzE` is `sceLibcHeapGetTraceInfo`, according to gr2_pc's `port/src/import_names.inc`.
Uncharted's libc never called it. The runtime stops at the first unsupported import, so more
missing ones follow.

## Next blockers, in order

1. **Merge the Gravity Rush 2 runtime from HayatoG/gr2_pc.** gr2_pc descends from the same
   gow3_pc family (by way of The Last Guardian's `tlg_pc`; its options are `TLG_*`/`GR2_*`) and
   plays the game to gameplay on PC at 30 FPS. Its `port/src/` has the same layout as our `src/`,
   plus the pieces this game needs:
   - `runtime_gr2.c`, `runtime_gr2_audio3d.c`, `runtime_gr2_font.c`, `runtime_gr2_json.c`,
     `runtime_gr2_np.c`, `runtime_ngs2.c`, `runtime_tlg.c` and `runtime_freeze.c`;
   - a fuller `import_names.inc`;
   - `isa_rewrite.c` and `isa_emulate.c`, which rewrite the eboot's 96 SSE4A `EXTRQ` instructions
     for CPUs without SSE4A. Under FEXCore, check whether the JIT decodes SSE4A, or keep the
     rewrite.

   Do it as uncharted_android merged ucndc_pc: a real merge from the common gow3_pc ancestor
   where possible, keeping our JIT host-call boundary (`guest_cpu.h`), fibers (`runtime_fiber_jit.c`),
   Bionic and Android pieces. Then the renderer side: gr2_pc's shadPS4 fixes from inFAMOUS (float
   atomics, `S_WQM_B64`, LDS barriers, the geometry-shader outline that draws black lines).
2. **Game libraries the launcher must link:** `libSceJobManager.prx` (and `libSceNpToolkit.prx`,
   `libSceSecure.prx` from `sysdata/`), as gr2_pc's launcher does. Only libc and Fios2 are
   linked today.
3. **Audio:** gr2_pc uses the console firmware's `libSceNgs2.sprx` (`system_lib/`, the player's
   own) or its own experimental mixer.
4. **Turnip on first start:** install the Standard Turnip build in this app (Settings → DRIVER).
   Better, make the launcher pick an installed Turnip by default, since the stock driver is
   unsupported.
5. **autorun.sh:** a `STOP:` line counts as a crash, so it takes a 3-minute bug report for GPU
   lines that a runtime stop cannot have. Skip the bug report when the log has no `Device lost`.
