// SPDX-License-Identifier: GPL-2.0-or-later
// gow3port: see gow3_prepare.h. Port of scripts/prepare.py and scripts/patches.py.
#include "gow3_prepare.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <sstream>

namespace gow3_prepare {
namespace {

constexpr u64 EbootBase = 0x400000;
constexpr u32 DmemRetailMb = 5056; // runtime_memory.c POOL_SIZE default, as in patches.py
constexpr size_t MaxImage = 512 * 1024 * 1024;

template <typename T>
T Read(const u8* p) {
    T v{};
    std::memcpy(&v, p, sizeof(T));
    return v;
}

bool InRange(size_t offset, size_t length, size_t size) {
    return offset <= size && length <= size - offset;
}

/// prepare.span: bytes [offset, offset+size) of a buffer, or a refusal.
bool Span(const std::vector<u8>& data, size_t offset, size_t size, const u8*& out, std::string& error) {
    if (!InRange(offset, size, data.size())) {
        error = "out of range";
        return false;
    }
    out = data.data() + offset;
    return true;
}

struct ProgramHeader {
    u32 type{}, flags{};
    u64 offset{}, vaddr{}, paddr{}, filesz{}, memsz{}, align{};
};

} // namespace

// ---------------------------------------------------------------- prepare.parse_self
// Public API (see gow3_prepare.h), so it lives outside the anonymous namespace.

bool ParseSelf(const u8* data, size_t size, std::vector<u8>& elf, std::string& error) {
    std::vector<u8> buffer(data, data + size);
    const u8* magic = nullptr;
    if (!Span(buffer, 0, 4, magic, error)) {
        return false;
    }
    static const u8 kElfMagic[4] = {0x7f, 'E', 'L', 'F'};
    static const u8 kSelfMagic[4] = {'O', 0x15, '=', 0x1d};
    // 上游 #552：除 SELF 容器外，也接受文件偏移 0 的解包 little-endian x86-64 ELF。
    // 解包 ELF 没有 SELF 段表，段数据本就按程序头的文件偏移排列。
    const bool plain_elf = std::memcmp(magic, kElfMagic, 4) == 0;
    u16 count = 0;
    size_t base = 0;
    if (plain_elf) {
        // ELF 头在偏移 0，无需跳过 SELF 头。
    } else if (std::memcmp(magic, kSelfMagic, 4) == 0) {
        count = Read<u16>(buffer.data() + 24);
        base = 32 + static_cast<size_t>(count) * 32;
    } else {
        error = "expected PS4 SELF or ELF";
        return false;
    }
    // Elf64_Ehdr after the SELF header: e_ident[16], e_type, e_machine, e_version, e_entry,
    // e_phoff, e_shoff, e_flags, e_ehsize, e_phentsize, e_phnum, ...
    if (!InRange(base, 64, buffer.size())) {
        error = "truncated SELF header";
        return false;
    }
    const u8* eh = buffer.data() + base;
    static const u8 kElf[7] = {0x7f, 'E', 'L', 'F', 0x02, 0x01, 0x01};
    if (std::memcmp(eh, kElf, 7) != 0 || Read<u16>(eh + 18) != 62) {
        error = "expected little-endian x86-64 ELF";
        return false;
    }
    // e_phentsize (54) is what the reference checks, not e_ehsize: a program header here is 56.
    const u16 phentsize = Read<u16>(eh + 54);
    const u16 phnum = Read<u16>(eh + 56);
    if (phentsize != 56 || phnum == 0 || phnum > 255) {
        error = "unsupported program headers";
        return false;
    }
    const u64 phoff = Read<u64>(eh + 32);

    std::vector<ProgramHeader> ph;
    ph.reserve(phnum);
    for (u16 i = 0; i < phnum; ++i) {
        const size_t at = base + phoff + static_cast<size_t>(i) * 56;
        if (!InRange(at, 56, buffer.size())) {
            error = "truncated program headers";
            return false;
        }
        ProgramHeader p;
        p.type = Read<u32>(buffer.data() + at);
        p.flags = Read<u32>(buffer.data() + at + 4);
        p.offset = Read<u64>(buffer.data() + at + 8);
        p.vaddr = Read<u64>(buffer.data() + at + 16);
        p.paddr = Read<u64>(buffer.data() + at + 24);
        p.filesz = Read<u64>(buffer.data() + at + 32);
        p.memsz = Read<u64>(buffer.data() + at + 40);
        p.align = Read<u64>(buffer.data() + at + 48);
        ph.push_back(p);
    }

    const size_t header_end = static_cast<size_t>(phoff + phnum * 56);
    size_t end = header_end;
    for (const auto& p : ph) {
        end = std::max(end, static_cast<size_t>(p.offset + p.filesz));
    }
    if (end > MaxImage) {
        error = "probe image exceeds 512 MiB limit";
        return false;
    }
    if (plain_elf) {
        // 解包 ELF：先校验引用字节范围（截断的段/程序头在此被拦下），再原样截取
        // [0, end)——ELF 文件字节已经是镜像布局，无需 SELF 那样的段拼装与覆盖校验。
        if (!InRange(0, end, buffer.size())) {
            error = "truncated ELF image";
            return false;
        }
        elf.assign(buffer.data(), buffer.data() + end);
        return true;
    }
    elf.assign(end, 0);
    if (!InRange(base, header_end, buffer.size())) {
        error = "truncated SELF header";
        return false;
    }
    std::memcpy(elf.data(), buffer.data() + base, header_end);

    std::vector<std::pair<size_t, size_t>> covered{{0, header_end}};
    for (u16 i = 0; i < count; ++i) {
        const size_t at = 32 + static_cast<size_t>(i) * 32;
        if (!InRange(at, 32, buffer.size())) {
            error = "truncated SELF segment table";
            return false;
        }
        const u64 flags = Read<u64>(buffer.data() + at);
        const u64 off = Read<u64>(buffer.data() + at + 8);
        const u64 length = Read<u64>(buffer.data() + at + 16);
        const u64 mem = Read<u64>(buffer.data() + at + 24);
        // 0x800: the segment's data is stored in the SELF file (not in the ELF's own space).
        if ((flags & 0x800) == 0) {
            continue;
        }
        if (flags & 10) { // encrypted (0x2) or compressed (0x8)
            error = "encrypted/compressed SELF segment is unsupported";
            return false;
        }
        const u64 index = (flags >> 20) & 4095;
        if (index >= ph.size()) {
            error = "invalid segment index";
            return false;
        }
        const auto& p = ph[index];
        if (length != p.filesz || length != mem) {
            error = "unsupported blocked segment layout";
            return false;
        }
        if (!InRange(static_cast<size_t>(off), static_cast<size_t>(length), buffer.size()) ||
            !InRange(static_cast<size_t>(p.offset), static_cast<size_t>(length), elf.size())) {
            error = "truncated SELF segment";
            return false;
        }
        std::memcpy(elf.data() + p.offset, buffer.data() + off, static_cast<size_t>(length));
        covered.emplace_back(static_cast<size_t>(p.offset), static_cast<size_t>(p.offset + length));
    }

    for (size_t i = 0; i < ph.size(); ++i) {
        const auto& p = ph[i];
        if (!p.filesz) {
            continue;
        }
        const bool have = std::any_of(covered.begin(), covered.end(), [&](const auto& c) {
            return c.first <= p.offset && p.offset + p.filesz <= c.second;
        });
        if (have) {
            continue;
        }
        // PT_LOAD (1), PT_DYNAMIC (2) and the PS4 0x61000000 pair have to be present or the
        // dynamic loader cannot run; anything else may stay missing, as prepare.py allows.
        if (p.type == 1 || p.type == 2 || p.type == 0x61000000 || p.type == 0x61000010) {
            error = "required segment " + std::to_string(i) + " is unavailable";
            return false;
        }
    }
    return true;
}

namespace {

// ---------------------------------------------------------------- patches.parse_pattern / find_pattern

/// "-1" is a wildcard byte ("??").
std::vector<int> ParsePattern(const std::string& text) {
    std::vector<int> pattern;
    std::istringstream stream(text);
    std::string token;
    while (stream >> token) {
        if (token == "??") {
            pattern.push_back(-1);
        } else {
            pattern.push_back(static_cast<int>(std::strtoul(token.c_str(), nullptr, 16)));
        }
    }
    return pattern;
}

/// Image offsets where the pattern matches, at most `limit` of them. Anchored on the longest run
/// of fixed bytes so a mostly-wildcard signature does not scan the whole image per position.
std::vector<size_t> FindPattern(const std::vector<u8>& image, const std::vector<int>& pattern, size_t limit = 2) {
    std::vector<std::pair<size_t, size_t>> runs;
    size_t start = SIZE_MAX;
    for (size_t i = 0; i <= pattern.size(); ++i) {
        const bool fixed = i < pattern.size() && pattern[i] >= 0;
        if (fixed && start == SIZE_MAX) {
            start = i;
        }
        if (!fixed && start != SIZE_MAX) {
            runs.emplace_back(start, i);
            start = SIZE_MAX;
        }
    }
    if (runs.empty()) {
        return {};
    }
    const auto best = std::max_element(runs.begin(), runs.end(), [](const auto& a, const auto& b) {
        return (a.second - a.first) < (b.second - b.first);
    });
    const size_t anchor_begin = best->first;
    const size_t anchor_len = best->second - best->first;
    std::vector<u8> anchor(anchor_len);
    for (size_t i = 0; i < anchor_len; ++i) {
        anchor[i] = static_cast<u8>(pattern[anchor_begin + i]);
    }
    std::vector<size_t> found;
    if (anchor_len == 0 || pattern.size() > image.size()) {
        return found;
    }
    size_t at = 0;
    for (;;) {
        const auto it = std::search(image.begin() + static_cast<long>(at), image.end(), anchor.begin(),
                                    anchor.end());
        if (it == image.end()) {
            break;
        }
        const size_t hit = static_cast<size_t>(std::distance(image.begin(), it));
        if (found.size() < limit) {
            const size_t candidate = hit >= anchor_begin ? hit - anchor_begin : SIZE_MAX;
            if (candidate != SIZE_MAX && candidate + pattern.size() <= image.size()) {
                bool all = true;
                for (size_t i = 0; i < pattern.size(); ++i) {
                    if (pattern[i] >= 0 && image[candidate + i] != static_cast<u8>(pattern[i])) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    found.push_back(candidate);
                }
            }
        }
        at = hit + 1;
        if (at >= image.size()) {
            break;
        }
    }
    return found;
}

/// patches.locate: where a site is in this build. A signature must match exactly once.
bool Locate(const std::vector<u8>& image, const Line& line, u64& offset, std::string& error) {
    if (line.pattern.empty()) {
        error = "missing signature";
        return false;
    }
    const auto found = FindPattern(image, ParsePattern(line.pattern));
    if (found.empty()) {
        error = "signature not found";
        return false;
    }
    if (found.size() > 1) {
        error = "signature found more than once";
        return false;
    }
    offset = static_cast<u64>(found[0]) + line.at;
    return true;
}

/// patches.encode: the Value of a line as bytes.
bool Encode(const Line& line, std::vector<u8>& out, std::string& error) {
    const std::string& type = line.type;
    const auto hex = [](const std::string& in, std::vector<u8>& dst) {
        std::string clean;
        for (char c : in) {
            if (c != ' ') {
                clean.push_back(c);
            }
        }
        if (clean.size() % 2) {
            return false;
        }
        for (size_t i = 0; i < clean.size(); i += 2) {
            dst.push_back(static_cast<u8>(std::strtoul(clean.substr(i, 2).c_str(), nullptr, 16)));
        }
        return true;
    };
    if (type == "bytes") {
        return hex(line.value, out) ? true : (error = "bad hex value", false);
    }
    if (type == "bytes16" || type == "bytes32" || type == "bytes64") {
        const size_t width = std::stoul(type.substr(5)) / 8;
        const auto value = std::strtoull(line.value.c_str(), nullptr, 0);
        for (size_t i = 0; i < width; ++i) {
            out.push_back(static_cast<u8>((value >> (8 * i)) & 0xff));
        }
        return true;
    }
    if (type == "float32") {
        const auto value = std::strtof(line.value.c_str(), nullptr);
        u8 raw[4];
        std::memcpy(raw, &value, 4);
        out.assign(raw, raw + 4);
        return true;
    }
    if (type == "float64") {
        const auto value = std::strtod(line.value.c_str(), nullptr);
        u8 raw[8];
        std::memcpy(raw, &value, 8);
        out.assign(raw, raw + 8);
        return true;
    }
    if (type == "utf8") {
        out.assign(line.value.begin(), line.value.end());
        out.push_back(0);
        return true;
    }
    if (type == "utf16") {
        for (char c : line.value) {
            out.push_back(static_cast<u8>(c));
            out.push_back(0);
        }
        out.push_back(0);
        out.push_back(0);
        return true;
    }
    error = "unsupported patch type '" + type + "'";
    return false;
}

/// patches.fill: Value with each {rel:NAME:TAIL} replaced by the rel32 to that target, measured
/// from the end of the instruction (TAIL bytes after the operand).
bool Fill(const std::string& value, u64 at,
          const std::vector<std::pair<std::string, u64>>& targets, std::vector<u8>& out, std::string& error) {
    std::string clean;
    for (char c : value) {
        if (c != ' ') {
            clean.push_back(c);
        }
    }
    size_t i = 0;
    while (i < clean.size()) {
        if (clean[i] != '{') {
            const size_t next = clean.find('{', i);
            const std::string part = clean.substr(i, next == std::string::npos ? std::string::npos : next - i);
            if (part.size() % 2) {
                error = "bad hex value";
                return false;
            }
            for (size_t k = 0; k < part.size(); k += 2) {
                out.push_back(static_cast<u8>(std::strtoul(part.substr(k, 2).c_str(), nullptr, 16)));
            }
            if (next == std::string::npos) {
                break;
            }
            i = next;
            continue;
        }
        const size_t end = clean.find('}', i);
        if (end == std::string::npos) {
            error = "unterminated {rel:} placeholder";
            return false;
        }
        // {rel:NAME:TAIL}: three colon-separated parts, the leading "rel" is the marker.
        const std::string body = clean.substr(i + 1, end - i - 1);
        const size_t first = body.find(':');
        const size_t second = first == std::string::npos ? std::string::npos : body.find(':', first + 1);
        if (first == std::string::npos || second == std::string::npos) {
            error = "malformed {rel:NAME:TAIL}";
            return false;
        }
        const std::string name = body.substr(first + 1, second - first - 1);
        const u64 tail = std::strtoull(body.substr(second + 1).c_str(), nullptr, 10);
        const auto it = std::find_if(targets.begin(), targets.end(),
                                     [&](const auto& t) { return t.first == name; });
        if (it == targets.end()) {
            error = "unknown target '" + name + "'";
            return false;
        }
        const auto disp = static_cast<int64_t>(it->second - (at + out.size() + 4 + tail));
        for (int b = 0; b < 4; ++b) {
            out.push_back(static_cast<u8>((static_cast<u64>(disp) >> (8 * b)) & 0xff));
        }
        i = end + 1;
    }
    return true;
}

bool InsideSegments(const std::vector<std::pair<u64, u64>>& segments, u64 offset, u64 length) {
    for (const auto& [start, end] : segments) {
        if (offset >= start && length <= end - start && offset - start <= end - start) {
            // offset + length must stay inside [start, end)
            if (offset - start + length <= end - start) {
                return true;
            }
        }
    }
    return false;
}

} // namespace

// ---------------------------------------------------------------- public

bool FlattenLoads(const std::vector<u8>& elf, Eboot& out, std::string& error) {
    if (!InRange(0x36, 4, elf.size())) {
        error = "not an ELF";
        return false;
    }
    const u16 phentsize = Read<u16>(elf.data() + 0x36);
    const u16 phnum = Read<u16>(elf.data() + 0x38);
    const u64 phoff = Read<u64>(elf.data() + 0x20);
    struct Load {
        u64 vaddr, offset, filesz;
    };
    std::vector<Load> loads;
    for (u16 i = 0; i < phnum; ++i) {
        const size_t at = static_cast<size_t>(phoff) + static_cast<size_t>(i) * phentsize;
        if (!InRange(at, 56, elf.size())) {
            error = "truncated program headers";
            return false;
        }
        const u32 type = Read<u32>(elf.data() + at);
        const u64 offset = Read<u64>(elf.data() + at + 8);
        const u64 vaddr = Read<u64>(elf.data() + at + 16);
        const u64 filesz = Read<u64>(elf.data() + at + 32);
        const u64 memsz = Read<u64>(elf.data() + at + 40);
        if (type != 1) { // PT_LOAD
            continue;
        }
        loads.push_back(Load{vaddr, offset, filesz});
        out.segments.emplace_back(vaddr, vaddr + memsz);
    }
    if (loads.empty()) {
        error = "no loadable segments";
        return false;
    }
    size_t image_size = 0;
    for (const auto& load : loads) {
        image_size = std::max(image_size, static_cast<size_t>(load.vaddr + load.filesz));
    }
    if (image_size > MaxImage) {
        error = "probe image exceeds 512 MiB limit";
        return false;
    }
    out.image.assign(image_size, 0);
    for (const auto& load : loads) {
        if (!InRange(static_cast<size_t>(load.offset), static_cast<size_t>(load.filesz), elf.size())) {
            error = "loadable segment outside the ELF";
            return false;
        }
        std::memcpy(out.image.data() + load.vaddr, elf.data() + load.offset,
                    static_cast<size_t>(load.filesz));
    }
    return true;
}

std::string SelectionGate(const std::vector<std::string>& selected) {
    // Mirrors compile_plan: more than one patch of an exclusive family is a conflict, and nothing
    // is compiled at all rather than picking one.
    for (const char* prefix : {"Resolution Patch", "Frame Rate Patch"}) {
        const size_t count = static_cast<size_t>(std::count_if(
            selected.begin(), selected.end(),
            [&](const std::string& name) { return name.rfind(prefix, 0) == 0; }));
        if (count > 1) {
            return "patch-conflict";
        }
    }
    return {};
}

std::vector<Write> CompilePatches(const Eboot& eboot, const std::vector<Patch>& patches,
                                 const std::vector<std::string>& selected, const std::string& app_version,
                                 Report& report) {
    const auto& image = eboot.image;
    const auto& segments = eboot.segments;
    std::vector<Write> writes;

    std::vector<const Patch*> chosen;
    for (const auto& name : selected) {
        const auto it = std::find_if(patches.begin(), patches.end(),
                                     [&](const Patch& p) { return p.name == name; });
        if (it != patches.end()) {
            chosen.push_back(&*it);
        }
    }

    for (const Patch* patch : chosen) {
        if (!app_version.empty() && patch->app_version != app_version) {
            report.skipped.emplace_back(patch->name, "patch not found for app version " + app_version);
            continue;
        }
        if (patch->app_elf != "eboot.bin") {
            report.skipped.emplace_back(patch->name, "patch not found for app version " + app_version);
            continue;
        }
        if (patch->lines.empty()) {
            report.skipped.emplace_back(patch->name, "empty patch");
            continue;
        }
        // Every site first: a patch is committed whole, so a later line failing cannot leave a
        // half-applied patch behind.
        std::vector<u64> sites;
        sites.reserve(patch->lines.size());
        bool failed = false;
        std::string error;
        for (const auto& line : patch->lines) {
            if (!line.target.empty()) {
                sites.push_back(SIZE_MAX); // resolved from the targets below
                continue;
            }
            u64 offset = 0;
            if (!Locate(image, line, offset, error)) {
                failed = true;
                break;
            }
            sites.push_back(offset);
        }
        std::vector<std::pair<std::string, u64>> targets;
        if (!failed) {
            for (const auto& target : patch->targets) {
                u64 offset = 0;
                if (target.line >= 0) {
                    if (static_cast<size_t>(target.line) >= sites.size() ||
                        sites[static_cast<size_t>(target.line)] == SIZE_MAX) {
                        error = "invalid target line reference";
                        failed = true;
                        break;
                    }
                    offset = sites[static_cast<size_t>(target.line)];
                } else {
                    Line probe;
                    probe.pattern = target.pattern;
                    probe.at = target.at;
                    if (!Locate(image, probe, offset, error)) {
                        failed = true;
                        break;
                    }
                    if (target.ref) {
                        if (offset + 4 > image.size()) {
                            error = "reference operand outside the image";
                            failed = true;
                            break;
                        }
                        const int32_t disp = Read<int32_t>(image.data() + offset);
                        offset = offset + 4 + target.tail + static_cast<u64>(static_cast<int64_t>(disp));
                    }
                }
                targets.emplace_back(target.name, offset);
            }
        }
        if (!failed) {
            for (const auto& target : targets) {
                const u64 target_offset = target.second;
                const bool inside = std::any_of(segments.begin(), segments.end(), [&](const auto& range) {
                    return target_offset >= range.first && target_offset < range.second;
                });
                if (!inside) {
                    error = "target outside the eboot";
                    failed = true;
                    break;
                }
            }
        }
        // Target lines borrow the site's offset of the line they name.
        std::vector<u64> resolved = sites;
        if (!failed) {
            for (size_t i = 0; i < patch->lines.size(); ++i) {
                const auto& name = patch->lines[i].target;
                if (name.empty()) {
                    continue;
                }
                const auto it = std::find_if(targets.begin(), targets.end(),
                                             [&](const auto& t) { return t.first == name; });
                if (it == targets.end()) {
                    error = "unknown target '" + name + "'";
                    failed = true;
                    break;
                }
                resolved[i] = it->second;
            }
        }
        std::vector<Write> ours;
        if (!failed) {
            for (size_t i = 0; i < patch->lines.size() && !failed; ++i) {
                const auto& line = patch->lines[i];
                const u64 offset = resolved[i];
                std::vector<u8> data;
                if (line.value.find('{') != std::string::npos) {
                    if (!Fill(line.value, offset, targets, data, error)) {
                        failed = true;
                    }
                } else if (!Encode(line, data, error)) {
                    failed = true;
                }
                if (failed) {
                    break;
                }
                if (data.empty() || data.size() > 4096 || !InsideSegments(segments, offset, data.size())) {
                    char buffer[96];
                    std::snprintf(buffer, sizeof(buffer), "address 0x%llx or write length is outside the eboot",
                                  static_cast<unsigned long long>(offset + EbootBase));
                    error = buffer;
                    failed = true;
                    break;
                }
                ours.push_back(Write{offset, std::move(data)});
            }
        }
        if (failed) {
            report.skipped.emplace_back(patch->name, error.empty() ? "patch failed" : error);
            continue;
        }
        report.applied.push_back(patch->name);
        writes.insert(writes.end(), std::make_move_iterator(ours.begin()), std::make_move_iterator(ours.end()));
    }
    return writes;
}

void PatchRequirements(const std::vector<Patch>& patches, const std::vector<std::string>& names,
                       const std::string& app_version, Effective& effective) {
    u32 dmem = 0;
    u32 vblank = 0;
    bool any_dmem = false;
    for (const auto& patch : patches) {
        if (std::find(names.begin(), names.end(), patch.name) == names.end()) {
            continue;
        }
        if (!app_version.empty() && patch.app_version != app_version) {
            continue;
        }
        // The note is prose: "Requires setting DMEM to 2048 MB or more" and "VBlank to 60 FPS".
        const auto scan = [&](const char* label, u32& slot, bool& seen) {
            const std::string needle = std::string(label) + " to ";
            size_t at = 0;
            while ((at = patch.note.find(needle, at)) != std::string::npos) {
                size_t p = at + needle.size();
                size_t digits = 0;
                while (p + digits < patch.note.size() &&
                       std::isdigit(static_cast<unsigned char>(patch.note[p + digits]))) {
                    ++digits;
                }
                if (digits) {
                    slot = std::max(slot, static_cast<u32>(std::strtoul(patch.note.substr(p, digits).c_str(),
                                                                         nullptr, 10)));
                    seen = true;
                }
                at = p;
            }
        };
        scan("DMEM", dmem, any_dmem);
        bool any_vblank = false;
        scan("VBlank", vblank, any_vblank);
    }
    effective.minimum_dmem_mb = any_dmem ? DmemRetailMb + dmem : 0;
    effective.vblank_hz = vblank;
}

std::vector<u8> PatchBlob(const std::vector<Write>& writes) {
    std::vector<u8> blob;
    const auto push = [&blob](auto value) {
        u8 raw[sizeof(value)];
        std::memcpy(raw, &value, sizeof(value));
        blob.insert(blob.end(), raw, raw + sizeof(value));
    };
    static const char magic[8] = {'G', '3', 'P', 'A', 'T', 'C', 'H', '2'};
    blob.insert(blob.end(), magic, magic + 8);
    push(static_cast<u64>(EbootBase));
    push(static_cast<u64>(writes.size()));
    for (const auto& write : writes) {
        push(static_cast<u64>(write.offset));
        push(static_cast<u64>(write.data.size()));
        blob.insert(blob.end(), write.data.begin(), write.data.end());
    }
    return blob;
}

std::vector<u8> ContentProfile(u32 sku, const u32 user_params[4]) {
    // <8s5I>: magic, the SKU and the four user-defined parameters from param.sfo.
    std::vector<u8> data;
    data.reserve(28);
    const char magic[8] = {'G', '3', 'C', 'O', 'N', 'T', '0', '1'};
    data.insert(data.end(), magic, magic + 8);
    const u32 values[5] = {sku, user_params[0], user_params[1], user_params[2], user_params[3]};
    for (u32 value : values) {
        const u8 raw[4] = {static_cast<u8>(value & 0xff), static_cast<u8>((value >> 8) & 0xff),
                           static_cast<u8>((value >> 16) & 0xff), static_cast<u8>((value >> 24) & 0xff)};
        data.insert(data.end(), raw, raw + 4);
    }
    return data;
}

bool ParsePatchInput(const std::string& text, std::vector<Patch>& out, std::string& error) {
    std::istringstream stream(text);
    std::string line;
    Patch current;
    bool in_patch = false;
    while (std::getline(stream, line)) {
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        if (line.empty()) {
            continue;
        }
        const char kind = line[0];
        const std::string rest = line.size() > 1 ? line.substr(1) : std::string();
        // A field is "-" when empty. Everything after the first space is free text (the note).
        const auto field = [](const std::string& s, std::size_t& pos) {
            while (pos < s.size() && s[pos] == ' ') {
                ++pos;
            }
            const size_t end = s.find(' ', pos);
            const std::string value = s.substr(pos, end == std::string::npos ? std::string::npos : end - pos);
            pos = end == std::string::npos ? s.size() : end;
            return value == "-" ? std::string() : value;
        };
        switch (kind) {
        case 'P': {
            if (in_patch) {
                out.push_back(std::move(current));
            }
            current = Patch{};
            // A patch name is prose ("Resolution Patch - 720p"): it takes the rest of the line.
            size_t pos = 0;
            while (pos < rest.size() && rest[pos] == ' ') {
                ++pos;
            }
            current.name = rest.substr(pos);
            in_patch = true;
            break;
        }
        case 'N': {
            size_t pos = 0;
            while (pos < rest.size() && rest[pos] == ' ') {
                ++pos;
            }
            std::string note = rest.substr(pos);
            for (auto& c : note) {
                if (c == '\\' && pos + 1 < rest.size()) {
                    // handled below by a second pass
                }
            }
            // \n and \\ are the only escapes the launcher emits.
            std::string unescaped;
            for (size_t i = 0; i < note.size(); ++i) {
                if (note[i] == '\\' && i + 1 < note.size() && (note[i + 1] == 'n' || note[i + 1] == '\\')) {
                    unescaped.push_back(note[i + 1] == 'n' ? '\n' : '\\');
                    ++i;
                } else {
                    unescaped.push_back(note[i]);
                }
            }
            current.note = std::move(unescaped);
            break;
        }
        case 'V': {
            size_t pos = 0;
            current.app_version = field(rest, pos);
            break;
        }
        case 'E': {
            size_t pos = 0;
            const std::string elf = field(rest, pos);
            current.app_elf = elf.empty() ? "eboot.bin" : elf;
            break;
        }
        case 'L': {
            size_t pos = 0;
            Line l;
            l.type = field(rest, pos);
            const std::string address = field(rest, pos);
            l.value = field(rest, pos);
            const std::string at = field(rest, pos);
            l.target = field(rest, pos);
            // The pattern takes the rest of the line: it is hex bytes separated by spaces.
            while (pos < rest.size() && rest[pos] == ' ') {
                ++pos;
            }
            l.pattern = rest.substr(pos);
            if (l.pattern == "-") {
                l.pattern.clear();
            }
            if (!address.empty()) {
                l.has_address = true;
                l.address = std::strtoull(address.c_str(), nullptr, 0);
            }
            if (!at.empty()) {
                l.at = std::strtoull(at.c_str(), nullptr, 0);
            }
            current.lines.push_back(std::move(l));
            break;
        }
        case 'T': {
            size_t pos = 0;
            Target t;
            // Same as a patch name: the rest of the line.
            while (pos < rest.size() && rest[pos] == ' ') {
                ++pos;
            }
            const size_t name_end = rest.find(' ', pos);
            t.name = rest.substr(pos, name_end == std::string::npos ? std::string::npos : name_end - pos);
            pos = name_end == std::string::npos ? rest.size() : name_end;
            const std::string line_ref = field(rest, pos);
            const std::string at = field(rest, pos);
            const std::string ref = field(rest, pos);
            const std::string tail = field(rest, pos);
            // The pattern takes the rest of the line: it is hex bytes separated by spaces.
            while (pos < rest.size() && rest[pos] == ' ') {
                ++pos;
            }
            t.pattern = rest.substr(pos);
            if (t.pattern == "-") {
                t.pattern.clear();
            }
            t.line = line_ref.empty() ? -1 : std::strtol(line_ref.c_str(), nullptr, 10);
            if (!at.empty()) {
                t.at = std::strtoull(at.c_str(), nullptr, 10);
            }
            t.ref = ref == "1";
            if (!tail.empty()) {
                t.tail = std::strtoull(tail.c_str(), nullptr, 10);
            }
            current.targets.push_back(std::move(t));
            break;
        }
        default:
            error = std::string("unexpected line '") + kind + "' in the patch input";
            return false;
        }
    }
    if (in_patch) {
        out.push_back(std::move(current));
    }
    return true;
}

std::string ReportJson(const std::string& title_id, const std::string& app_version,
                       const std::string& eboot_sha256, const Report& report) {
    std::ostringstream json;
    const auto quote = [](const std::string& s) {
        std::string out = "\"";
        for (char c : s) {
            if (c == '"' || c == '\\') {
                out.push_back('\\');
            }
            if (c == '\n') {
                out += "\\n";
                continue;
            }
            out.push_back(c);
        }
        out.push_back('"');
        return out;
    };
    const auto list = [&](const std::vector<std::string>& values) {
        std::string out = "[";
        for (size_t i = 0; i < values.size(); ++i) {
            out += (i ? ", " : "") + quote(values[i]);
        }
        return out + "]";
    };
    json << "{\n";
    json << "  \"schema\": \"gow3.patch-status.v1\",\n";
    json << "  \"title_id\": " << quote(title_id) << ",\n";
    json << "  \"app_version\": " << quote(app_version) << ",\n";
    json << "  \"eboot_sha256\": " << quote(eboot_sha256) << ",\n";
    json << "  \"reason\": " << quote(report.reason) << ",\n";
    json << "  \"requested\": " << list(report.requested) << ",\n";
    json << "  \"selected\": " << list(report.selected) << ",\n";
    json << "  \"applied\": " << list(report.applied) << ",\n";
    json << "  \"skipped\": {";
    for (size_t i = 0; i < report.skipped.size(); ++i) {
        json << (i ? ", " : "") << quote(report.skipped[i].first) << ": " << quote(report.skipped[i].second);
    }
    json << "},\n";
    json << "  \"effective\": {\n";
    json << "    \"render_resolution\": " << quote(report.effective.render_resolution) << ",\n";
    json << "    \"engine_fps\": " << (report.effective.vblank_hz ? report.effective.vblank_hz : 60) << ",\n";
    json << "    \"vblank_hz\": " << (report.effective.vblank_hz ? report.effective.vblank_hz : 60) << ",\n";
    json << "    \"vblank_source\": " << quote(report.effective.vblank_source) << ",\n";
    json << "    \"dmem_mb\": " << (report.effective.dmem_mb ? report.effective.dmem_mb : DmemRetailMb) << ",\n";
    json << "    \"minimum_dmem_mb\": " << report.effective.minimum_dmem_mb << ",\n";
    json << "    \"dmem_source\": " << quote(report.effective.dmem_source) << ",\n";
    json << "    \"device_mem_mb\": " << report.effective.device_mem_mb << ",\n";
    json << "    \"budget_checked\": " << (report.effective.budget_checked ? "true" : "false") << "\n";
    json << "  },\n";
    json << "  \"write_count\": " << report.write_count << ",\n";
    json << "  \"write_bytes\": " << report.write_bytes << "\n";
    json << "}\n";
    return json.str();
}

} // namespace gow3_prepare
