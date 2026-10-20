// gow3port: Bionic 兼容头，经 cpu/fex_project.cmake 以 -include 强制纳入 FEX 构建的 C++ 单元。
//
// gow3 的 FEX 提交比 Bloodborne-Android 引用的更新，其在 FileUtils.cpp 里直接调用
// getdents64（Linux 特有）。glibc 在 _LARGEFILE64_SOURCE 下暴露该声明并导出符号；
// Bionic 的 libc 既不声明也不导出它（只有内核 syscall __NR_getdents64）。这里用
// syscall(2) 提供等价实现，保证 FEX 的目录遍历在 Bionic 下可编译可链接。
// 整个实体只在 C++ 下生效：-include 会进入每个翻译单元，C/汇编单元不应看到任何声明。
#pragma once

#if defined(__cplusplus) && defined(__ANDROID__) && !defined(_WIN32)
#include <dirent.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

extern "C" {

// Bionic 没有 getdents64 包装：用原始 syscall。布局与 Linux 内核一致
// （struct linux_dirent64：ino64/d_off/d_reclen/d_type/d_name）。
inline ssize_t gow3_getdents64_wrap(int fd, void* dirp, size_t count) {
    return syscall(__NR_getdents64, fd, dirp, count);
}

}

#define getdents64 gow3_getdents64_wrap
#endif
