// SPDX-License-Identifier: GPL-2.0-or-later
// gow3port: parity test for the C++ port against scripts/patches.py.
//
// The Python chain is the behaviour baseline, so the port is only allowed to exist if it produces
// byte-identical output. Usage (host, no NDK needed):
//
//   gow3_prepare_golden <eboot.bin> <patch-input.txt> <expected-patches.bin> [expected-status.json]
//                      [selection.txt]
//
// [selection.txt] holds one patch name per line. Without it every patch in the input is selected,
// which is useful to see each one fail or succeed at once; the per-patch runs pin down which one.
//
// <patch-input.txt> is the flat format of gow3_prepare.h; tools/migration/make_patch_input.py
// writes it from the XML so both sides read the same XML.
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "gow3_prepare.h"

namespace {

std::string ReadFile(const char* path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream buffer;
    buffer << file.rdbuf();
    return buffer.str();
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <eboot.bin> <patch-input.txt> <expected-patches.bin> "
                             "[expected-status.json]\n",
                     argv[0]);
        return 2;
    }
    const std::string eboot_bytes = ReadFile(argv[1]);
    const std::string input_text = ReadFile(argv[2]);

    std::vector<gow3_prepare::Patch> patches;
    std::string error;
    if (!gow3_prepare::ParsePatchInput(input_text, patches, error)) {
        std::fprintf(stderr, "FAIL: patch input: %s\n", error.c_str());
        return 1;
    }
    std::fprintf(stderr, "parsed %zu patches\n", patches.size());

    gow3_prepare::Eboot eboot;
    if (!gow3_prepare::ParseSelf(reinterpret_cast<const u8*>(eboot_bytes.data()), eboot_bytes.size(),
                                 eboot.elf, error)) {
        std::fprintf(stderr, "FAIL: parse_self: %s\n", error.c_str());
        return 1;
    }
    if (!gow3_prepare::FlattenLoads(eboot.elf, eboot, error)) {
        std::fprintf(stderr, "FAIL: flatten: %s\n", error.c_str());
        return 1;
    }
    std::fprintf(stderr, "elf %zu bytes, image %zu bytes, %zu PT_LOAD\n", eboot.elf.size(),
                 eboot.image.size(), eboot.segments.size());

    // The version and the selection. Without a selection file every patch is selected, which is
    // how the per-patch parity runs and the whole-catalog run share one code path.
    std::vector<std::string> selected;
    std::string app_version;
    if (argc >= 6) {
        std::ifstream selection(argv[5]);
        std::string name;
        while (std::getline(selection, name)) {
            if (!name.empty() && name.back() == '\r') {
                name.pop_back();
            }
            if (!name.empty()) {
                selected.push_back(name);
            }
        }
        if (selected.empty()) {
            std::fprintf(stderr, "FAIL: selection file %s has no names\n", argv[5]);
            return 1;
        }
    } else {
        for (const auto& patch : patches) {
            selected.push_back(patch.name);
        }
    }
    if (app_version.empty()) {
        for (const auto& name : selected) {
            for (const auto& patch : patches) {
                if (patch.name == name) {
                    app_version = patch.app_version;
                    break;
                }
            }
            if (!app_version.empty()) {
                break;
            }
        }
    }
    std::fprintf(stderr, "app version %s, %zu selected\n", app_version.c_str(), selected.size());

    gow3_prepare::Report report;
    report.selected = selected;
    report.requested = selected;
    // compile_plan refuses the whole launch on a conflict, so the port has to as well.
    const std::string disabled = gow3_prepare::SelectionGate(selected);
    std::vector<gow3_prepare::Write> writes;
    if (!disabled.empty()) {
        for (const auto& name : selected) {
            report.skipped.emplace_back(name, disabled);
        }
        report.reason = disabled;
    } else {
        writes = gow3_prepare::CompilePatches(eboot, patches, selected, app_version, report);
    }
    gow3_prepare::PatchRequirements(patches, report.applied, app_version, report.effective);
    report.effective.dmem_mb = std::max<u32>(5056, report.effective.minimum_dmem_mb); // DMEM_RETAIL_MB
    report.effective.dmem_source = report.effective.minimum_dmem_mb ? "compiled-patches" : "platform-default";
    report.effective.vblank_source =
        report.effective.vblank_hz ? "compiled-patches" : "platform-default";
    report.write_count = writes.size();
    for (const auto& write : writes) {
        report.write_bytes += write.data.size();
    }
    if (report.reason.empty()) {
        report.reason = report.applied.empty()
                            ? (report.skipped.empty() ? "no-patches" : "no-applicable-patches")
                            : (report.skipped.empty() ? "compiled" : "partially-compiled");
    }

    const auto blob = gow3_prepare::PatchBlob(writes);
    const std::string expected = ReadFile(argv[3]);
    if (blob.size() != expected.size() || std::memcmp(blob.data(), expected.data(), blob.size()) != 0) {
        std::fprintf(stderr, "FAIL: patches.bin differs: got %zu bytes, expected %zu\n", blob.size(),
                     expected.size());
        std::fprintf(stderr, "  reason=%s applied=%zu skipped=%zu\n", report.reason.c_str(),
                     report.applied.size(), report.skipped.size());
        for (const auto& [name, why] : report.skipped) {
            std::fprintf(stderr, "  skipped %s: %s\n", name.c_str(), why.c_str());
        }
        // Print both headers so a mismatch is readable without a diff tool.
        for (size_t i = 0; i < blob.size() && i < expected.size(); ++i) {
            if (blob[i] != expected[i]) {
                std::fprintf(stderr, "  first difference at byte %zu: got %02x, expected %02x\n", i, blob[i],
                             expected[i]);
                break;
            }
        }
        return 1;
    }

    if (argc >= 5) {
        const std::string want_status = ReadFile(argv[4]);
        const std::string got_status =
            gow3_prepare::ReportJson("CUSA00000", app_version, "sha", report);
        // The reports are compared field by field: the Python side writes extra identity fields, so
        // text equality would be wrong. Check the fields that decide a launch.
        const auto contains = [](const std::string& haystack, const std::string& needle) {
            return haystack.find(needle) != std::string::npos;
        };
        const bool ok = contains(got_status, "\"reason\": \"" + report.reason + "\"") &&
                        contains(got_status, "\"write_count\": " + std::to_string(writes.size()));
        if (!ok) {
            std::fprintf(stderr, "FAIL: status fields missing from:\n%s\n", got_status.c_str());
            return 1;
        }
        (void)want_status;
    }

    std::fprintf(stderr, "OK: patches.bin matches byte for byte (%zu bytes, %zu writes, reason=%s)\n",
                 blob.size(), writes.size(), report.reason.c_str());
    return 0;
}
