// SPDX-License-Identifier: GPL-2.0-or-later
// Parity gate for the C++ boot-image port (android-launcher/app/src/main/cpp/gow3link) against
// the Python reference (scripts/prepare.py, scripts/link_modules.py).
//
// Rebuilds boot.bin (G3PROBE2) and boot-linked.bin (G3PROBE5) from the same game and compares
// both byte for byte. boot.bin is compared on its own as well, so a failure says whether the eboot
// flattening or the module linking drifted.
//
// usage: gow3_link_golden <game-dir> <python-out-dir>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "gow3_link.h"

namespace {

bool ReadFile(const std::string& path, std::vector<u8>& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return false;
    }
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

bool Compare(const char* label, const std::vector<u8>& mine, const char* path) {
    std::vector<u8> theirs;
    if (!ReadFile(path, theirs)) {
        std::printf("  %-16s MISSING reference %s\n", label, path);
        return false;
    }
    if (mine == theirs) {
        std::printf("  %-16s ok (%zu bytes)\n", label, mine.size());
        return true;
    }
    size_t at = 0;
    while (at < mine.size() && at < theirs.size() && mine[at] == theirs[at]) {
        ++at;
    }
    std::printf("  %-16s DIFFERS at 0x%zx: ours %zu bytes, reference %zu bytes\n", label, at,
                mine.size(), theirs.size());
    if (at < mine.size() && at < theirs.size()) {
        const size_t end = std::min(mine.size(), theirs.size());
        std::printf("  %-16s ours 0x%02x%02x%02x%02x, reference 0x%02x%02x%02x%02x\n", "", mine[at],
                    at + 1 < end ? mine[at + 1] : 0, at + 2 < end ? mine[at + 2] : 0,
                    at + 3 < end ? mine[at + 3] : 0, theirs[at], at + 1 < end ? theirs[at + 1] : 0,
                    at + 2 < end ? theirs[at + 2] : 0, at + 3 < end ? theirs[at + 3] : 0);
    }
    return false;
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::printf("usage: gow3_link_golden <game-dir> <python-out-dir>\n");
        return 2;
    }
    const std::string game = argv[1];
    const std::string out = argv[2];
    std::vector<u8> eboot, libc, fios;
    if (!ReadFile(game + "/eboot.bin", eboot) || !ReadFile(game + "/sce_module/libc.prx", libc) ||
        !ReadFile(game + "/sce_module/libSceFios2.prx", fios)) {
        std::printf("cannot read the game modules from %s\n", game.c_str());
        return 2;
    }
    std::string error;
    std::vector<u8> boot;
    if (!gow3_link::PrepareBoot(eboot.data(), eboot.size(), libc.data(), libc.size(), boot,
                                error)) {
        std::printf("PrepareBoot failed: %s\n", error.c_str());
        return 1;
    }
    std::vector<u8> linked;
    if (!gow3_link::LinkBoot(boot, eboot, {libc, fios}, linked, error)) {
        std::printf("LinkBoot failed: %s\n", error.c_str());
        return 1;
    }
    bool ok = true;
    std::printf("boot image parity for %s\n", game.c_str());
    ok &= Compare("boot.bin", boot, (out + "/boot.bin").c_str());
    ok &= Compare("boot-linked.bin", linked, (out + "/boot-linked.bin").c_str());
    return ok ? 0 : 1;
}
