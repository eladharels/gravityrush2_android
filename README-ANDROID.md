# Uncharted: The Nathan Drake Collection — Android port (work in progress)

An experimental Android/ARM64 port of the unofficial native PC port of Uncharted: The Nathan
Drake Collection (PS4). It merges two GPL projects that share one history:

- **zorde23/ucndc_pc** (Uncharted 1-3 on PC, from Felipe Prado's God of War III port), and
- **zFitness/gow3_android** (the Android runtime of the God of War III port: the game's x86-64
  code in FEXCore, the runtime and the shadPS4 renderer native on ARM64, Turnip for Vulkan).

No game files are included. You need your own dump of the collection (tested on PC with
CUSA02344 v01.02), with `eboot.bin`, `big2-ps4_Shipping.elf` and `big3-ps4_Shipping.elf`.

## What this branch changes

- `src/runtime_fiber_jit.c` + `guest_cpu_context_save/load` (`src/guest_cpu.h`,
  `cpu/guest_cpu_fex.cpp`): libSceFiber for Uncharted 3's job system under the JIT. On x86-64 the
  PC port swaps host registers in assembly; under FEXCore a fiber switch replaces the guest
  registers when the host call returns.
- The Uncharted renderer's x86 intrinsics (`__rdtsc`, `pause`) go through `Common::RawRDTSC()` and
  `COMMON_CPU_RELAX()`.
- The SRT walker: the PC's x86 machine-code walker keeps the Uncharted context; ARM64 uses
  gow3_android's portable bytecode (the Uncharted pointer loader, GPU-written leaves and ReadConst
  windows are not in the bytecode yet).
- Launcher: the collection is recognised by its executables; `uc_game` in gow3.ini picks the game
  (uc1, uc2, uc3; default uc3) with the runtime options the PC launcher uses for it
  (`UnchartedGames.kt`). Application id `com.uncharted.android`, so it installs next to the
  God of War III app.

## Status

Milestone 1: the APK builds in GitHub Actions and reaches the game loader. Not yet run on a device.

Known gaps: the uc3 SRT walker features on ARM64, x86 code hooks (mouse camera, God of War III
cheats) are off under the JIT, no in-app picker for `uc_game` yet, update folders must be merged
into the base dump.

## Credits

deadinside28 (bloodborne_pc), Supermedo (Windows port, uncharted_pc), Felipe Prado (gow3_pc),
zorde23 (ucndc_pc), zFitness (gow3_android), ducvd89 (Bloodborne-Android), cuesta4, the shadPS4
and FEX-Emu teams. GPL v2 or later, as the projects it is built from.
