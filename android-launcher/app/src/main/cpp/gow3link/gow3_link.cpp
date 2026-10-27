// SPDX-License-Identifier: GPL-2.0-or-later
// gow3port: see gow3_link.h. Port of scripts/prepare.py's prepare() and scripts/link_modules.py.
#include "gow3_link.h"

#include <algorithm>
#include <cstring>
#include <map>
#include <optional>
#include <unordered_map>
#include <utility>

#include "gow3_prepare.h"

namespace gow3_link {
namespace {

constexpr size_t MaxImage = 512 * 1024 * 1024;
constexpr size_t ModuleAlign = 65536;

template <typename T>
T Read(const u8* p) {
    T v{};
    std::memcpy(&v, p, sizeof(T));
    return v;
}

struct ProgramHeader {
    u32 type{}, flags{};
    u64 offset{}, vaddr{}, paddr{}, filesz{}, memsz{}, align{};
};

struct Symbol {
    std::string name;
    u32 type{}, binding{};
    u16 section{};
    u64 value{}, size{};
    std::string identity;  ///< the identity key, or kPlain when the name has no three "#" parts
};

struct Load {
    u64 vaddr{}, memsz{}, flags{};
};

struct Reloc {
    u64 target{}, kind{}, value{};  ///< value: symbol index for imports, otherwise an addend
    i64 addend{};
};

// ---------------------------------------------------------------- SHA-1 and base64
// prepare.nid hashes a symbol name into the PS4 NID the dynamic tables index libraries, modules
// and imports by. Both sides must spell it identically, so this is prepare.py's algorithm.

struct Sha1 {
    u32 h[5]{0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u};
    u8 block[64]{};
    size_t used{};
    u64 length{};

    static u32 Rol(u32 v, unsigned bits) { return (v << bits) | (v >> (32 - bits)); }

    void Block(const u8* p) {
        u32 w[80];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<u32>(p[i * 4]) << 24) | (static_cast<u32>(p[i * 4 + 1]) << 16) |
                   (static_cast<u32>(p[i * 4 + 2]) << 8) | static_cast<u32>(p[i * 4 + 3]);
        }
        for (int i = 16; i < 80; ++i) {
            w[i] = Rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        }
        u32 a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; ++i) {
            u32 f{}, k{};
            if (i < 20) {
                f = (b & c) | (~b & d);
                k = 0x5A827999u;
            } else if (i < 40) {
                f = b ^ c ^ d;
                k = 0x6ED9EBA1u;
            } else if (i < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8F1BBCDCu;
            } else {
                f = b ^ c ^ d;
                k = 0xCA62C1D6u;
            }
            const u32 t = Rol(a, 5) + f + e + k + w[i];
            e = d;
            d = c;
            c = Rol(b, 30);
            b = a;
            a = t;
        }
        h[0] += a;
        h[1] += b;
        h[2] += c;
        h[3] += d;
        h[4] += e;
    }

    void Update(const u8* p, size_t size) {
        length += size;
        while (size > 0) {
            const size_t take = std::min(size, sizeof(block) - used);
            std::memcpy(block + used, p, take);
            used += take;
            p += take;
            size -= take;
            if (used == sizeof(block)) {
                Block(block);
                used = 0;
            }
        }
    }

    void Finish(u8 out[20]) {
        const u64 bits = length * 8;
        const u64 counted = length;
        u8 pad = 0x80;
        Update(&pad, 1);
        pad = 0;
        while (used != 56) {
            Update(&pad, 1);
        }
        u8 tail[8];
        for (int i = 0; i < 8; ++i) {
            tail[i] = static_cast<u8>(bits >> (56 - i * 8));
        }
        length = counted; // the trailing length field must not count itself
        Update(tail, 8);
        for (int i = 0; i < 5; ++i) {
            out[i * 4] = static_cast<u8>(h[i] >> 24);
            out[i * 4 + 1] = static_cast<u8>(h[i] >> 16);
            out[i * 4 + 2] = static_cast<u8>(h[i] >> 8);
            out[i * 4 + 3] = static_cast<u8>(h[i]);
        }
    }
};

const u8 kNidSalt[] = {0x51, 0x8d, 0x64, 0xa6, 0x35, 0xde, 0xd8, 0xc1,
                       0xe6, 0xb0, 0x39, 0xb1, 0xc3, 0xe5, 0x52, 0x30};

/// prepare.nid: base64(sha1(name + salt)[0:8] reversed), '=' dropped and '/' turned into '-'.
std::string Nid(const std::string& name) {
    Sha1 sha;
    sha.Update(reinterpret_cast<const u8*>(name.data()), name.size());
    sha.Update(kNidSalt, sizeof(kNidSalt));
    u8 digest[20];
    sha.Finish(digest);
    u8 head[8];
    for (int i = 0; i < 8; ++i) {
        head[i] = digest[7 - i];
    }
    static const char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-";
    std::string out;
    for (size_t i = 0; i < sizeof(head); i += 3) {
        const size_t remain = sizeof(head) - i;
        u32 bits = static_cast<u32>(head[i]) << 16;
        if (remain > 1) {
            bits |= static_cast<u32>(head[i + 1]) << 8;
        }
        if (remain > 2) {
            bits |= static_cast<u32>(head[i + 2]);
        }
        out.push_back(kAlphabet[(bits >> 18) & 63]);
        out.push_back(kAlphabet[(bits >> 12) & 63]);
        if (remain > 1) {
            out.push_back(kAlphabet[(bits >> 6) & 63]);
        }
        if (remain > 2) {
            out.push_back(kAlphabet[bits & 63]);
        }
    }
    // The trailing '=' of the last group is what .rstrip('=') drops; '/' becomes '-'.
    std::replace(out.begin(), out.end(), '/', '-');
    return out;
}

/// link_libc.encode_id: the base64 spelling of the module id in a dynamic table entry.
std::string EncodeId(u64 value) {
    static const char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-";
    std::string out(1, kAlphabet[value & 63]);
    value >>= 6;
    while (value) {
        out.insert(out.begin(), kAlphabet[value & 63]);
        value >>= 6;
    }
    return out;
}

// ---------------------------------------------------------------- identities
// A symbol's identity is (NID, library identity, module identity). Names without exactly three
// '#'-separated parts ("module_start" and friends) have none: Python's None, kPlain here.

const std::string kPlain = std::string(1, '\0');
constexpr char kSeparator = '\1';

struct LibraryIdentity {
    std::string name;
    u32 version{};
};

std::string MakeKey(const std::string& nid, const LibraryIdentity& library,
                    const LibraryIdentity& module) {
    std::string key;
    key.reserve(nid.size() + library.name.size() + module.name.size() + 24);
    key.push_back('\0');
    key += nid;
    key.push_back(kSeparator);
    key += library.name;
    key.push_back(kSeparator);
    key += std::to_string(library.version);
    key.push_back(kSeparator);
    key += module.name;
    key.push_back(kSeparator);
    key += std::to_string(module.version);
    return key;
}

size_t Separator(const std::string& key, size_t from) {
    return key.find(kSeparator, from);
}

/// link_modules.canonical: host tables key imports as NID#library, because the eboot's own #c#C
/// spelling differs per game. The library version is dropped.
std::string Canonical(const std::string& key) {
    if (key == kPlain) {
        return {};
    }
    const size_t nid_end = Separator(key, 1);
    const size_t library_end = Separator(key, nid_end + 1);
    return key.substr(1, nid_end - 1) + "#" + key.substr(nid_end + 1, library_end - nid_end - 1);
}

std::string LibraryOf(const std::string& key) {
    const size_t nid_end = Separator(key, 1);
    const size_t library_end = Separator(key, nid_end + 1);
    return key.substr(nid_end + 1, library_end - nid_end - 1);
}

std::string NidOf(const std::string& key) { return key.substr(1, Separator(key, 1) - 1); }

// ---------------------------------------------------------------- checked buffers

bool Bounds(const std::vector<u8>& data, size_t offset, size_t size) {
    return offset <= data.size() && size <= data.size() - offset;
}

bool ReadAt(const std::vector<u8>& data, size_t offset, void* out, size_t size, std::string& error) {
    if (!Bounds(data, offset, size)) {
        error = "read out of range";
        return false;
    }
    std::memcpy(out, data.data() + offset, size);
    return true;
}

template <typename T>
bool ReadField(const std::vector<u8>& data, size_t offset, T& out, std::string& error) {
    return ReadAt(data, offset, &out, sizeof(T), error);
}

void Push64(std::vector<u8>& out, u64 value) {
    for (int i = 0; i < 8; ++i) {
        out.push_back(static_cast<u8>(value >> (i * 8)));
    }
}

void Store64(std::vector<u8>& buffer, size_t offset, u64 value) {
    for (int i = 0; i < 8; ++i) {
        buffer[offset + i] = static_cast<u8>(value >> (i * 8));
    }
}

void PushPadded(std::vector<u8>& out, const std::string& text, size_t width) {
    std::vector<u8> bytes(text.begin(), text.end());
    bytes.resize(width, 0);
    out.insert(out.end(), bytes.begin(), bytes.end());
}

void PushMagic(std::vector<u8>& out, const char* magic) {
    for (int i = 0; i < 8; ++i) {
        out.push_back(static_cast<u8>(magic[i]));
    }
}

size_t AlignUp(u64 value) { return static_cast<size_t>((value + ModuleAlign - 1) & ~u64{ModuleAlign - 1}); }

// ---------------------------------------------------------------- PS4 module parsing

struct Module {
    std::vector<u8> elf;
    std::vector<ProgramHeader> ph;
    // The dynamic entries in order. tags is the dict() view (a repeated tag keeps its last value),
    // but the library and module tables are built from the list, because a PS4 module repeats those
    // tags once per entry and only the list keeps them all.
    std::vector<std::pair<u64, u64>> dynamic;
    std::map<u64, u64> tags;
    std::vector<Symbol> symbols;
    std::vector<Reloc> relocations;
    std::map<std::string, LibraryIdentity> libraries;
    std::map<std::string, LibraryIdentity> modules;
};

bool ProgramHeaders(const std::vector<u8>& elf, std::vector<ProgramHeader>& out, std::string& error) {
    u64 phoff{};
    u16 phentsize{}, phnum{};
    if (!ReadField(elf, 32, phoff, error) || !ReadField(elf, 54, phentsize, error) ||
        !ReadField(elf, 56, phnum, error)) {
        return false;
    }
    if (phentsize != 56 || phnum == 0 || phnum > 255) {
        error = "unsupported program headers";
        return false;
    }
    out.clear();
    out.reserve(phnum);
    for (u16 i = 0; i < phnum; ++i) {
        const size_t base = phoff + static_cast<size_t>(i) * phentsize;
        ProgramHeader p;
        if (!ReadField(elf, base + 0, p.type, error) || !ReadField(elf, base + 4, p.flags, error) ||
            !ReadField(elf, base + 8, p.offset, error) || !ReadField(elf, base + 16, p.vaddr, error) ||
            !ReadField(elf, base + 32, p.filesz, error) || !ReadField(elf, base + 40, p.memsz, error) ||
            !ReadField(elf, base + 48, p.align, error)) {
            return false;
        }
        out.push_back(p);
    }
    return true;
}

const ProgramHeader* FindHeader(const std::vector<ProgramHeader>& ph, u32 type) {
    for (const ProgramHeader& p : ph) {
        if (p.type == type) {
            return &p;
        }
    }
    return nullptr;
}

std::vector<Load> LoadSegments(const std::vector<ProgramHeader>& ph) {
    std::vector<Load> loads;
    for (const ProgramHeader& p : ph) {
        if (p.type == 1u || p.type == 0x61000010u) {
            loads.push_back(Load{p.vaddr, p.memsz, p.flags});
        }
    }
    return loads;
}

bool Mapped(const std::vector<Load>& loads, u64 address, u64 size = 8) {
    for (const Load& p : loads) {
        if (p.vaddr <= address && address + size <= p.vaddr + p.memsz) {
            return true;
        }
    }
    return false;
}

/// Read the dynamic table into the module's tag map. `stop_at_null` is what prepare() and
/// link_modules.module() do; prepare.inspect_libc() walks every entry, because the tables it
/// reads are not terminated.
bool ReadDynamic(Module& module, bool stop_at_null, std::string& error) {
    const ProgramHeader* dp = FindHeader(module.ph, 2u);
    if (!dp) {
        error = "no dynamic header";
        return false;
    }
    module.tags.clear();
    module.dynamic.clear();
    const u64 end = dp->offset + dp->filesz;
    for (size_t pos = static_cast<size_t>(dp->offset); pos + 16 <= end; pos += 16) {
        u64 tag{}, value{};
        if (!ReadField(module.elf, pos, tag, error) || !ReadField(module.elf, pos + 8, value, error)) {
            return false;
        }
        if (tag == 0 && stop_at_null) {
            break;
        }
        module.dynamic.emplace_back(tag, value);
    }
    for (const auto& entry : module.dynamic) {
        module.tags[entry.first] = entry.second; // dict(): a repeated tag keeps the last value
    }
    return true;
}

bool BlobOf(const Module& module, std::vector<u8>& blob, std::string& error) {
    const ProgramHeader* lp = FindHeader(module.ph, 0x61000000u);
    if (!lp) {
        error = "no module blob header";
        return false;
    }
    if (!Bounds(module.elf, static_cast<size_t>(lp->offset), static_cast<size_t>(lp->filesz))) {
        error = "module blob out of range";
        return false;
    }
    blob.assign(module.elf.begin() + lp->offset, module.elf.begin() + lp->offset + lp->filesz);
    return true;
}

/// The blob slice a (offset, size) dynamic tag pair describes. Both values come from the dynamic
/// table, not from the blob itself.
bool BlobTable(const Module& module, const std::vector<u8>& blob, u64 offset_tag, u64 size_tag,
               std::vector<u8>& out, std::string& error) {
    u64 offset{}, size{};
    const auto at = module.tags.find(offset_tag);
    if (at != module.tags.end()) {
        offset = at->second;
        const auto sz = module.tags.find(size_tag);
        if (sz != module.tags.end()) {
            size = sz->second;
        }
    }
    if (!Bounds(blob, static_cast<size_t>(offset), static_cast<size_t>(size))) {
        error = "table out of range";
        return false;
    }
    out.assign(blob.begin() + offset, blob.begin() + offset + size);
    return true;
}

/// The blob's string table: tags 0x61000035 (offset) and 0x61000037 (size).
bool StringsOf(const Module& module, const std::vector<u8>& blob, std::vector<u8>& strings,
               std::string& error) {
    return BlobTable(module, blob, 0x61000035u, 0x61000037u, strings, error);
    return true;
}

bool ReadString(const std::vector<u8>& strings, size_t offset, std::string& out, std::string& error) {
    if (!Bounds(strings, offset, 1)) {
        error = "string offset out of range";
        return false;
    }
    size_t end = offset;
    while (end < strings.size() && strings[end] != 0) {
        ++end;
    }
    if (end == strings.size()) {
        error = "unterminated string";
        return false;
    }
    out.assign(strings.begin() + offset, strings.begin() + end);
    return true;
}

bool ResolveIdentity(const Module& module, const std::string& name, std::string& out,
                     std::string& error) {
    const size_t first = name.find('#');
    if (first == std::string::npos) {
        out = kPlain;
        return true;
    }
    const size_t second = name.find('#', first + 1);
    if (second == std::string::npos || name.find('#', second + 1) != std::string::npos) {
        out = kPlain;
        return true;
    }
    const auto library = module.libraries.find(name.substr(first + 1, second - first - 1));
    const auto mod = module.modules.find(name.substr(second + 1));
    if (library == module.libraries.end() || mod == module.modules.end()) {
        error = "symbol names an undeclared library or module";
        return false;
    }
    out = MakeKey(name.substr(0, first), library->second, mod->second);
    return true;
}

bool ReadSymbols(Module& module, const std::vector<u8>& blob, const std::vector<u8>& strings,
                 bool with_identity, std::string& error) {
    std::vector<u8> table;
    if (!BlobTable(module, blob, 0x61000039u, 0x6100003fu, table, error)) {
        return false;
    }
    if (table.size() % 24) {
        error = "bad symbol table size";
        return false;
    }
    for (size_t pos = 0; pos < table.size(); pos += 24) {
        u32 name{};
        u8 info{};
        u16 section{};
        u64 value{}, size_field{};
        if (!ReadField(table, pos + 0, name, error) || !ReadField(table, pos + 4, info, error) ||
            !ReadField(table, pos + 6, section, error) || !ReadField(table, pos + 8, value, error) ||
            !ReadField(table, pos + 16, size_field, error)) {
            return false;
        }
        Symbol s;
        s.type = info & 15;
        s.binding = info >> 4;
        s.section = section;
        s.value = value;
        s.size = size_field;
        if (!ReadString(strings, name, s.name, error)) {
            return false;
        }
        module.symbols.push_back(s);
    }
    if (with_identity) {
        for (Symbol& s : module.symbols) {
            if (!ResolveIdentity(module, s.name, s.identity, error)) {
                return false;
            }
        }
    } else {
        for (Symbol& s : module.symbols) {
            s.identity = kPlain;
        }
    }
    return true;
}

bool ReadRelocations(Module& module, const std::vector<u8>& blob, std::string& error) {
    const u64 pairs[][2] = {{0x61000029u, 0x6100002du}, {0x6100002fu, 0x61000031u}};
    for (const auto& pair : pairs) {
        std::vector<u8> table;
        if (!BlobTable(module, blob, pair[0], pair[1], table, error)) {
            return false;
        }
        if (table.size() % 24) {
            error = "bad relocation table size";
            return false;
        }
        for (size_t pos = 0; pos < table.size(); pos += 24) {
            Reloc r;
            u64 info{};
            if (!ReadField(table, pos + 0, r.target, error) || !ReadField(table, pos + 8, info, error) ||
                !ReadField(table, pos + 16, r.addend, error)) {
                return false;
            }
            r.kind = info & 0xffffffffu;
            r.value = info >> 32;
            module.relocations.push_back(r);
        }
    }
    return true;
}

/// link_modules.module(): a PS4 module with its library/module tables, symbols and relocations.
/// `with_identity` is false for prepare.inspect_libc, which only reads symbol names and would
/// otherwise refuse a module whose tables it never needed.
bool ParseModule(const std::vector<u8>& source, Module& out, bool with_identity,
                 std::string& error) {
    std::vector<u8> elf;
    if (!gow3_prepare::ParseSelf(source.data(), source.size(), elf, error)) {
        return false;
    }
    out = Module{};
    out.elf = std::move(elf);
    if (!ProgramHeaders(out.elf, out.ph, error) || !ReadDynamic(out, true, error)) {
        return false;
    }
    std::vector<u8> blob, strings;
    if (!BlobOf(out, blob, error) || !StringsOf(out, blob, strings, error)) {
        return false;
    }
    for (const auto& entry : out.dynamic) {
        const u64 tag = entry.first;
        if (tag != 0x61000013u && tag != 0x61000015u && tag != 0x6100000du && tag != 0x6100000fu) {
            continue;
        }
        auto& table = (tag == 0x61000013u || tag == 0x61000015u) ? out.libraries : out.modules;
        const std::string key = EncodeId(entry.second >> 48);
        LibraryIdentity identity;
        if (!ReadString(strings, static_cast<size_t>(entry.second & 0xffffffffu), identity.name,
                        error)) {
            return false;
        }
        identity.version = static_cast<u32>((entry.second >> 32) & 0xffff);
        const auto found = table.find(key);
        if (found != table.end() &&
            (found->second.name != identity.name || found->second.version != identity.version)) {
            error = "conflicting module/library IDs";
            return false;
        }
        table[key] = identity;
    }
    return ReadSymbols(out, blob, strings, with_identity, error) &&
           ReadRelocations(out, blob, error);
}

/// prepare.inspect_libc: prove this dump's _init_env really is RET instead of assuming it. The
/// answer is the G3PROBE2 runtime capability bit, so it has to be computed, not guessed.
bool InitEnvIsRet(const std::vector<u8>& source, bool& ret, std::string& error) {
    Module libc;
    if (!ParseModule(source, libc, /*with_identity=*/false, error)) {
        return false;
    }
    // inspect_libc walks every dynamic entry rather than stopping at DT_NULL.
    if (!ReadDynamic(libc, false, error)) {
        return false;
    }
    std::vector<u8> blob, strings, table;
    if (!BlobOf(libc, blob, error) || !StringsOf(libc, blob, strings, error) ||
        !BlobTable(libc, blob, 0x61000039u, 0x6100003fu, table, error)) {
        return false;
    }
    const std::string init_env = Nid("_init_env");
    ret = false;
    for (size_t pos = 0; pos + 24 <= table.size(); pos += 24) {
        u32 name{};
        u8 info{};
        u16 section{};
        u64 value{}, size{};
        if (!ReadField(table, pos + 0, name, error) || !ReadField(table, pos + 4, info, error) ||
            !ReadField(table, pos + 6, section, error) || !ReadField(table, pos + 8, value, error) ||
            !ReadField(table, pos + 16, size, error)) {
            return false;
        }
        std::string symbol;
        if (!ReadString(strings, name, symbol, error)) {
            return false;
        }
        if (symbol.substr(0, symbol.find('#')) != init_env || section == 0 || (info & 15) != 2) {
            continue;
        }
        const ProgramHeader* code = nullptr;
        for (const ProgramHeader& p : libc.ph) {
            if (p.type == 1u && (p.flags & 1u) && p.vaddr <= value && value < p.vaddr + p.filesz) {
                code = &p;
                break;
            }
        }
        if (!code) {
            continue;
        }
        const size_t at = static_cast<size_t>(code->offset + value - code->vaddr);
        if (!Bounds(libc.elf, at, static_cast<size_t>(size))) {
            continue;
        }
        ret = size == 1 && libc.elf[at] == 0xc3;
        break;
    }
    return true;
}

} // namespace

// ---------------------------------------------------------------- prepare.prepare

bool PrepareBoot(const u8* eboot, size_t eboot_size, const u8* libc, size_t libc_size,
                 std::vector<u8>& boot, std::string& error) {
    const std::vector<u8> source(eboot, eboot + eboot_size);
    std::vector<u8> elf;
    if (!gow3_prepare::ParseSelf(source.data(), source.size(), elf, error)) {
        return false;
    }
    std::vector<ProgramHeader> ph;
    u64 entry{};
    if (!ProgramHeaders(elf, ph, error) || !ReadField(elf, 24, entry, error)) {
        return false;
    }
    const std::vector<Load> loads = LoadSegments(ph);
    if (loads.empty()) {
        error = "no load segments";
        return false;
    }
    u64 image_size = 0;
    for (const Load& p : loads) {
        image_size = std::max(image_size, p.vaddr + p.memsz);
    }
    if (image_size > MaxImage || !Mapped(loads, entry, 1)) {
        error = "invalid memory image";
        return false;
    }
    std::vector<u8> image(static_cast<size_t>(image_size), 0);
    for (const ProgramHeader& p : ph) {
        if (p.type != 1u && p.type != 0x61000010u) {
            continue;
        }
        if (p.filesz > p.memsz) {
            error = "segment file size exceeds memory size";
            return false;
        }
        if (!Bounds(elf, static_cast<size_t>(p.offset), static_cast<size_t>(p.filesz))) {
            error = "segment out of range";
            return false;
        }
        std::memcpy(image.data() + static_cast<size_t>(p.vaddr), elf.data() + p.offset,
                    static_cast<size_t>(p.filesz));
    }
    Module main;
    main.elf = elf;
    main.ph = ph;
    if (!ReadDynamic(main, true, error)) {
        return false;
    }
    std::vector<u8> blob, strings;
    if (!BlobOf(main, blob, error) || !StringsOf(main, blob, strings, error) ||
        !ReadSymbols(main, blob, strings, false, error)) {
        return false;
    }
    const std::vector<Symbol>& symbols = main.symbols;
    std::vector<Reloc> relocations;
    std::vector<u32> import_order;              // first-seen order, as Python dict insertion order
    std::unordered_map<u32, u32> import_index;  // symbol index -> name table index
    const u64 pairs[][2] = {{0x61000029u, 0x6100002du}, {0x6100002fu, 0x61000031u}};
    for (const auto& pair : pairs) {
        std::vector<u8> table;
        if (!BlobTable(main, blob, pair[0], pair[1], table, error)) {
            return false;
        }
        if (table.size() % 24) {
            error = "bad relocation table size";
            return false;
        }
        for (size_t pos = 0; pos < table.size(); pos += 24) {
            Reloc r;
            u64 info{};
            if (!ReadField(table, pos + 0, r.target, error) || !ReadField(table, pos + 8, info, error) ||
                !ReadField(table, pos + 16, r.addend, error)) {
                return false;
            }
            u64 kind = info & 0xffffffffu;
            const u64 sym = info >> 32;
            if (!Mapped(loads, r.target)) {
                error = "relocation outside mapped segments";
                return false;
            }
            if (kind == 8) {
                relocations.push_back(Reloc{r.target, 0, static_cast<u64>(r.addend), 0});
                continue;
            }
            if (kind != 1 && kind != 6 && kind != 7) {
                error = "unsupported relocation " + std::to_string(kind);
                return false;
            }
            if (sym >= symbols.size()) {
                error = "relocation names an unknown symbol";
                return false;
            }
            const Symbol& symbol = symbols[sym];
            if (symbol.section) {
                if (symbol.section == 0xfff1) {
                    Store64(image, static_cast<size_t>(r.target), symbol.value + r.addend);
                } else {
                    relocations.push_back(Reloc{r.target, 0, symbol.value + r.addend, 0});
                }
                continue;
            }
            auto slot = import_index.find(static_cast<u32>(sym));
            if (slot == import_index.end()) {
                slot = import_index
                           .emplace(static_cast<u32>(sym), static_cast<u32>(import_order.size()))
                           .first;
                import_order.push_back(static_cast<u32>(sym));
            }
            kind = (symbol.type == 1) ? 2 : 1;
            if ((kind == 1 && r.addend != 0) || r.addend < 0 || r.addend >= 4096) {
                error = "unsupported import addend";
                return false;
            }
            relocations.push_back(Reloc{r.target, kind, slot->second, r.addend});
        }
    }
    bool init_env_is_ret = false;
    if (!InitEnvIsRet(std::vector<u8>(libc, libc + libc_size), init_env_is_ret, error)) {
        return false;
    }
    std::vector<u8> out;
    PushMagic(out, "G3PROBE2");
    Push64(out, image.size());
    Push64(out, entry);
    Push64(out, loads.size());
    Push64(out, relocations.size());
    Push64(out, import_order.size());
    Push64(out, init_env_is_ret ? 1 : 0);
    for (const Load& p : loads) {
        Push64(out, p.vaddr);
        Push64(out, p.memsz);
        Push64(out, p.flags);
    }
    for (u32 sym : import_order) {
        if (symbols[sym].name.size() >= 128) {
            error = "import name too long";
            return false;
        }
        PushPadded(out, symbols[sym].name, 128);
    }
    for (const Reloc& r : relocations) {
        Push64(out, r.target);
        Push64(out, r.kind);
        Push64(out, r.value);
        Push64(out, static_cast<u64>(r.addend));
    }
    out.insert(out.end(), image.begin(), image.end());
    boot = std::move(out);
    return true;
}

// ---------------------------------------------------------------- link_modules.link

namespace {

/// Rewrite initial-exec `mov reg, fs:[0]` to GS: glibc owns FS on Linux, so the runtime points GS
/// at each guest thread's TCB instead. gr2_pc: games load the thread pointer into any register
/// (64, REX.W 48/4c, 8b, ModRM mod 00 rm 100 with any reg, SIB 25, disp32 0).
bool IsFsLoad(const u8* at) {
    return at[0] == 0x64 && (at[1] == 0x48 || at[1] == 0x4c) && at[2] == 0x8b && (at[3] & 0xc7) == 0x04 &&
           at[4] == 0x25 && at[5] == 0 && at[6] == 0 && at[7] == 0 && at[8] == 0;
}
size_t PatchFsLoads(std::vector<u8>& image, const std::vector<ProgramHeader>& ph, u64 base) {
    constexpr size_t kFsLoad = 9;
    size_t patched = 0;
    for (const ProgramHeader& p : ph) {
        if (p.type != 1u || !(p.flags & 1u)) {
            continue;
        }
        const size_t start = static_cast<size_t>(base + p.vaddr);
        if (start >= image.size()) {
            continue;
        }
        const size_t stop = std::min(image.size(), static_cast<size_t>(base + p.vaddr + p.filesz));
        for (size_t at = start; at + kFsLoad <= stop;) {
            if (IsFsLoad(image.data() + at)) {
                image[at] = 0x65;
                ++patched;
                at += kFsLoad;
            } else {
                ++at;
            }
        }
    }
    return patched;
}

struct Export {
    u64 address{};
    u64 kind{};
};

} // namespace

bool LinkBoot(const std::vector<u8>& boot, const std::vector<u8>& eboot,
              const std::vector<std::vector<u8>>& modules, std::vector<u8>& linked,
              std::string& error) {
    // G3PROBE2: magic, size, entry, segments, relocations, names, capabilities.
    char magic[8];
    u64 image_size{}, entry{}, segment_count{}, relocation_count{}, name_count{}, capabilities{};
    if (!ReadAt(boot, 0, magic, 8, error) || std::memcmp(magic, "G3PROBE2", 8) != 0) {
        error = "linker expects freshly prepared G3PROBE2";
        return false;
    }
    size_t pos = 56;
    if (!ReadField(boot, 8, image_size, error) || !ReadField(boot, 16, entry, error) ||
        !ReadField(boot, 24, segment_count, error) || !ReadField(boot, 32, relocation_count, error) ||
        !ReadField(boot, 40, name_count, error) || !ReadField(boot, 48, capabilities, error)) {
        return false;
    }
    std::vector<Load> segments;
    for (u64 i = 0; i < segment_count; ++i) {
        Load s;
        if (!ReadField(boot, pos + static_cast<size_t>(i) * 24 + 0, s.vaddr, error) ||
            !ReadField(boot, pos + static_cast<size_t>(i) * 24 + 8, s.memsz, error) ||
            !ReadField(boot, pos + static_cast<size_t>(i) * 24 + 16, s.flags, error)) {
            return false;
        }
        segments.push_back(s);
    }
    pos += static_cast<size_t>(segment_count) * 24;
    std::vector<std::string> boot_names;
    for (u64 i = 0; i < name_count; ++i) {
        const size_t at = pos + static_cast<size_t>(i) * 128;
        if (!Bounds(boot, at, 128)) {
            error = "name table out of range";
            return false;
        }
        size_t length = 0;
        while (length < 128 && boot[at + length] != 0) {
            ++length;
        }
        boot_names.emplace_back(reinterpret_cast<const char*>(boot.data() + at), length);
    }
    pos += static_cast<size_t>(name_count) * 128;
    std::vector<Reloc> relocations;
    for (u64 i = 0; i < relocation_count; ++i) {
        const size_t at = pos + static_cast<size_t>(i) * 32;
        Reloc r;
        if (!ReadField(boot, at + 0, r.target, error) || !ReadField(boot, at + 8, r.kind, error) ||
            !ReadField(boot, at + 16, r.value, error) || !ReadField(boot, at + 24, r.addend, error)) {
            return false;
        }
        relocations.push_back(r);
    }
    pos += static_cast<size_t>(relocation_count) * 32;
    if (!Bounds(boot, pos, static_cast<size_t>(image_size)) ||
        pos + static_cast<size_t>(image_size) != boot.size()) {
        error = "unexpected boot trailer";
        return false;
    }
    std::vector<u8> image(boot.begin() + pos, boot.begin() + pos + image_size);

    Module main;
    if (!ParseModule(eboot, main, /*with_identity=*/true, error)) {
        return false;
    }
    // The eboot's own spelling of every import, resolved to an identity and renamed to the
    // NID#library form the host tables use. Two names resolving to one identity share a slot,
    // and keep the slot of whichever was seen first, as Python's dict comprehension does.
    std::vector<std::string> identity_order;
    std::unordered_map<std::string, u64> identity_index;
    std::vector<std::string> names;
    names.reserve(boot_names.size() + 64);
    for (u64 i = 0; i < boot_names.size(); ++i) {
        std::string key;
        if (!ResolveIdentity(main, boot_names[i], key, error)) {
            return false;
        }
        auto slot = identity_index.find(key);
        if (slot == identity_index.end()) {
            identity_order.push_back(key);
            identity_index.emplace(key, i);
        } else {
            slot->second = i; // a later name of the same identity takes the slot
        }
        names.push_back(Canonical(key));
    }

    // imported(): give a symbol's identity a name table slot, appending if it is new.
    auto imported = [&](const Symbol& symbol, u64& index) -> bool {
        const std::string& key = symbol.identity;
        auto slot = identity_index.find(key);
        if (slot == identity_index.end()) {
            identity_order.push_back(key);
            index = names.size();
            identity_index.emplace(key, index);
            names.push_back(Canonical(key));
        } else {
            index = slot->second;
        }
        return true;
    };

    std::map<std::string, Export> exports;
    std::map<std::string, std::vector<Export>> by_nid;
    size_t fs_patched = PatchFsLoads(image, main.ph, 0);
    size_t base = AlignUp(image_size);
    u32 tls_module = 2; // libc is module id 2 by construction: the first one that has TLS
    struct ModuleEntry {
        u64 base{}, size{}, init{}, tls_address{}, tls_memsz{}, tls_filesz{}, tls_module{};
    };
    std::vector<ModuleEntry> table;
    for (const std::vector<u8>& bytes : modules) {
        Module m;
        if (!ParseModule(bytes, m, /*with_identity=*/true, error)) {
            return false;
        }
        const std::vector<Load> loads = LoadSegments(m.ph);
        u64 module_size = 0;
        for (const Load& p : loads) {
            module_size = std::max(module_size, p.vaddr + p.memsz);
        }
        if (base + module_size > MaxImage) {
            error = "linked image exceeds probe limit";
            return false;
        }
        image.resize(base + module_size, 0);
        auto mapped = [&loads](u64 address, u64 length = 8) {
            return Mapped(loads, address, length);
        };
        for (const ProgramHeader& p : m.ph) {
            if (p.type != 1u && p.type != 0x61000010u) {
                continue;
            }
            if (p.filesz > p.memsz) {
                error = "invalid segment size";
                return false;
            }
            if (!Bounds(m.elf, static_cast<size_t>(p.offset), static_cast<size_t>(p.filesz))) {
                error = "segment out of range";
                return false;
            }
            std::memcpy(image.data() + base + static_cast<size_t>(p.vaddr), m.elf.data() + p.offset,
                        static_cast<size_t>(p.filesz));
            segments.push_back(Load{base + p.vaddr, p.memsz, p.flags});
        }
        const ProgramHeader* tls = FindHeader(m.ph, 7u);
        u32 module_id = 0;
        if (tls) {
            if (tls->filesz > tls->memsz || !mapped(tls->vaddr, tls->memsz)) {
                error = "unsupported TLS layout";
                return false;
            }
            module_id = tls_module++;
        }
        for (const Reloc& r : m.relocations) {
            if (!mapped(r.target)) {
                error = "relocation outside image";
                return false;
            }
            u64 target = r.target + base;
            if (r.kind == 8) {
                relocations.push_back(Reloc{target, 0, base + static_cast<u64>(r.addend), 0});
            } else if (r.kind == 16) {
                if (r.value >= m.symbols.size()) {
                    error = "TLS relocation names an unknown symbol";
                    return false;
                }
                const Symbol& symbol = m.symbols[r.value];
                if (!symbol.name.empty() || symbol.type != 3 || r.addend != 0 || module_id == 0) {
                    error = "unsupported external TLS relocation";
                    return false;
                }
                Store64(image, static_cast<size_t>(target), module_id);
            } else if (r.kind == 1 || r.kind == 6 || r.kind == 7) {
                if (r.value >= m.symbols.size()) {
                    error = "relocation names an unknown symbol";
                    return false;
                }
                const Symbol& symbol = m.symbols[r.value];
                if (symbol.section) {
                    if (symbol.section == 0xfff1) {
                        Store64(image, static_cast<size_t>(target), symbol.value + r.addend);
                    } else {
                        relocations.push_back(
                            Reloc{target, 0, base + symbol.value + r.addend, 0});
                    }
                } else if (symbol.identity == kPlain && symbol.binding == 2) {
                    Store64(image, static_cast<size_t>(target), 0); // weak undefined: module_start
                } else {
                    u64 index = 0;
                    if (!imported(symbol, index)) {
                        return false;
                    }
                    const u64 kind = (symbol.type == 1) ? 2 : 1;
                    if ((kind == 1 && r.addend != 0) || r.addend < 0 || r.addend >= 4096) {
                        error = "unsupported import addend";
                        return false;
                    }
                    relocations.push_back(Reloc{target, kind, index, r.addend});
                }
            } else {
                error = "unsupported relocation type " + std::to_string(r.kind);
                return false;
            }
        }
        for (const Symbol& s : m.symbols) {
            const bool exportable = s.section && (s.binding == 1 || s.binding == 2) &&
                                    s.identity != kPlain && (s.type == 1 || s.type == 2);
            if (!exportable) {
                continue;
            }
            if (!mapped(s.value, std::max<u64>(1, s.size))) {
                error = "export outside load segments";
                return false;
            }
            const Export entry{base + s.value, (s.type == 1) ? 2u : 1u};
            if (!exports.emplace(s.identity, entry).second) {
                error = "ambiguous export";
                return false;
            }
            by_nid[NidOf(s.identity) + "#" + LibraryOf(s.identity)].push_back(entry);
        }
        fs_patched += PatchFsLoads(image, m.ph, base);
        table.push_back(ModuleEntry{base, module_size, base + m.tags[12],
                                    tls ? base + tls->vaddr : 0, tls ? tls->memsz : 0,
                                    tls ? tls->filesz : 0, module_id});
        base = AlignUp(base + module_size);
    }

    // Bind what the modules export. Imports of libSceLibcInternal are served by libc.prx's
    // same-NID exports: that library does not ship with the game and exposes the same functions.
    struct Binding {
        u64 index{}, address{}, kind{};
    };
    std::vector<Binding> bindings;
    for (const std::string& key : identity_order) {
        const u64 index = identity_index[key];
        std::optional<Export> found;
        if (const auto it = exports.find(key); it != exports.end()) {
            found = it->second;
        } else if (key != kPlain && LibraryOf(key) == "libSceLibcInternal") {
            const auto candidates = by_nid.find(NidOf(key) + "#libc");
            if (candidates != by_nid.end() && candidates->second.size() == 1) {
                found = candidates->second.front();
            }
        }
        if (!found && key != kPlain) {
            // gr2_pc: a module may export a newer version of the library the game imports (the
            // firmware's libSceNgs2.sprx); the same NID and library name is the same function.
            const auto candidates = by_nid.find(NidOf(key) + "#" + LibraryOf(key));
            if (candidates != by_nid.end() && candidates->second.size() == 1) {
                found = candidates->second.front();
            }
        }
        if (found) {
            bindings.push_back(Binding{index, found->address, found->kind});
        }
    }

    const ProgramHeader* main_tls = FindHeader(main.ph, 7u);
    u64 main_tls_values[4] = {0, 0, 0, 0};
    if (main_tls) {
        if (main_tls->filesz > main_tls->memsz || main_tls->memsz > 1024 * 1024 ||
            main_tls->vaddr + main_tls->filesz > image_size) {
            error = "unsupported eboot TLS layout";
            return false;
        }
        main_tls_values[0] = main_tls->vaddr;
        main_tls_values[1] = main_tls->filesz;
        main_tls_values[2] = main_tls->memsz;
        main_tls_values[3] = main_tls->align;
    }
    const ProgramHeader* procparam = FindHeader(main.ph, 0x61000001u);
    if (!procparam) {
        error = "no procparam header";
        return false;
    }

    std::vector<u8> out;
    PushMagic(out, "G3PROBE5");
    Push64(out, image.size());
    Push64(out, entry);
    Push64(out, segments.size());
    Push64(out, relocations.size());
    Push64(out, names.size());
    Push64(out, capabilities);
    Push64(out, procparam->vaddr);
    for (u64 value : main_tls_values) {
        Push64(out, value);
    }
    Push64(out, table.size());
    for (const ModuleEntry& t : table) {
        Push64(out, t.base);
        Push64(out, t.size);
        Push64(out, t.init);
        Push64(out, t.tls_address);
        Push64(out, t.tls_memsz);
        Push64(out, t.tls_filesz);
        Push64(out, t.tls_module);
    }
    Push64(out, bindings.size());
    for (const Binding& b : bindings) {
        Push64(out, b.index);
        Push64(out, b.address);
        Push64(out, b.kind);
    }
    for (const Load& s : segments) {
        Push64(out, s.vaddr);
        Push64(out, s.memsz);
        Push64(out, s.flags);
    }
    for (const std::string& name : names) {
        if (name.size() >= 128) {
            error = "name too long";
            return false;
        }
        PushPadded(out, name, 128);
    }
    for (const Reloc& r : relocations) {
        Push64(out, r.target);
        Push64(out, r.kind);
        Push64(out, r.value);
        Push64(out, static_cast<u64>(r.addend));
    }
    out.insert(out.end(), image.begin(), image.end());
    linked = std::move(out);
    return true;
}

} // namespace gow3_link
