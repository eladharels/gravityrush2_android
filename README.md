# Uncharted: The Nathan Drake Collection for PC (native port)

**English** | [Português (Brasil)](README.pt-BR.md)

A native Windows 10 and 11 port of the games in **Uncharted: The Nathan Drake Collection** (PS4,
Bluepoint, 2015). The focus is **Uncharted 3: Drake's Deception**; Uncharted 2 is already
playable and Uncharted 1 already boots and runs.

> **No game files are included.** You need a dump from your own PS4
> (tested: **CUSA02344**, version 01.00 with the **01.02** update).
> This project is not affiliated with Sony Interactive Entertainment, Naughty Dog or Bluepoint
> Games. Uncharted is a trademark of Sony Interactive Entertainment.

## How it works

The PS4 uses an x86-64 processor, the same architecture as a PC, so the game's original executable
runs **directly on the CPU**, with no emulation: a runtime written for the port replaces the PS4
system libraries (memory, threads, files, audio, video, controller, saves). The graphics are
translated: the PS4 GPU (AMD GCN) commands and shaders become Vulkan through the
[shadPS4](https://github.com/shadps4-emu/shadPS4) video core.

This repository is a fork of the [God of War III port](https://github.com/FelipePrado0/gow3_pc),
which comes from Supermedo's [Bloodborne Windows port](https://github.com/Supermedo/bloodborne_pc)
and deadinside28's original [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc). That is
why many names still start with `gow3` / `GOW3_` (the executable is `gow3-probe.exe`).

## Status

| Game | Executable | Status |
|---|---|---|
| Uncharted 3 | `big3-ps4_Shipping.elf` | Playable; **60 FPS** in the Chateau, the Cartagena streets and the museum with the optimizations in `windows\jogar_uc3.bat` |
| Uncharted 2 | `big2-ps4_Shipping.elf` | Playable (51–60 FPS), with its own saves. Use `windows\jogar_uc2.bat` |
| Uncharted 1 | `eboot.bin` | Boots and runs at 60 FPS (`windows\jogar_uc1.bat`); lightly tested |

Known issues: some engine sounds (Ajm) are missing; in the Uncharted 2 train prologue the carriage
falls too early with `GOW3_COMPUTE_PIPE=1`; core bugs shadPS4 also has (white hair after loading a
checkpoint, dust dots). Internal resolution scaling (FSR/DLSS) does not work for Uncharted: the
inherited hooks are specific to God of War III.

The project log (in Portuguese), with what was done and why, is in
[docs/uc3/DEVLOG.md](docs/uc3/DEVLOG.md).

## Requirements

- 64-bit Windows 10 (1903+) or 11; a Vulkan 1.3 GPU (developed on an AMD RX 6750 XT).
- [MSYS2](https://www.msys2.org) to build.
- Your own PS4 dump: the game folder (`CUSA02344`) and the 01.02 update folder.

## Building

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
   git clone --recursive <this repository's url> uc3_pc
   ```

3. Edit `windows\config.bat` with the paths to MSYS2, the game and the update.
4. Run `windows\compilar.bat` (or `bash build.sh` in the CLANG64 shell). The result is
   `out\gow3-probe.exe`.

## Playing

Double-click the game's `.bat` in `windows\`:

| File | What it does |
|---|---|
| `config.bat` | Paths (MSYS2, game, update) and the recommended optimization list |
| `compilar.bat` | Builds the port |
| `jogar_uc3.bat` | Uncharted 3 with the optimizations (60 FPS) |
| `jogar_uc2.bat` | Uncharted 2 (without `GOW3_COMPUTE_PIPE`, because of the train) |
| `jogar_uc1.bat` | Uncharted 1 (experimental) |
| `fechar_jogo.bat` | Closes the game if it hangs |

Each session's log goes to `out\last-run.log` (the previous one to `out\previous-run.log`). Saves
are kept in `user\`. In game, **Insert** (or R3+L2 on a controller) opens the port's menu.

You can also call the script directly and pass options for that run only:

```powershell
powershell -ExecutionPolicy Bypass -File tools\run_uc3.ps1 -Set GOW3_READBACKS=1,GOW3_FRAME_STATS=1
```

### Options (toggles)

Every new optimization goes in **off by default** in the code; the `.bat` files in `windows\`
turn on the tested set. The main ones:

| Variable | Effect |
|---|---|
| `GOW3_READBACKS` | `2` (the `run_uc3.ps1` default): precise GPU readbacks, needed for the scenery to show; `1`: relaxed mode |
| `GOW3_READBACK_ASYNC`, `GOW3_READBACK_FAST*`, `GOW3_READBACK_SIDEQ`, `GOW3_DEFERRED_READBACK` | GPU readbacks without draining the whole queue |
| `GOW3_SCALAR_COPY_HLE`, `GOW3_HLE_INVALIDATE` | Replaces a copy compute shader with native code (and invalidates the caches it writes) |
| `GOW3_COMPUTE_PIPE`, `GOW3_LEAF_COPY` | Compute processing shortcuts |
| `GOW3_EAGER_SUBMIT*`, `GOW3_ASYNC_SUBMIT`, `GOW3_DEFERRED_SUBMIT` | Submit GPU work earlier / on another thread |
| `GOW3_RING_COPY_THREAD`, `GOW3_RING_DEDUP` | Ring buffer copies on a separate thread, without repeats |
| `GOW3_FRAME_STATS` | Frame statistics in the log |
| `GOW3_EXECUTABLE` | Which game to start (`big3-ps4_Shipping.elf`, `big2-ps4_Shipping.elf`, `eboot.bin`) |
| `GOW3_LOW_IMAGE` | Loads the executable below 2 GB (needed for Uncharted 1) |

The full list, with measurements, is in the [DEVLOG](docs/uc3/DEVLOG.md).

## Credits

- **deadinside28**: [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc), the original
  native port (the base of everything, also called bbport).
- **Supermedo** (Mohammed Albarghouthi): the [Bloodborne Windows port](https://github.com/Supermedo/bloodborne_pc)
  and [uncharted_pc](https://github.com/Supermedo/uncharted_pc), the source of ideas and fixes used
  here (scalar constant copy HLE, addr64 `BUFFER_LOAD`, Ajm end-of-job rules, frames-ahead limit).
- **Felipe Prado** and contributors: [gow3_pc](https://github.com/FelipePrado0/gow3_pc), the God of
  War III port this repository is a fork of, with the rendering fixes by
  [cuesta4](https://github.com/cuesta4/shadPS4/tree/eltutz).
- **shadPS4 team**: the [shadPS4](https://github.com/shadps4-emu/shadPS4) video core, which does all
  the GPU and shader translation to Vulkan.

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
