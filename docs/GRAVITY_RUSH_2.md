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

## The gr2_pc merge (2026-10-27, commit 6ced6bf)

gr2_pc's first commit (17b008a) imports gow3_pc unchanged under `port/`; its `port/src` is
byte-identical to our `src/` at the common ancestor 998ebc5. So the merge is a three-way merge
per file of gr2_pc's `port/src` (HEAD) into `src/`, with that import as the base. To redo it after
upstream changes:

```bash
git clone https://github.com/HayatoG/gr2_pc <scratch>/gr2_pc   # core.longpaths=true on Windows
git fetch <scratch>/gr2_pc HEAD:refs/gr2/head && git update-ref refs/gr2/base 17b008a91eb10b854a8c6feb118666fae297a238
# per file: git merge-file --diff3 ours base theirs, theirs renamed first (below)
```

gr2_pc renamed `GOW3_*`, `gow3_*`, `Gow3*`, `gow3gpu*` to `TLG_*`, `tlg_*`, `Tlg*`, `tlggpu*`
(and `tlg.ini`, thread names `tlg:`). Its side was mapped back to our names before merging
(keeping `runtime_tlg_*`), which left 40 conflict hunks instead of 65. So all options stay
`GOW3_*` here; gr2_pc's `GR2_*` aliases (copied to `TLG_*` at start) are not ported.

Taken from gr2_pc: `runtime_gr2.c`, `runtime_gr2_audio3d.c`, `runtime_gr2_font.c`,
`runtime_gr2_json.c`, `runtime_gr2_np.c`, `runtime_ngs2.c` (its own mixer), `runtime_ngs2_trace.c`,
`runtime_tlg.c` (`sceLibcHeapGetTraceInfo` and other libc/kernel extras), `runtime_freeze.c`,
`import_names.inc`, the RTC, rwlock, semaphore, memory-map and file-read changes, the mutex owner
tracking, the per-thread wait slots and `GOW3_WAIT_STATS`, `msg_log` for message dialogs.

Kept ours, and why:

- **ISA rewrite** (`isa_rewrite.c`, `isa_emulate.c`, `cpu_check.c` are in `src/` but not built
  on Android, and `probe.c` does not call them): they patch the eboot's SSE4A `EXTRQ` for x86 CPUs
  without SSE4A. FEXCore decodes SSE4A itself.
- **Fibers:** gr2_pc's `sceFiber*` (ucontext/Windows fibers, the entry called as host code) is
  removed; Bionic has no `makecontext`, and `runtime_fiber_jit.c` resolves first anyway.
- **probe.c:** ours unchanged (its JIT boundary). Not ported: the ISA/red-zone rewrite, the
  `GOW3_IMPORT_TRACE` x86 stubs, `GR2_*` aliases, the freeze monitor start, NGS2 trace binding.
- **runtime_pad.c:** ours (Android input bridge, Uncharted mouse camera). gr2_pc's PC mouse
  scheme and **simulated motion sensor** (tilt/shake keys for gravity sliding) are not merged;
  gr2_pc adds no pad imports. Port the motion part when gameplay is reached.
- **Save dialog** (`runtime_services.c`): ours (the Android save bridge); gr2_pc's needs its
  renderer's new save-list UI (`tlggpu_save_list_*`, `_message_*`, `_progress`, `_toast`).
- **TSC:** ours (the guest CPU's counter), not gr2_pc's host `rdtsc`.
- **Mutex lock:** ours (spin, statistics, caller sampling), with gr2_pc's owner tracking added.
  Note that cond waits now return EPERM when the caller does not hold the mutex (gr2_pc,
  inFAMOUS #586); a game that locks on one thread and waits on another would now get EPERM.

Changed for the JIT: NGS2 voice callbacks and user FX, the font memory destroy callback and the
NP toolkit allocator are guest code and are called through `guest_cpu_call`; the JSON allocator's
base vtable holds `guest_cpu_host_function` stubs; MXCSR save/restore at thread start and in the
NGS2 mixer are x86-64 only (`#if defined(__x86_64__)`); the freeze monitor is compiled out
(`GOW3_FREEZE_MONITOR`, needs gr2_pc's renderer frame counters and x86 registers).

Launcher (`Gow3Prepare.kt`, `gow3_prepare_jni.cpp`, `gow3_link.cpp`): links `libc.prx`,
`libSceFios2.prx`, `libSceJobManager.prx` and `sysdata/libSceSecure.prx` (a name with a `/` is
relative to the game folder), the order of gr2_pc's `gr2_run.py`. `libSceNpToolkit.prx` is not
linked, as on PC: `runtime_gr2_np.c` serves it. The linker took two gr2_pc `link_modules.py`
changes: `mov reg, fs:[0]` for any register (not only rax), and binding a newer library version
by NID and library name. gr2_pc also links the console's `system_lib/libSceNgs2.sprx` when
present; not done here (its own mixer is used).

Build fix: `runtime_gr2_font.c` renders glyphs with FreeType, so `build_bionic_deps.sh` builds
FreeType 2.13.3 (static, no zlib/png/bzip2/brotli/harfbuzz) and `gow3-probe` links it.

## Second boot (2026-10-27, build e7fb093): the game's entry point runs

1. All four linked modules initialise (libc 0, Fios2 1, JobManager 2, libSceSecure 3; 266
   native bindings instead of 242), and the game is entered at guest offset 0xa0.
2. The game starts: `_init_env`, AppContent, nine system modules (host), 1.4 GB + 3.1 GB of
   direct memory, three 1920x1080 display buffers, the threads `BGFiberWorkerLow/High/Sys/Font`,
   FIOS2 and its `SceFios*` threads, PlayGo (2 chunks), Audio3d (port opened on the Android PCM
   bridge) and the thread `sndx_audio3d_out_thread`.
3. Then the process ends:

```
Guest fault (signal 4) at guest offset 0x264eb0, address 0x800368eb0
```

It was not the JIT. Build 7cfccfe prints more at a fault (the thread, the bytes at the RIP, the
image return addresses on the guest stack), and with `FEX_MAXINST=1` (one guest instruction per
JIT block; `gr2-env.txt` now takes `FEX_*` options) the main thread stopped at the same offset,
with the guest stack topped by +0x25bb5e. That return address follows a call of the import stub
at 0x13f7b40 (`jmp [0x1705ef8]`), the import `mPYgU4oYpuY#libSceNgs2` =
`sceNgs2SystemCreateWithAllocator`. gr2_pc's `runtime_ngs2.c` called the game's allocator
handler (guest code at 0x264eb0) directly: on arm64 the host CPU ran the x86 bytes and took
SIGILL. The signal reached probe.c while the thread was in a host call, so the "guest offset"
printed was the host PC. Fixed in the next build: `guest_alloc`/`guest_free` call the handlers
through `guest_cpu_call`, and a fault with the host PC inside the image now says "Host jumped
into guest code ... without guest_cpu_call".

**Rule for later merges from gr2_pc (or any PC port):** every call of a function pointer the
guest handed in (callbacks, allocators, vtables) must go through `guest_cpu_call`, and every host
function handed to the guest as a pointer through `guest_cpu_host_function`. Check new files for
`typedef ... (*Name)(...)` and calls through struct members.

## Third boot (2026-10-27, build f0432b0): engine start, then the detiler on the stock driver

With the NGS2 fix the game runs ~60 s of start-up: the NGS2 built-in mixer (grain 256, 48 kHz),
the `sndx_*` sound threads and `SoundTick`, `DrawThread`, 7 `MTTW` and 6 `JoBwOrKeR` threads, 5
`Havok Thread`s, `MSM::StepThr`, `RTC Polling Thread.`, NpToolkit offline (signed out), the
first fiber (`SandboxBGJobWorker`, JIT contexts), sceFont glyph rendering (FreeType), ~200
semaphores, and its first shaders. Then the renderer stops:

```
GPU [Debug] <Critical> tile_manager.cpp:189 GetTilingPipeline: Assertion Failed!
Detiler pipeline creation failed ErrorUnknown
STOP: GPU library assertion failed (see GPU log above)
```

This is shadPS4's detiler compute pipeline failing to build, on the **stock Qualcomm driver**
("GPU driver: system (stock)"): the app has no Turnip yet. The kernel GPU lines of the bug report
show no CP faults. Next: install the Standard Turnip build in this app (or make the launcher
use one by default, blocker 4 below) and run again; only if Turnip fails too is the detiler
shader itself the problem.

## Fourth boot (2026-10-27, build f0432b0 on Turnip): first frames, then AvPlayer

With Turnip selected (the app had `v27.0.0-20261027-r4-8G2-OneUI` installed; HOME → GPU
DRIVER) the detiler builds and the game renders: 11-29 draws per frame (logos), the first
frame 466 ms, then ~67 ms. So the detiler stop was the stock driver. Then:

```
Host jumped into guest code (signal 4) at guest offset 0x111af60: a host function called a guest function pointer without guest_cpu_call
  thread 'BGFiberWorkerSy', ... guest stack, image offsets: +0x111a384 ...
```

+0x111a384 returns from the stub of `KMcEa+rHsIo` = `sceAvPlayerAddSource`: shadPS4's AvPlayer
(`gpu/shadps4/core/libraries/avplayer`) forwarded to the game's replacement allocator directly.
Build 7ef1b1b sends the game's allocators, file callbacks and event callback through
`GuestCall` (`avplayer_common.h`, over `guest_cpu_call`). Not run yet. Other shadPS4 HLE
libraries in `gpu/` may have the same direct calls; the new fault line names them.

## Fifth and sixth boots (2026-10-27, builds 7ef1b1b and 99c34d7 on Turnip): ~280 draws, 4.5 FPS

7ef1b1b: the logo videos play (AvPlayer), save data `system0020` is created and mounted, then
libc++ terminated: `HLE thread: join failed (thread::detach failed: Invalid argument)`. An
AvPlayer is stopped from a game thread while its controller thread stops itself; Bionic's
`pthread_detach` returns EINVAL there, and the fallback detached again outside the `try`.
99c34d7 (`gpu/shim/core/libraries/kernel/threads.h`) releases the thread without throwing.

99c34d7 runs without a crash for 6 minutes: about 280 draws and 37 dispatches per frame
(title screen or menu; autorun's Cross presses did not reach more), **4.5 FPS**. GPU-bound: each
frame waits 100-145 ms on GPU ticks, stage A/B ~210 ms, while the process uses 0.8 cores and the
GPU thread ~8 ms per frame. Next: find the expensive draws (shader dumps, `GOW3_DUMP_SHADERS=1`;
a geometry-shader or LDS path is the first suspect: gr2_pc's renderer fixes from inFAMOUS cover
geometry-shader outlines and LDS barriers), then the renderer-side merge of gr2_pc's `gpu/`.
No screenshot of that screen yet.

## The opening scene: 4.6 -> 16 FPS (2026-10-28)

Build 99c34d7 reaches the game's opening scene (the mining ship's descent, credits and
subtitles), rendered correctly on Turnip, at 4.6 FPS. Measured, one change per run, 5 minutes
each with Cross every 20 s (screenshots and the 5-second statistics):

| Run | Change | FPS |
|---|---|---|
| 99c34d7 | (defaults) | 4.6 |
| 5346132 | output 854x480 (`GOW3_OUTPUT_SIZE`) | 4.6 (the scene stayed 1920x1080) |
| 5346132 | `GOW3_READBACKS=0` | 4.6 |
| a0c376a | `GOW3_SCENE_MRT=3` (scene at 1280x720) | 4.7 |
| a0c376a | `GOW3_SCENE_MRT=3`, 854x480 | 4.8 |
| a0c376a | `GOW3_READBACK_LINEAR=0`, `GOW3_SCENE_MRT=3` | **16** |

- The GPU profile (`GOW3_GPU_PROFILE=1`; fixed in 9dd22cc/c900d1e: it now drops frames whose
  Turnip timestamps go backwards and waits 32 frames, not 4) showed ~76 submissions a second,
  each ~8 ms of GPU work and ~5 ms of GPU idle, with an `image download R16G16B16A16Sfloat
  1920x1080` (16 MB) in nearly every one. `GOW3_READBACK_LINEAR=1` (uncharted_android's
  default: without it Uncharted's language screen showed corrupted bands) read every linear
  render target back to guest memory, which serialised the CPU and the GPU at each submission.
  Gravity Rush 2's picture is unchanged without it.
- The reduced-resolution scene started only at a pass with 5+ colour targets (God of War III's
  and Uncharted's G-buffers); Gravity Rush 2's has 3. `GOW3_SCENE_MRT` (default 5) sets it.
- Both are Gravity Rush 2's defaults now (`GravityRush2.kt`). After them the GPU is ~99% busy:
  the next gains are GPU work (the G-buffer pass `ps fbaef68b`, the HDR passes) and the render
  size.

## Gameplay reached (2026-10-28, build 7f63899)

With the defaults alone (empty `gr2-env.txt`) the game continues from the save that the
earlier runs created (`data0000`) into gameplay in the mine: the app's touch controls, the
waypoint marker, ~2,000-3,600 draws per frame. **7-17 FPS, mean 11.4** over 4 minutes, GPU 99%
busy (the overlay), CPU 14%. At 854x480 (`GOW3_OUTPUT_SIZE`, the scene really at 854x480) the
mean was 10.7: the render size does not set the frame time. The GPU cost is per draw or per
vertex (the shadPS4-translated shaders), and the phone heats (FPS fell from 17 to 7-11 within
two minutes in both runs). Next: GPU profile of a gameplay frame (`GOW3_GPU_PROFILE=1`) to rank
its passes and shaders, then gr2_pc's renderer fixes.

Note for test scripts: `printf '' | adb shell "cat > gr2-env.txt"` does not empty the file
(adb sends no input); use `adb shell ": > .../gr2-env.txt"`.

## Where the GPU time goes in gameplay (2026-10-28, build 95c92f6)

- `TU_DEBUG=nocb` (Turnip's tiled GMEM rendering allowed again): mean 11.8 FPS against 11.4 with
  `nocb,sysmem`, no GPU faults in 5 minutes. Not the bottleneck; the default stays.
- GPU profile (`GOW3_GPU_PROFILE=1`; 95c92f6 keeps 256 slices of 1024 queries, since gameplay
  starts ~300 profiler frames a second): the GPU is busy ~1 s per second, ~105 ms per game frame
  at ~9.5 FPS (~3,600 draws). Per game frame:
  - shadow maps ~22 ms: `pass 4096x4096 depth D16` and `pass 2048x2048 depth D16`
    (vs `5d0febd2`, no pixel shader). Fixed size, so the render size does not change them;
  - compute at native 1080p ~20-25 ms: dispatches of 240x135 and 120x68 groups (cs `7e22ac2f`,
    `694a3f45`, `12bb8982`, `e172c821`, `c12dbba7`, `3615273f`, ...), the post-processing.
    Scene proxies only shrink render targets, not compute storage images;
  - scene-proxy resolves and fills (720p proxy <-> native 1080p) ~8 ms;
  - the 720p scene passes ~10-15 ms, then a long tail (75 labels). ~110 small passes per frame
    with vs `eb0345fe` (one per draw: something ends the render pass at every draw).
- The renderer's own threads are near their limit too (stage B busy 85-90%), behind the GPU.

Leads, in order of expected gain: the shadow maps (half-size depth targets, or a gr2_pc-style
game patch), the native-size compute post-processing (scale the dispatches with the scene, or
let the proxies cover storage images), the per-draw pass breaks, then gr2_pc's renderer fixes
(early fragment tests BP12, wave64 lowering at the real subgroup size).

## Turnip build: Standard, not 8G2-OneUI (2026-10-29)

The app had `v27.0.0-20261027-r4-8G2-OneUI` selected: the build with a flag for the Adreno 740,
not this phone's Adreno 750. Downloaded in the app (Settings -> DRIVER -> DOWNLOAD DRIVERS ->
Banners Turnip -> `Turnip-v27.0.0-20261029-r2.zip`, the plain Android build) and selected on
HOME -> GPU DRIVER. Same save, same 5-minute run, defaults only:

| Driver | Mean FPS in gameplay | Range | Draws/frame |
|---|---|---|---|
| 8G2-OneUI 20261027-r4 | 11.4 | 7-17 | ~2,960 |
| Standard 20261029-r2 | **17.6** | 13-22 | ~2,830 |

No GPU faults, the picture is correct (outdoors in the ruins). Two things changed at once (the
variant and two days of Mesa), but either way the Standard build is the one to use on this
phone. **Install the Standard build** for Gravity Rush 2; the launcher does not pick a driver by
itself yet (blocker 4 below).

## Next blockers, in order

1. **Merge the Gravity Rush 2 runtime from HayatoG/gr2_pc.** (Done for `src/`, see above;
   the renderer side is still to do.) gr2_pc descends from the same
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
2. **Game libraries the launcher must link:** done (libc, Fios2, JobManager,
   `sysdata/libSceSecure.prx`; NpToolkit is served by the runtime, as on PC).
3. **Audio:** gr2_pc uses the console firmware's `libSceNgs2.sprx` (`system_lib/`, the player's
   own) or its own experimental mixer.
4. **Turnip on first start (needed: the stock driver fails the detiler):** install the Standard Turnip build in this app (Settings → DRIVER).
   Better, make the launcher pick an installed Turnip by default, since the stock driver is
   unsupported.
5. **autorun.sh:** a `STOP:` line counts as a crash, so it takes a 3-minute bug report for GPU
   lines that a runtime stop cannot have. Skip the bug report when the log has no `Device lost`.
