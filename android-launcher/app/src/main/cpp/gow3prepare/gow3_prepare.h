// SPDX-License-Identifier: GPL-2.0-or-later
// gow3port: the game preparation the Bionic runtime needs, before the game starts.
//
// This is a C++ port of scripts/prepare.py (PS4 SELF -> ELF) and scripts/patches.py (the
// selected XML patches -> one G3PATCH2 blob), so the launcher can produce the runtime's inputs
// on the device instead of needing Python on a host. scripts/patches.py stays the reference:
// tools/migration/gow3_prepare_golden.c must produce byte-identical patches.bin for the same
// input, and that test is what keeps the two implementations honest.
//
// Deliberately free of XML and JSON parsers: the launcher already parses the patch XML with the
// platform DOM for its own UI, and passes the result here in the flat text format read by
// ParsePatchInput(). The report is produced as JSON text because only writing it is needed.
#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

// The runtime uses shadPS4's u8/u32/u64 aliases; this library is built outside that tree (it has
// to compile for the launcher on its own), so it uses the standard fixed-width types.
using u8 = std::uint8_t;
using u16 = std::uint16_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;

namespace gow3_prepare {

/// One byte write at an image offset (what G3PATCH2 carries).
struct Write {
    u64 offset{};
    std::vector<u8> data;
};

/// A patch site as it comes from the XML, flattened by the launcher.
struct Line {
    std::string type;              ///< bytes | bytes16 | bytes32 | bytes64 | float32 | float64 | utf8 | utf16
    std::string value;             ///< hex digits for the numeric types, text for utf8/utf16
    std::string pattern;           ///< hex bytes with "??" wildcards; empty when the line is a Target line
    u64 at{};                      ///< offset of the write inside the pattern match
    u64 address{};                 ///< the reference build's address (vaddr); only used without an image
    bool has_address{};
    std::string target;            ///< name of the Target this line writes, empty when it patches a site
};

/// A named Target a patch refers to. Either a whole line's site, or a place found by signature.
struct Target {
    std::string name;
    long line{-1};                 ///< index into Patch::lines, -1 when located by signature
    std::string pattern;           ///< signature of the target itself
    u64 at{};
    bool ref{};                    ///< the operand at the located offset is a rel32 to follow
    u64 tail{};                    ///< bytes between the operand and the end of the instruction
};

struct Patch {
    std::string name;
    std::string note;              ///< the Note attribute; "DMEM to N MB" / "VBlank to N FPS" are read from it
    std::string app_version;       ///< AppVer; only a matching patch is compiled
    std::string app_elf{"eboot.bin"};
    std::vector<Line> lines;
    std::vector<Target> targets;
};

/// The launch parameters the reference derives from the notes of the patches that compiled.
struct Effective {
    u32 dmem_mb{};                 ///< direct memory MiB the compiled patches need
    u32 minimum_dmem_mb{};
    u32 vblank_hz{};
    u64 device_mem_mb{};           ///< the device's RAM when known, 0 otherwise
    bool budget_checked{};
    std::string dmem_source;       ///< platform-default | compiled-patches | override
    std::string vblank_source;
    std::string render_resolution;
};

/// Why a patch was not compiled, or the report's overall reason.
struct Report {
    std::string reason;
    std::vector<std::string> requested;
    std::vector<std::string> selected;
    std::vector<std::string> applied;
    std::vector<std::pair<std::string, std::string>> skipped; ///< name, reason
    Effective effective;
    size_t write_count{};
    size_t write_bytes{};
};

/// The ELF image of an eboot: the flat bytes a patch signature is searched in, and the PT_LOAD
/// ranges a write must fall inside. Mirrors prepare.parse_self's first two outputs.
struct Eboot {
    std::vector<u8> elf;           ///< the reconstructed ELF file
    std::vector<u8> image;         ///< PT_LOAD contents placed at their virtual addresses
    std::vector<std::pair<u64, u64>> segments; ///< (vaddr, vaddr + memsz) per PT_LOAD
};

/// parse_self: a plaintext PS4 SELF or an unpacked little-endian x86-64 ELF (upstream #552)
/// into a readable ELF image. false + error on anything unsupported (encrypted or compressed
/// SELF segments included), the same set prepare.py refuses.
bool ParseSelf(const u8* data, size_t size, std::vector<u8>& elf, std::string& error);

/// eboot_image / eboot_segments of an ELF produced by ParseSelf.
bool FlattenLoads(const std::vector<u8>& elf, Eboot& out, std::string& error);

/// compile_plan's selection gate, before anything is compiled: at most one Resolution Patch and at
/// most one Frame Rate Patch may be on, and a resolution patch displaces the patches it replaces
/// (REPLACED_BY_EXCLUSIVE). Returns the disabled reason, empty when the selection may compile.
std::string SelectionGate(const std::vector<std::string>& selected);

/// compile_plan: the writes of the selected patches against the image. A patch whose sites are
/// not all found is left out whole, with the reason in report->skipped - never half applied.
std::vector<Write> CompilePatches(const Eboot& eboot, const std::vector<Patch>& patches,
                                 const std::vector<std::string>& selected, const std::string& app_version,
                                 Report& report);

/// patch_requirements: (DMEM MiB, VBlank Hz) the notes of these patches ask for, 0 when none.
void PatchRequirements(const std::vector<Patch>& patches, const std::vector<std::string>& names,
                       const std::string& app_version, Effective& effective);

/// patch_blob: magic, image base, then (offset, length, bytes) per write - what probe.c reads.
std::vector<u8> PatchBlob(const std::vector<Write>& writes);

/// G3CONT01: the AppContent profile probe.c validates before the run. The layout is
/// <8s5I>: magic, the SKU (full=3, trial=1) and the game's four USER_DEFINED_PARAM values, which
/// come from param.sfo - the launcher reads those (scripts/content_profile.py does the same).
std::vector<u8> ContentProfile(u32 sku, const u32 user_params[4]);

/// The flat text format the launcher produces and ParsePatchInput reads. One P block per patch,
/// L lines per patch in order, T targets. Empty fields are a single '-'.
///   P <name>
///   N <note>
///   V <app version>
///   E <app elf>
///   L <type> <address|-> <value> <at|-> <target|-> <pattern...>
///   T <name> <line|-> <at|-> <ref 0|1> <tail|-> <pattern...>
bool ParsePatchInput(const std::string& text, std::vector<Patch>& out, std::string& error);

/// patch_status.json, the report the launcher shows and run-bionic.sh's identity check reads.
std::string ReportJson(const std::string& title_id, const std::string& app_version,
                       const std::string& eboot_sha256, const Report& report);

} // namespace gow3_prepare
