// SPDX-License-Identifier: GPL-2.0-or-later
// gow3port: the boot image the Bionic runtime executes, built on the device.
//
// This is a C++ port of scripts/prepare.py's prepare() and scripts/link_modules.py's link(): the
// eboot's PT_LOAD contents flattened into one image (G3PROBE2), then the game's own system modules
// - libc.prx first, then libSceFios2.prx - appended and their imports bound to each other's
// exports (G3PROBE5, boot-linked.bin). Without this the launcher needs Python on a host, which is
// what this migration removes.
//
// scripts/*.py stays the behaviour baseline: tools/migration/gow3_link_golden.cpp must produce
// byte-identical boot.bin and boot-linked.bin for the same game, and that test is what keeps the
// two implementations honest. Nothing here formats XML or JSON: the inputs are the player's own
// game files and the outputs are the binary formats probe.c reads.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

using u8 = std::uint8_t;
using u16 = std::uint16_t;
using u32 = std::uint32_t;
using i64 = std::int64_t;
using u64 = std::uint64_t;

namespace gow3_link {

/// prepare.prepare(): the eboot's flat memory image and the G3PROBE2 container probe.c and
/// link_modules.py read. The last header field is the runtime capability bit (libc's _init_env
/// really is RET), which prepare.py proves with inspect_libc() instead of assuming it - so the
/// libc.prx that ships with the game is an input here.
bool PrepareBoot(const u8* eboot, size_t eboot_size, const u8* libc, size_t libc_size,
                 std::vector<u8>& boot, std::string& error);

/// link_modules.link(): G3PROBE5. `boot` is the G3PROBE2 PrepareBoot produced. `eboot` is the
/// module again because resolving an import to a (NID, library, module) identity needs the
/// dynamic tables, which G3PROBE2 does not carry. `modules` are the game modules in link order
/// (libc.prx first - it becomes module id 2, which the TLS relocations of every later module
/// refer to). One contiguous image: the eboot first, then each module aligned to 64 KiB.
bool LinkBoot(const std::vector<u8>& boot, const std::vector<u8>& eboot,
              const std::vector<std::vector<u8>>& modules, std::vector<u8>& linked,
              std::string& error);

} // namespace gow3_link
