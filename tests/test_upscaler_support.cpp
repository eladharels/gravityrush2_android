// SPDX-License-Identifier: GPL-2.0-or-later
// Saved FSR 4 choices must be safe before the first rendered frame on older GPUs.
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <initializer_list>
#include <unistd.h>
#include "gow3_settings.h"

int main() {
    using namespace Gow3Settings;
    char path[] = "/tmp/gow3-upscaler-test-XXXXXX";
    const int fd = mkstemp(path);
    assert(fd >= 0);
    close(fd);
    setenv("GOW3_CONFIG", path, 1);
    unsetenv("GOW3_UPSCALER");
    unsetenv("GOW3_UPSCALE_PRESET");
    unsetenv("GOW3_RENDER_RES");
    auto& s = Get();
    s.upscaler = UpscalerTaa;
    s.preset = Performance;
    assert(RenderPreset() == NativeAA && s.preset == Performance);
    Save();
    s.upscaler = UpscalerOff;
    Load();
    assert(s.upscaler == UpscalerTaa && s.preset == Performance);
    assert(RenderPreset() == NativeAA && !ResolutionNeedsRestart());
    s.upscaler = UpscalerFsr3;
    assert(RenderPreset() == Performance);
    for (bool fsr4 : {false, true}) {
        for (bool fsr411 : {false, true}) {
            for (int requested = 0; requested < UpscalerCount; ++requested) {
                FILE* config = std::fopen(path, "w");
                assert(config);
                std::fprintf(config, "upscaler=%s\npreset=3\noutput_res=2560x1440\n",
                             UpscalerName(requested));
                std::fclose(config);
                s.fsr4_problem = nullptr;
                Load();
                assert(s.upscaler == requested);
                ConfigureUpscalerSupport(fsr4, fsr411);
                const bool unsupported = (requested == UpscalerFsr4 && !fsr4) ||
                    (requested == UpscalerFsr411 && !(fsr4 && fsr411));
                assert(s.upscaler == (unsupported ? UpscalerFsr3 : requested));
                assert(s.fsr4_supported == fsr4);
                assert(s.fsr411_supported == (fsr4 && fsr411));
                assert(bool(s.fsr4_problem.load()) == unsupported);
                assert(s.preset == Performance && s.output_res == 2);
                // Startup patch settings still describe the already applied guest patches.
                assert(s.startup_upscaler == requested);
                ConfigureUpscalerSupport(fsr4, fsr411);
                assert(s.upscaler == (unsupported ? UpscalerFsr3 : requested));
            }
        }
    }
    s.startup_preset = Quality;
    s.startup_upscaler = UpscalerFsr3;
    s.upscaler = UpscalerFsr3;
    s.preset = Performance;
    assert(RenderPreset() == Performance && !ResolutionNeedsRestart());
    setenv("GOW3_RENDER_RES", "1706x960", 1);
    assert(RenderPreset() == Quality && ResolutionNeedsRestart());
    s.preset = Quality;
    assert(!ResolutionNeedsRestart());
    s.upscaler = UpscalerOff;
    assert(ResolutionNeedsRestart());
    s.upscaler = UpscalerFsr4;
    assert(!ResolutionNeedsRestart()); // provider change at the same patched size is live
    s.upscaler = UpscalerTaa;          // TAA needs native guest targets
    assert(ResolutionNeedsRestart());
    s.upscaler = UpscalerFsr4;
    const int output = s.startup_output_res;
    s.output_res = (output + 1) % OutputCount;
    assert(ResolutionNeedsRestart());
    s.output_res = output;
    setenv("GOW3_RENDER_RES", "", 1);
    assert(!FixedRenderSession() && !ResolutionNeedsRestart());
    unsetenv("GOW3_RENDER_RES");
    // GSR 2 (Android default provider): the ini name round-trips, and the desktop fallback
    // target stays FSR 3.1 while Android falls back to off.
    assert(std::strcmp(UpscalerName(UpscalerGsr2), "gsr2") == 0);
    assert(IsGsr2(UpscalerGsr2) && !IsGsr2(UpscalerFsr3));
    {
        FILE* config = std::fopen(path, "w");
        assert(config);
        std::fprintf(config, "upscaler=gsr2\noutput_res=854x480\n");
        std::fclose(config);
        Load();
        assert(s.upscaler == UpscalerGsr2);
        assert(s.output_res == 4 && OutputWidths[4] == 854 && OutputHeights[4] == 480);
    }
    for (bool fsr4 : {false, true}) {
        setenv("GOW3_ANDROID_INPUT", "1", 1);
        FILE* config = std::fopen(path, "w");
        assert(config);
        std::fprintf(config, "upscaler=%s\n", UpscalerName(UpscalerFsr4));
        std::fclose(config);
        s.fsr4_problem = nullptr;
        Load();
        assert(s.upscaler == UpscalerFsr4);
        ConfigureUpscalerSupport(fsr4, false);
        assert(s.upscaler == (fsr4 ? UpscalerFsr4 : UpscalerOff));
        assert(bool(s.fsr4_problem.load()) == !fsr4);
        unsetenv("GOW3_ANDROID_INPUT");
    }
    unsetenv("GOW3_ANDROID_INPUT");
    std::remove(path);
    std::puts("Upscaler support: saved choices and FSR 3.1 fallback PASS");
}
