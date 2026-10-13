// SPDX-FileCopyrightText: Copyright 2024 shadPS4 Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <utility>
#include "common/assert.h"
#include "common/types.h"
#include "core/address_space.h"
#include "video_core/page_manager.h"

namespace VideoCore {

// gow3: the page manager's per-page watcher counts, in a header of their own so the
// protection rule below is unit-tested (tests/test_page_state.cpp).
struct PageState {
    u8 num_write_watchers;
    u8 num_read_watchers;

    Core::MemoryPermission WritePerm() const noexcept {
        return num_write_watchers == 0 ? Core::MemoryPermission::Write
                                       : Core::MemoryPermission::None;
    }

    Core::MemoryPermission ReadPerm() const noexcept {
        return num_read_watchers == 0 ? Core::MemoryPermission::Read
                                      : Core::MemoryPermission::None;
    }

    Core::MemoryPermission Perms() const noexcept {
        // gow3: a pending readback must block writes too, until its GPU version reaches RAM.
        return num_read_watchers != 0 ? Core::MemoryPermission::None
                                     : ReadPerm() | WritePerm();
    }

    template <bool is_read>
    u8 GetPage() const {
        if constexpr (is_read) {
            return num_read_watchers;
        } else {
            return num_write_watchers;
        }
    }

    constexpr Core::MemoryPermission Update(PageOp write_op, bool update_write = true,
                                            PageOp read_op = PageOp::None,
                                            bool update_read = false) {
        if (update_read) {
            if (read_op == PageOp::Track) {
                ASSERT_MSG(num_read_watchers < 255, "Too many watchers");
            } else if (read_op == PageOp::Untrack) {
                ASSERT_MSG(num_read_watchers > 0, "Not enough watchers");
            }
            num_read_watchers += std::to_underlying(read_op);
        }
        if (update_write) {
            if (write_op == PageOp::Track) {
                ASSERT_MSG(num_write_watchers < 255, "Too many watchers");
            } else if (write_op == PageOp::Untrack) {
                ASSERT_MSG(num_write_watchers > 0, "Not enough watchers");
            }
            num_write_watchers += std::to_underlying(write_op);
        }
        return Perms();
    }

    /// gow3: the old interface (kept for the test): +1/-1 on one of the counts.
    template <s32 delta, bool is_read>
    u8 AddDelta() {
        constexpr auto op = delta == 1 ? PageOp::Track : delta == -1 ? PageOp::Untrack : PageOp::None;
        if constexpr (is_read) {
            Update(PageOp::None, false, op, true);
        } else {
            Update(op, true);
        }
        return GetPage<is_read>();
    }
};

} // namespace VideoCore
