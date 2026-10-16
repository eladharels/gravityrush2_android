// SPDX-FileCopyrightText: Copyright 2024 shadPS4 Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <cstdlib>

#include <vector>
#include <boost/container/set.hpp>
#include <boost/container/small_vector.hpp>
#include "common/types.h"

namespace Serialization {
struct Archive;
}

namespace Shader {

// uc3 (from SuperMedo's uncharted_pc, GPL): a ReadConst whose offset varies per lane (a constant
// block's pointer plus a per-pixel offset, as addr64 buffer loads compute it) reads a window of the
// block's first dwords that the walker copies to the flat buffer; its flags are the window's start
// with this bit.
constexpr u32 ReadConstWindowFlag = 1u << 16;
// uc3: GOW3_WINDOW_DWORDS=<n> (16-4096, default 256): dwords copied per window.
inline u32 ReadConstWindowDwords() {
    static const u32 dwords = [] {
        const char* env = std::getenv("GOW3_WINDOW_DWORDS");
        const u32 v = env ? static_cast<u32>(std::strtoul(env, nullptr, 10)) : 256u;
        return v < 16u ? 16u : (v > 4096u ? 4096u : v);
    }();
    return dwords;
}
// uc3: marks the ReadConsts made from an addr64 buffer load (GOW3_WINDOW_ADDR64_ONLY=1 gives only
// those a window). Cleared by the offset assignment; masked off by the SPIR-V emitter.
constexpr u32 ReadConstAddr64Mark = 1u << 17;
inline bool WindowAddr64Only() {
    static const bool only = std::getenv("GOW3_WINDOW_ADDR64_ONLY") != nullptr;
    return only;
}

struct SrtWalkCtx;
// uc3 (Fable R.8.1): the walker takes a context: the pointer loader it calls for each table
// pointer and a table it fills with the guest address of each flattened dword.
using PFN_SrtWalker = void PS4_SYSV_ABI (*)(const u32* /*user_data*/, u32* /*flat_dst*/,
                                            SrtWalkCtx* /*ctx*/);
PFN_SrtWalker RegisterWalkerCode(const u8* ptr, size_t size);

/// uc3: a table pointer as the walker follows it: `host` is where its loads read (the
/// unprotected backing view, or the guest address when there is none), `guest` its guest
/// address.
struct SrtPtr {
    u64 host;
    u64 guest;
};
/// Loads the 8-byte pointer at host_base + off (guest_base + off in the guest; guest_base is 0
/// for user data) and returns where to read the table it points to.
using PFN_SrtLoadPtr = SrtPtr PS4_SYSV_ABI (*)(const u8* host_base, u64 guest_base, u64 off);
/// uc3 (Fable R.8.2): a flattened dword whose guest source the GPU wrote; with GOW3_LEAF_COPY the
/// flat holds 0 there and the draw recording thread copies the 4 bytes on the GPU.
struct SrtGpuLeaf {
    u32 dst_dw;
    u32 pad;
    u64 src;
};
/// Rereads the flattened dwords whose guest source is GPU-written (src[i] is 0 for none), or,
/// given `leaves`, zeroes them and lists them for a GPU copy.
using PFN_SrtFixup = void (*)(const u64* src, u32* flat, size_t count,
                              std::vector<SrtGpuLeaf>* leaves);
/// Reads a GPU-written dword back (synchronous) into `out`.
using PFN_SrtReadDword = void (*)(u64 src, u32* out);

struct SrtWalkCtx {
    PFN_SrtLoadPtr load_ptr; // [r8 + 0]
    u64* src_table;          // [r8 + 8]
};

void SetSrtWalkerHooks(PFN_SrtLoadPtr load_ptr, PFN_SrtFixup fixup,
                       PFN_SrtReadDword read_dword = nullptr);
/// Runs a walker. `sync` false: GPU-written bytes are not read back. `leaves`: see PFN_SrtFixup.
void RunSrtWalker(PFN_SrtWalker walker, const u32* user_data, u32* flat, size_t flat_dw,
                  bool sync = true, std::vector<SrtGpuLeaf>* leaves = nullptr);
/// uc3 R.21: while set on a thread, RunSrtWalker adds the guest pages it reads (table pointers
/// and flattened leaves, 4 KiB pages) to this list (unsorted, may repeat).
inline thread_local std::vector<u64>* t_srt_read_pages = nullptr;
/// uc3 R.22: with t_srt_read_pages, also the value of every guest location the walk read (table
/// pointers: 8 bytes, flattened leaves: 4), at the host address that reads it unprotected. Equal
/// values later mean the walk would produce the same flat.
struct SrtCheck {
    u64 host;
    u64 value;
    u32 size;
    u32 pad;
};
inline thread_local std::vector<SrtCheck>* t_srt_checks = nullptr;
/// True when every check still holds.
inline bool SrtChecksHold(const SrtCheck* checks, u32 count) noexcept {
    for (u32 i = 0; i < count; ++i) {
        const auto& check = checks[i];
        u64 value = 0;
        __builtin_memcpy(&value, reinterpret_cast<const void*>(check.host), check.size == 8 ? 8 : 4);
        if (value != check.value) {
            return false;
        }
    }
    return true;
}
/// Reads one GPU-written dword back through the hook (or plainly when there is none).
void SrtReadDword(u64 src, u32* out);
/// True while the walker of this thread may read GPU-written bytes back.
bool SrtWalkerSyncAllowed();

/// uc3: a load the SRT walker faulted on: `mov rdi, [mem]` (8 bytes) or `mov r10d, [mem]`
/// (4 bytes). False when `rip` is not walker code or the instruction is not one of those.
struct SrtWalkerLoad {
    bool to_rdi;
    u32 size;
    u32 length;
};
bool DecodeSrtWalkerLoad(const void* rip, SrtWalkerLoad& load);

struct PersistentSrtInfo {
    // Special case when fetch shader uses step rates.
    struct SrtSharpReservation {
        u32 sgpr_base;
        u32 dword_offset;
        u32 num_dwords;
    };

    PFN_SrtWalker walker_func{};
    size_t walker_func_size{};
    u32 flattened_bufsize_dw = 16; // NumUserDataRegs

    void Serialize(Serialization::Archive& ar) const;
    bool Deserialize(Serialization::Archive& ar);
};

} // namespace Shader
