// SPDX-License-Identifier: GPL-2.0-or-later
// ParseSelf 的解包 ELF 输入契约（上游 #552，见 openspec/changes/accept-unpacked-elf-input）。
// fixture 全部合成，不含任何游戏内容。
//
// 宿主构建运行：
//   clang++ -std=c++17 -I android-launcher/app/src/main/cpp/gow3prepare \
//     tests/test_gow3_prepare_elf.cpp \
//     android-launcher/app/src/main/cpp/gow3prepare/gow3_prepare.cpp \
//     -o /tmp/test_gow3_prepare_elf && /tmp/test_gow3_prepare_elf
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "gow3_prepare.h"

using gow3_prepare::ParseSelf;

namespace {

int failures = 0;

#define CHECK(cond)                                                    \
    do {                                                               \
        if (!(cond)) {                                                 \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                \
        }                                                              \
    } while (0)

void Put16(u8* p, u16 v) { std::memcpy(p, &v, 2); }
void Put32(u8* p, u32 v) { std::memcpy(p, &v, 4); }
void Put64(u8* p, u64 v) { std::memcpy(p, &v, 8); }
u64 Get64(const u8* p) { u64 v{}; std::memcpy(&v, p, 8); return v; }

// 合成最小 SELF：SELF 头(32B, count=1) + 1 个段表项(32B, flags=0x800|index 0)
// + ELF 头(64B, base=64) + 1 个程序头(56B, phoff=64) + 16B 段数据(文件尾)。
// 镜像布局：ELF 头[0,64) 程序头[64,120) 段数据[0x80,0x90)，end=144。
std::vector<u8> SelfFixture() {
    std::vector<u8> f(208, 0);
    f[0] = 'O'; f[1] = 0x15; f[2] = '='; f[3] = 0x1d;
    Put16(&f[24], 1);                       // 段表项数
    Put64(&f[32], 0x800ull);                // flags: 数据在 SELF 文件内, 段索引 0
    Put64(&f[40], 192);                     // 段数据在文件中的偏移
    Put64(&f[48], 16);                      // length
    Put64(&f[56], 16);                      // mem
    u8* eh = &f[64];
    const u8 ident[7] = {0x7f, 'E', 'L', 'F', 0x02, 0x01, 0x01};
    std::memcpy(eh, ident, 7);
    Put16(eh + 16, 2);                      // e_type = ET_EXEC
    Put16(eh + 18, 62);                     // e_machine = EM_X86_64
    Put32(eh + 20, 1);                      // e_version
    Put64(eh + 24, 0x400000);               // e_entry
    Put64(eh + 32, 64);                     // e_phoff（相对 ELF 头）
    Put16(eh + 52, 64);                     // e_ehsize
    Put16(eh + 54, 56);                     // e_phentsize
    Put16(eh + 56, 1);                      // e_phnum
    u8* ph = &f[128];
    Put32(ph + 0, 1);                       // PT_LOAD
    Put32(ph + 4, 5);                       // R|X
    Put64(ph + 8, 0x80);                    // p_offset（镜像内偏移）
    Put64(ph + 16, 0x400000);               // p_vaddr
    Put64(ph + 24, 0x400000);               // p_paddr
    Put64(ph + 32, 16);                     // p_filesz
    Put64(ph + 40, 16);                     // p_memsz
    Put64(ph + 48, 0x1000);                 // p_align
    for (int i = 0; i < 16; ++i) f[192 + i] = static_cast<u8>(0xA0 + i);
    return f;
}

} // namespace

int main() {
    std::string error;

    // 1) SELF 被接受，产出预期镜像（段数据落在 p_offset 处）。
    const std::vector<u8> self = SelfFixture();
    std::vector<u8> elf_from_self;
    CHECK(ParseSelf(self.data(), self.size(), elf_from_self, error));
    if (elf_from_self.size() != 144) {
        std::printf("FAIL: SELF image size %zu != 144\n", elf_from_self.size());
        ++failures;
    }
    for (int i = 0; i < 16; ++i) {
        CHECK(elf_from_self[0x80 + i] == static_cast<u8>(0xA0 + i));
    }
    CHECK(Get64(elf_from_self.data() + 64 + 8) == 0x80); // 镜像内程序头的 p_offset

    // 2) 解包 ELF 与 SELF 产物逐字节一致（上游 #552 的核心等价断言）。
    std::vector<u8> elf_from_elf;
    CHECK(ParseSelf(elf_from_self.data(), elf_from_self.size(), elf_from_elf, error));
    CHECK(elf_from_elf == elf_from_self);

    // 3) 截断的镜像被拒绝（末尾差一个字节即 out of range）。
    {
        std::vector<u8> cut(elf_from_self.begin(), elf_from_self.end() - 1);
        std::vector<u8> elf;
        CHECK(!ParseSelf(cut.data(), cut.size(), elf, error));
        CHECK(error.find("truncated ELF image") != std::string::npos);
    }

    // 4) 截断的程序头被拒绝。
    {
        std::vector<u8> cut(elf_from_self.begin(), elf_from_self.begin() + 100);
        std::vector<u8> elf;
        CHECK(!ParseSelf(cut.data(), cut.size(), elf, error));
        CHECK(error.find("truncated program headers") != std::string::npos);
    }

    // 5) 错误架构 / 字节序 / 类别被拒绝（与 SELF 同一条 ELF 头校验）。
    for (const auto& probe : std::vector<std::pair<size_t, u8>>{{4, 1}, {5, 2}, {18, 3}}) {
        std::vector<u8> bad = elf_from_self;
        bad[probe.first] = probe.second;
        std::vector<u8> elf;
        CHECK(!ParseSelf(bad.data(), bad.size(), elf, error));
        CHECK(error.find("expected little-endian x86-64 ELF") != std::string::npos);
    }

    // 6) 镜像超过 512 MiB 上限，且在分配前被拒绝。
    {
        std::vector<u8> big = elf_from_self;
        Put64(big.data() + 64 + 8, 512ull * 1024 * 1024); // 程序头 p_offset
        std::vector<u8> elf;
        CHECK(!ParseSelf(big.data(), big.size(), elf, error));
        CHECK(error.find("512 MiB") != std::string::npos);
        CHECK(elf.empty());
    }

    // 7) 非 SELF 非 ELF 魔数仍被拒绝，提示两种可接受格式。
    {
        std::vector<u8> junk(64, 0);
        std::vector<u8> elf;
        CHECK(!ParseSelf(junk.data(), junk.size(), elf, error));
        CHECK(error == "expected PS4 SELF or ELF");
    }

    // 8) SELF 路径既有行为不变：加密段依旧拒绝。
    {
        std::vector<u8> encrypted = SelfFixture();
        Put64(&encrypted[32], 0x800ull | 0x2ull);
        std::vector<u8> elf;
        CHECK(!ParseSelf(encrypted.data(), encrypted.size(), elf, error));
        CHECK(error.find("encrypted/compressed") != std::string::npos);
    }

    if (failures) {
        std::printf("test_gow3_prepare_elf: %d check(s) failed\n", failures);
        return 1;
    }
    std::puts("gow3 prepare elf ok");
    return 0;
}
