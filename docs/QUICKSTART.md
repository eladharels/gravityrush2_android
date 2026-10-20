# Quickstart: from getting the project to running debug (Android port)

This guide is aimed at new developers. It walks you through building the God of War III
**Android / ARM64 port** in this repository from scratch on a **macOS Apple Silicon** host:
cross-compiling the ARM64 components, packaging a debug APK, installing it on a real device
and inspecting the runtime logs.

You are expected to **bring your own game dump** (neither this repository nor the APK
contains any game files).

## Prerequisites

| Prerequisite | Description |
| --- | --- |
| Host OS | macOS Apple Silicon + [Homebrew](https://brew.sh) |
| Homebrew packages | `llvm`, `lld`, `boost`, `bison`, `flex` (`build_mac_arm64.sh` locates LLVM/LLD/Boost automatically; `build_turnip.sh` prepends keg-only bison/flex to PATH); `vulkan-headers` (see 2.1) |
| Android SDK | platform-tools (`adb`) |
| Device | ARM64 Android device (Adreno GPU), USB debugging enabled |
| Game dump | `CUSA01740 v01.02` (currently used for testing; the desktop build target is `CUSA01623 v01.02`) |
| Steam dependency libs | fetched by `fetch_steam_libs.sh` in section 2.4 (pinned version + SHA-512 check) |

> For game patches and `eboot.bin` validation, defer to the notes in [README](../README.md).

## 1. Clone the repository and the FEX submodule

```bash
git clone --recursive <your-repo-url> gow3_pc
cd gow3_pc
git submodule update --init --recursive third_party/FEX
```

## 2. Prepare external inputs

### 2.1 ARM64 sysroot — build time (Debian image)

The cross-compilation headers/libraries for the build phase come from a **Debian image**
(the runtime rootfs in 2.3 comes from fexdroid).

`fetch_arm64_sysroot.py` pulls Debian trixie arm64 packages and assembles a sysroot
(output: `.local-deps/android/arm64-sysroot`). `--vulkan-headers` points at a
Vulkan-Headers prefix whose `include/{vulkan,vk_video}` **overrides** the Debian-provided
headers — the older headers lack definitions such as `eMesaKosmickrisp`, so you need
Vulkan-Headers **v1.4.363**:

```bash
# Homebrew way (version follows the current formula; prefer >= 1.4.363):
#   brew install vulkan-headers
# Or clone a pinned version:
#   git clone --depth 1 --branch v1.4.363 https://github.com/KhronosGroup/Vulkan-Headers.git /tmp/vk-headers

python3 tools/android/inputs/fetch_arm64_sysroot.py \
  --mirror https://mirrors.tuna.tsinghua.edu.cn/debian --jobs 8 \
  --vulkan-headers /opt/homebrew/opt/vulkan-headers
```

### 2.2 Host header-only dependencies → `.local-deps/root/usr`

`build_arm64.sh` looks for tsl-robin-map and glslang in `.local-deps/root/usr`
(`GOW3_ARM64_HEADERS` default) with hard-coded paths, so both must be installed into that
prefix:

```bash
PREFIX="$PWD/.local-deps/root/usr"

# tsl-robin-map: build_arm64.sh requires $headers/share/cmake/tsl-robin-map
git clone --depth 1 https://github.com/Tessil/robin-map.git /tmp/robin-map
cmake -S /tmp/robin-map -B /tmp/robin-map/build -DCMAKE_INSTALL_PREFIX="$PREFIX"
cmake --build /tmp/robin-map/build
cmake --install /tmp/robin-map/build

# VulkanMemoryAllocator (VMA): headers + share/cmake/VulkanMemoryAllocator
git clone --depth 1 https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator.git /tmp/vma
cmake -S /tmp/vma -B /tmp/vma/build -DCMAKE_INSTALL_PREFIX="$PREFIX"
cmake --build /tmp/vma/build
cmake --install /tmp/vma/build

# glslang: glslangValidator generates host shaders at build time and must be in $headers/bin.
# Build libSPIRV.a / glslangValidator first, then install (install does not compile).
git clone --depth 1 https://github.com/KhronosGroup/glslang.git /tmp/glslang
cmake -S /tmp/glslang -B /tmp/glslang/build -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_OPT=OFF -DCMAKE_INSTALL_PREFIX="$PREFIX"
cmake --build /tmp/glslang/build
cmake --install /tmp/glslang/build
```

Boost can simply be **reused from Homebrew** on macOS, no need to install it into that
prefix: `build_mac_arm64.sh` points `GOW3_BOOST_DIR` at Homebrew's `lib/cmake/Boost-*`
automatically (see Prerequisites).

### 2.3 Runtime rootfs — runtime (fexdroid APK)

The Linux userland on the device (glibc/FEX, X11, Turnip etc., also Debian trixie arm64)
comes from the `assets/rootfs-arm64.tar` inside the official **fexdroid** legacy APK.
`build_android_runtime.sh` requires it when packaging the runtime and fails hard if missing:

```bash
python3 tools/android/inputs/fetch_rootfs.py
```

- **Source**: `https://github.com/cobrabm12/fexdroid/releases/download/apk-latest/fexdroid-legacy.apk`
  (overridable via `--url` / `GOW3_FEXDROID_APK_URL`).
- **Validation**: pinned SHA-256 `21bed34092f698e39e77138589ebdab8d3498577ffc4fdc3bfef1c711b50d3bf`
  (overridable via `--sha256` / `GOW3_FEXDROID_APK_SHA256`); a mismatch is an error.
- **Output**: `.local-deps/android/migration/x/rootfs`; the ELF loader path is migrated from
  `ro.cobrabm.fexdroid` to `com.gow3.android`.
- **Idempotent**: skips when already fully present; use `--force` to re-fetch.

### 2.4 Dependency libraries (Steam sign-in)

The launcher's Steam sign-in / Lossless Scaling frame-generation feature depends on Java
libraries such as JavaSteam. `fetch_steam_libs.sh` resolves pinned dependencies (javasteam
1.8.0, xz, zstd-jni, Spongy Castle, ZXing) via a pinned Apache Maven into
`.local-deps/android/steam-libs/{jars,jni}` and generates `jars.txt` (Gradle uses it to dex
the jars into the APK):

```bash
bash tools/android/inputs/fetch_steam_libs.sh
```

- **Validation**: the Maven distribution ships with a pinned SHA-512 check; dependency
  versions are constants at the top of the script.
- **Output**: `.local-deps/android/steam-libs/jars.txt` (+ `jars/`, `jni/`).

### 2.5 Verify external inputs

Cross-check each item against the entry points of `build_arm64.sh` / `build_android_runtime.sh`:

```bash
[[ -f .local-deps/android/arm64-sysroot/usr/lib/aarch64-linux-gnu/libc.so.6 ]] && echo "sysroot ok"
[[ -d .local-deps/root/usr/share/cmake/tsl-robin-map ]] && echo "robin-map ok"
[[ -x .local-deps/root/usr/bin/glslangValidator ]] && echo "glslang ok"
[[ -f third_party/FEX/CMakeLists.txt ]] && echo "FEX ok"
ls "$(brew --prefix boost)/lib/cmake/"Boost-* >/dev/null 2>&1 && echo "boost ok"
[[ -d .local-deps/android/migration/x/rootfs ]] && echo "rootfs ok"
[[ -f .local-deps/android/steam-libs/jars.txt ]] && echo "steam-libs ok"
```

`.local-deps/` and `out/` are not versioned.

## 3. Copy the game files needed to prepare the loader

The full game stays on the phone. Only four small files are needed on the computer:

```text
game/CUSA01740/eboot.bin                         # from CUSA01740-patch
game/CUSA01740/sce_sys/param.sfo                 # from CUSA01740-patch
game/CUSA01740/sce_module/libc.prx               # from CUSA01740
game/CUSA01740/sce_module/libSceFios2.prx        # from CUSA01740
```

## 4. Prepare the game image

```bash
python3 scripts/prepare.py game/CUSA01740 --out out
python3 scripts/link_modules.py game/CUSA01740 --out out
python3 scripts/content_profile.py game/CUSA01740 --out out --sku full
```

Expected output: `out/eboot.elf`, `out/boot.bin`, `out/boot-linked.bin`, `out/content.bin`.
The `Unavailable non-loadable metadata headers` printed by `prepare.py` is informational,
not a failure.

## 5. Build the ARM64 components and the debug APK

Set up the toolchain environment (once per shell):

```bash
export GOW3_LLVM_PREFIX=/opt/homebrew/opt/llvm
export PATH="$GOW3_LLVM_PREFIX/bin:/opt/homebrew/bin:$PATH"
export GOW3_AR="$GOW3_LLVM_PREFIX/bin/llvm-ar"
export GOW3_FEX_SOURCE="$PWD/third_party/FEX"
```

Build CPU + GPU + native probe. Because the game-specific patch addresses for the current
`CUSA01740` test build have not passed validation, you must set `GOW3_SKIP_GAME_PATCH_PARTS=1`:

```bash
GOW3_SKIP_GAME_PATCH_PARTS=1 \
bash tools/android/build/build_arm64.sh
```

> You can also use the isolated wrapper `bash tools/android/build/build_mac_arm64.sh`,
> which locates the Homebrew LLVM/LLD, Boost and a sibling FEX checkout automatically,
> without polluting the persistent shell environment.

Build the full runtime (native components + release Turnip/LSFG + release runtime). This step
needs the 2.3 fexdroid rootfs; if missing it errors with `fexdroid migration rootfs missing`:

```bash
GOW3_SKIP_GAME_PATCH_PARTS=1 \
bash tools/android/build/build_android_runtime.sh
```

**Turnip / LSFG need no manual preparation**: the scripts fetch and build them automatically
at pinned versions —

- **Turnip** (`build_turnip.sh main` / `gen8`): Mesa sources are fetched at a pinned commit
  via `git init + fetch` (main from GitLab freedesktop, gen8 from GitHub
  `whitebelyash/mesa-unified`); the first run also downloads `mesa-sysroot` (another set of
  Debian packages) and creates a local meson venv.
- **LSFG** (`build_lsfg.sh`): the `lsfg-vk` repo is cloned at `v1.0.0` (commit `7113d7d`),
  patched with `tools/android/lsfg-patches/` KGSL/Turnip patches, then compiled.

The first run needs network access and takes a while (fetching Mesa + compiling two Turnip
variants); later runs hit the cache and skip.

Outputs:

```text
out/arm64/gpu/libgow3gpu.so
out/arm64/fex/libgow3cpu.so
out/arm64/gow3-probe
out/arm64/bundle/
out/arm64/turnip/          # main variant (libvulkan_freedreno.so)
out/arm64/turnip-gen8/     # Adreno 8xx variant
out/arm64/lsfg/            # liblsfg-vk.so + VkLayer_LS_frame_generation.json
out/release/runtime.tar.gz # rootfs + gow3port + home (no game content)
```

With the native components already in place, package the debug APK (reuses the local
Turnip/LSFG, does not rebuild them):

```bash
GOW3_REBUILD_DEBUG_RUNTIME=1 \
GOW3_DEBIAN_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/debian \
bash tools/android/debug/build_debug_apk.sh
```

- The APK is written to `out/debug/God of War III-debug-<version>.apk`.
- If `out/debug/runtime.tar.gz` already exists and the native components are unchanged, you
  can drop `GOW3_REBUILD_DEBUG_RUNTIME=1` and reuse it directly.

The Steam sign-in libraries were already prepared in section 2.4 (`fetch_steam_libs.sh`), so
no further handling is needed here; if `jars.txt` is missing, Steam sign-in in the APK
becomes a placeholder implementation.

#### One-click build with Android Studio (signing matches the CLI)

`build_debug_apk.sh` and Android Studio's Build / Run execute the **same Gradle task**
(`:app:assembleDebug`). The project `build.gradle.kts` has **no** `signingConfigs`, so both
use the **built-in debug signing of the Android Gradle Plugin (AGP)**:

- Keystore: `~/.android/debug.keystore` (auto-generated by AGP on the first debug build if absent)
- alias: `androiddebugkey`; store / key passwords are both `android`

That "android" password is not configured in the project or Android Studio — it is an
**AGP hard-coded default**. The `GOW3_KEYSTORE_PASSWORD` etc. variables above only affect
**release** signing (`build_release_apk.sh`), not debug builds; the repository's
`tools/android/debug/gow3-debug.keystore` (alias `gow3`) is likewise not wired into Gradle
debug signing, so don't be misled by it.

**You must stage the runtime payload before using Android Studio**: the debug source set
reads `runtime.payload` + `runtime.sha256` from `out/android-debug/assets/` (see
`android-launcher/app/build.gradle.kts`). Clicking Build without staging first still produces
an APK with consistent signing, but it will fail to run once installed because the runtime is
missing. Run `build_debug_apk.sh` once to stage the payload; subsequent Android Studio
incremental builds will reuse it. The artifact is at
`android-launcher/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.

## 6. Install, clear caches and view logs

```bash
adb install -r out/debug/God\ of\ War\ III-debug-*.apk
adb shell run-as com.gow3.android sh -c '
  rm -rf files/gow3port/user/cache
  rm -rf files/gow3port/user/shader
'
adb exec-out run-as com.gow3.android \
  sh -c 'tail -f files/gow3port/logs/android-game.log'
```

The full `CUSA01740` game stays on the device (e.g. `Download/games/ps4/GOW3 ROM/CUSA01740`).
The current Android game picker accepts only one game directory; if the base game and
`CUSA01740-patch` are stored separately, you must present a merged game view to it at runtime.

## 7. Troubleshooting

| Symptom | Fix |
| --- | --- |
| `adb install` reports `INSTALL_FAILED_USER_RESTRICTED` | Enable "Install via USB" in Developer options (common on MIUI) |
| Black screen / hangs on launch | Clear `user/cache` and `user/shader` (see section 6), then relaunch |
| Verify the GPU without installing the game | Run `run-gow3.sh vulkan` on the device (Vulkan self-check) |
| Check system functions the game still needs | `run-gow3.sh check` (`--check-imports`, needs a prepared image) |
| No game-process logs visible | First confirm the APK is installed with package `com.gow3.android`, then run the log command from section 6 |

---

## Notes

- Build outputs `out/` and external inputs `.local-deps/` are not versioned; game dumps, keys,
  accounts and caches likewise never enter the repository or the APK.
- More docs: [README.md](../README.md) (features & troubleshooting) and [MODS.md](MODS.md).
