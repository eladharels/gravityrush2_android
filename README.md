# Uncharted: The Nathan Drake Collection for PC (native port)

**English** | [Português (Brasil)](README.pt-BR.md)

A native Windows 10 and 11 port of the games in **Uncharted: The Nathan Drake Collection** (PS4,
Bluepoint, 2015). The focus is **Uncharted 3: Drake's Deception**, which runs at 60 FPS in 1080p with no upscaling or frame generation;
Uncharted 2 is playable and Uncharted 1 runs (experimental).

> **No game files are included.** You need a dump from your own PS4
> (tested: **CUSA02344**, version 01.00 with the **01.02** update).
> This project is not affiliated with Sony Interactive Entertainment, Naughty Dog or Bluepoint
> Games. Uncharted is a trademark of Sony Interactive Entertainment.

## Download and play

No install and no terminal needed:

1. Download the latest `ucndc_pc-v…-windows.zip` from [Releases](https://github.com/zorde23/ucndc_pc/releases) and extract it anywhere
   (for example `C:\Games\ucndc_pc`). Don't run it from inside the zip.
2. Open **`Uncharted Launcher.exe`**. If Windows SmartScreen warns about it, click
   *More info* → *Run anyway* (the launcher is not signed).
3. Pick the folder of your game dump (`CUSA02344`). The update folder (01.02) is found
   automatically when it sits next to it.
4. Click **Play** on the game you want. The launcher can also create desktop shortcuts.

The **Options** button sets the display mode (window, borderless, fullscreen), VSync, the FPS
limit and the FPS counter. Under *Advanced* you can turn off any of the optimizations; with all of
them on you get the tested setup. In game, **Insert** (or L2+R2+L3+R3 on a controller) opens the port's
menu. Saves are kept in the `user` folder next to the launcher, and the last session's log in
`user\last_run.log`.

You need 64-bit Windows 10 (1903+) or 11 and a GPU with Vulkan 1.3. The port was developed on an
AMD RX 6750 XT; Intel GPUs get a fix from Supermedo but are still untested.

## How it works

The PS4 uses an x86-64 processor, the same architecture as a PC, so the game's original executable
runs **directly on the CPU**, with no emulation: a runtime written for the port replaces the PS4
system libraries (memory, threads, files, audio, video, controller, saves). The graphics are
translated: the PS4 GPU (AMD GCN) commands and shaders become Vulkan through the
[shadPS4](https://github.com/shadps4-emu/shadPS4) video core.

This repository is a fork of the [God of War III port](https://github.com/FelipePrado0/gow3_pc),
which comes from Supermedo's [Bloodborne Windows port](https://github.com/Supermedo/bloodborne_pc)
and deadinside28's original [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc). What the player
sees is already named after this port (`ucndc.exe`, `ucndc.ini`, `UC_*` options); inside the code
many names still start with `gow3` / `GOW3_`, and the old names still work.

## Why this project

The original idea was to port only **Uncharted 3**, the one game in the trilogy I had never played and also the one
shadPS4 has the most trouble running.
Since the three games in the collection share the same engine and the same dump, Uncharted 2 and
Uncharted 1 came later almost for free, and the goal is now the whole trilogy.

## Status

Updated 2026-10-17.

| Game | Executable | Status |
|---|---|---|
| Uncharted 1 | `eboot.bin` | Runs at **60 FPS** with sound (gunshots included); lightly tested past the start |
| Uncharted 2 | `big2-ps4_Shipping.elf` | Playable, 51–60 FPS, with its own saves |
| Uncharted 3 | `big3-ps4_Shipping.elf` | Playable at **60 FPS in 1080p, with no upscaling or frame generation** (the Cartagena streets and the museum); around 50 FPS in heavy vegetation (the Chateau). A few known issues, listed below. Being played start to end on the port |

Fixed in UC3 over the last few days: young Drake's white hair (addr64 `BUFFER_LOAD`), swapped
textures (bartender's vest, museum floor, bathroom door) and performance, which went from 18–24 FPS
in the streets and the Chateau to 60.

Also new: the player package with the launcher and its Options screen; the port's in-game menu no
longer shows God of War III-only items (cheats, orbs, upscaler, render resolution) in the Uncharted
games; and black screen on Intel GPUs fixed (from Supermedo's 0.3.3, untested here).

Known issues:
**Uncharted 3**
- **Rare crash after long sessions (GPU driver timeout).** After about 30 minutes of play, the port can send a very large block of memory (~1.5 GB) to the GPU in one go. The frame stalls and Windows/AMD reports a driver timeout, closing the game. It is rare and has not been reproduced on demand yet. Save often.
- **Some walls flash dark for an instant while turning the camera.** A layered (array) texture is rebuilt from scratch instead of reused, and it is blank for a frame while it is rebuilt. The same stretches also cause short dips in 1% low FPS (frames of 25-50 ms). Seen in The Citadel (chapter at 37%).
- **Brief texture flicker the first time you enter a new area.** New shaders are compiled in the background, and objects using them are skipped for a few frames. Shaders are cached, so it does not repeat on later visits.
- **Heavy vegetation areas (The Chateau) run around 50 FPS** instead of 60. The limit is the emulated GPU command processor on the CPU.
- The game is locked to 60 FPS. Higher frame rates speed up the game logic.
- Internal resolution scaling (FSR/DLSS) does not work for Uncharted: the inherited hooks are specific to God of War III.

**Uncharted 2**
- In the train prologue the carriage can fall too early, so you may need to jump sooner than on PS4. UC2 runs without `UC_COMPUTE_PIPE` for this reason. If the train still fails, turn off `UC_HLE_INORDER` and `UC_ACB_DISPATCH_PIPE` in the launcher (Options > Advanced) for that part; they keep the rooftop chase at 60 FPS.
- Lightly tested. Please report missing sounds or visual bugs.

**Uncharted 1**
- Lightly tested. Please report missing sounds or visual bugs.

The project log (in Portuguese), with what was done and why, is in
[docs/uc3/DEVLOG.md](docs/uc3/DEVLOG.md).

## How this differs from Supermedo's uncharted_pc

Supermedo's [uncharted_pc](https://github.com/Supermedo/uncharted_pc) is another native port of the
same collection, also GPL and from the same family (both descend from deadinside28's bloodborne_pc).
The two were made in parallel, and this one took several of its fixes, always credited (see
[Credits](#credits)). Compared with its version 0.3.1 (2026-10-15):

| | This port (ucndc_pc) | uncharted_pc (Supermedo) |
|---|---|---|
| Origin | Fork of the God of War III port (gow3_pc) | Direct fork of bloodborne_pc (bbport) |
| Video core | shadPS4 0.19.0 with fixes from the 2026-10-12 nightly (memory tracker, texture cache) | shadPS4 from 2026-09-24 with recompiler backports |
| GPU readbacks by the game | **Precise** mode (the scenery is drawn from the right data; this is what fixed the visual problems here), sped up to reach 60 FPS: async readbacks, side queue, early submits, ring copies on another thread | **Relaxed** mode by default, working around the problem with the scalar copy HLE |
| How optimizations land | In the code, each one behind an option that is **off by default**, measured and logged in the [DEVLOG](docs/uc3/DEVLOG.md); the launcher turns on the tested set, and *Options → Advanced* turns them off one by one | On by default, with options to turn them off |
| Other things this port has | Tight barriers on formatted buffers, full hash of small images, MP3 in Ajm, diagnostic tools (GPU profile, fault and write tracing) | — |
| What it has that this one does not yet | — | Upscalers and frame generation, 120 FPS option, mouse in UC3, collection menu through `eboot.bin` |

The two projects help each other: when something works there and not here (or the other way round),
comparing them is worth it.

## Building from source

Only needed to work on the port; to play, use the [download](#download-and-play).


1. Install MSYS2 (`winget install MSYS2.MSYS2`), open the **MSYS2 CLANG64** shell and install the
   dependencies:

   ```bash
   pacman -S --needed git patch \
     mingw-w64-clang-x86_64-{toolchain,cmake,ninja,pkgconf,python} \
     mingw-w64-clang-x86_64-{vulkan-headers,vulkan-loader,vulkan-memory-allocator,glslang} \
     mingw-w64-clang-x86_64-{sdl3,fmt,boost,robin-map,xxhash,ffmpeg,zydis} \
     mingw-w64-clang-x86_64-{spirv-tools,spirv-headers,spirv-cross}
   ```

2. Clone with submodules:

   ```bash
   git clone --recursive https://github.com/zorde23/ucndc_pc.git
   ```

3. Edit `windows\config.bat` with the paths to MSYS2, the game and the update.
4. Run `windows\compilar.bat` (or `bash build.sh` in the CLANG64 shell). The result is
   `out\ucndc.exe`.
5. Optional: `packaging\windows\empacotar.bat` builds the player zip (`dist\ucndc_pc-v<version>-windows.zip`)
   with the launcher. It needs Python for Windows installed (python.org or `winget install Python.Python.3.13`).

### Playing from source

Double-click the game's `.bat` in `windows\`:

| File | What it does |
|---|---|
| `config.bat` | Paths (MSYS2, game, update) and the recommended optimization list |
| `compilar.bat` | Builds the port |
| `jogar_uc3.bat` | Uncharted 3 with the optimizations (60 FPS) |
| `jogar_uc2.bat` | Uncharted 2 (without `UC_COMPUTE_PIPE`, because of the train) |
| `jogar_uc1.bat` | Uncharted 1 (experimental) |
| `fechar_jogo.bat` | Closes the game if it hangs |

Each session's log goes to `out\last-run.log` (the previous one to `out\previous-run.log`). Saves
are kept in `user\`. In game, **Insert** (or L2+R2+L3+R3 on a controller) opens the port's menu.

You can also call the script directly and pass options for that run only:

```powershell
powershell -ExecutionPolicy Bypass -File tools\run_uc3.ps1 -Set UC_READBACKS=1,UC_FRAME_STATS=1
```

### Options (toggles)

Every new optimization goes in **off by default** in the code; the launcher and the `.bat` files in
`windows\` turn on the tested set. Each one also works with the older `GOW3_` prefix
(`UC_LEAF_COPY` = `GOW3_LEAF_COPY`; if both are set, `UC_` wins). The main ones:

| Variable | Effect |
|---|---|
| `UC_READBACKS` | `2` (the `run_uc3.ps1` default): precise GPU readbacks, needed for the scenery to show; `1`: relaxed mode |
| `UC_READBACK_ASYNC`, `UC_READBACK_FAST*`, `UC_READBACK_SIDEQ`, `UC_DEFERRED_READBACK` | GPU readbacks without draining the whole queue |
| `UC_SCALAR_COPY_HLE`, `UC_HLE_INVALIDATE` | Replaces a copy compute shader with native code (and invalidates the caches it writes) |
| `UC_COMPUTE_PIPE`, `UC_LEAF_COPY` | Compute processing shortcuts |
| `UC_EAGER_SUBMIT*`, `UC_ASYNC_SUBMIT`, `UC_DEFERRED_SUBMIT` | Submit GPU work earlier / on another thread |
| `UC_RING_COPY_THREAD`, `UC_RING_DEDUP` | Ring buffer copies on a separate thread, without repeats |
| `UC_FRAME_STATS` | Frame statistics in the log |
| `UC_EXECUTABLE` | Which game to start (`big3-ps4_Shipping.elf`, `big2-ps4_Shipping.elf`, `eboot.bin`) |
| `UC_LOW_IMAGE` | Loads the executable below 2 GB (needed for Uncharted 1) |
| `UC_AJM_END_RULES` | Ajm (audio decoder) end-of-job rules, from SuperMedo (Uncharted 1) |
| `UC_EOP_TICK_WAIT` | The `m_gfxEopTick` assert waits for the GPU instead of stopping the game |
| `UC_SYNC_SMALL_TARGETS` | Shaders for small render targets compile right away, so a one-time lighting table drawn at boot is not skipped (fixes the dark cave torch in UC3) |
| `UC_JOB_SPIN`, `UC_JOB_SLEEP` | `UC_JOB_SPIN=100` + `UC_JOB_SLEEP=1`: the game's six job threads spin less and sleep during gameplay; CPU use drops from about 66% to about 50% at the same frame rate (UC3) |
| `UC_HLE_INORDER`, `UC_ACB_DISPATCH_PIPE` | Keep the UC2 rooftop chase at 60 FPS; turn them off if the train prologue fails |

The full list, with measurements, is in the [DEVLOG](docs/uc3/DEVLOG.md).

## Credits

- **deadinside28**: [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc), the original
  native port (the base of everything, also called bbport).
- **Supermedo** (Mohammed Albarghouthi): the [Bloodborne Windows port](https://github.com/Supermedo/bloodborne_pc)
  and [uncharted_pc](https://github.com/Supermedo/uncharted_pc), the source of ideas and fixes used
  here (scalar constant copy HLE, addr64 `BUFFER_LOAD`, Ajm end-of-job rules, the `m_gfxEopTick` wait patch from 0.3.2, read-only `O_CREAT` open for Uncharted 1 sound, the Intel GPU fix from 0.3.3).
- **Felipe Prado** and contributors: [gow3_pc](https://github.com/FelipePrado0/gow3_pc), the God of
  War III port this repository is a fork of, with the rendering fixes by
  [cuesta4](https://github.com/cuesta4/shadPS4/tree/eltutz).
- **Mindro** (ManoharPadul): the [shadPS4 build for the Uncharted collection](https://github.com/ManoharPadul/ShadPS4),
  whose release notes showed which problems to look for (texture cache, recommended settings).
- **shadPS4 team**: the [shadPS4](https://github.com/shadps4-emu/shadPS4) video core, which does all
  the GPU and shader translation to Vulkan.
- **Claude** ([Anthropic](https://www.anthropic.com)): developed with Claude's help (research, code,
  testing, packaging and documentation).

Also used: [LibAtrac9](https://github.com/Thealexbarney/LibAtrac9), [FFmpeg](https://ffmpeg.org),
[SDL3](https://github.com/libsdl-org/SDL), [Dear ImGui](https://github.com/ocornut/imgui),
[sirit](https://github.com/shadps4-emu/sirit), [magic_enum](https://github.com/Neargye/magic_enum),
[miniz](https://github.com/richgel999/miniz), [xbyak](https://github.com/herumi/xbyak),
[Vulkan Memory Allocator](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator),
[FSR-Vulkan](https://github.com/FireBurn/FSR-Vulkan), [MSYS2](https://www.msys2.org) and
[LLVM](https://llvm.org).

The original God of War III port README is in [docs/gow3/README.gow3.md](docs/gow3/README.gow3.md).

## License

GNU GPL v2 or later ([LICENSE](LICENSE)), the same as shadPS4 and the ports this one derives from.
Third-party components keep their own licenses.
