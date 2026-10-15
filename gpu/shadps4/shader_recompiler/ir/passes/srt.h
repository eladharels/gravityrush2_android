// SPDX-FileCopyrightText: Copyright 2024 shadPS4 Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <boost/container/set.hpp>
#include <boost/container/small_vector.hpp>
#include "common/types.h"

namespace Serialization {
struct Archive;
}

namespace Shader {

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
/// Rereads the flattened dwords whose guest source is GPU-written (src[i] is 0 for none).
using PFN_SrtFixup = void (*)(const u64* src, u32* flat, size_t count);

struct SrtWalkCtx {
    PFN_SrtLoadPtr load_ptr; // [r8 + 0]
    u64* src_table;          // [r8 + 8]
};

void SetSrtWalkerHooks(PFN_SrtLoadPtr load_ptr, PFN_SrtFixup fixup);
/// Runs a walker. `sync` false: GPU-written bytes keep the guest copy (no readback).
void RunSrtWalker(PFN_SrtWalker walker, const u32* user_data, u32* flat, size_t flat_dw,
                  bool sync = true);
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
