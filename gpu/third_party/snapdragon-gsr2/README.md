# Snapdragon Game Super Resolution 2 (SGSR 2), vendored shaders

- Upstream: https://github.com/SnapdragonStudios/snapdragon-gsr (directory
  `sgsr/v2/include/glsl_2_pass_cs`), commit
  `d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e`.
- License: BSD-3-Clause, Copyright (c) 2024, Qualcomm Innovation Center, Inc.
  (`LICENSE`, verbatim upstream text).
- Variant: the **2-pass compute** shaders (`convert` + `upscale`). The upstream repo
  also ships a 2-pass-fs and a 3-pass-cs variant; the 2-pass compute one was chosen
  because it is the variant Bloodborne-Android validated on the same target hardware
  (Snapdragon 8 Gen 2 / Adreno 740, ~1.1 ms per frame), it needs no fragment-path
  color state from the game's renderpasses, and it exposes exactly the inputs this
  port already computes (scene color, scene depth, motion vectors, jitter).

## Files

- `glsl_2_pass_cs/sgsr2_convert.comp`, `glsl_2_pass_cs/sgsr2_upscale.comp`:
  **unmodified upstream copies**.
- Derived copies with this port's Vulkan integration live in
  `gpu/shadps4/video_core/host_shaders/gsr2_{convert,upscale}.comp`. Each carries the
  Qualcomm copyright header plus a list of the changes relative to upstream, so the
  diff against `glsl_2_pass_cs/` stays reviewable. The change list is shared with
  Bloodborne-Android's derivation of the same upstream commit (their
  `gpu/shadps4/video_core/host_shaders/gsr2_convert.comp`), since both ports feed the
  shaders the same motion-vector convention.

Consumer: `gpu/shadps4/video_core/renderer_vulkan/vk_gsr2.{h,cpp}` (the `GsrUpscaler`
provider used by `vk_temporal_upscaler`). Vendor files are compiled by
`gpu/CMakeLists.txt` `add_host_shader` into SPIR-V C headers.
