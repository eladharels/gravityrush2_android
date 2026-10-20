# Included by FEX's project() (CMAKE_PROJECT_FEX_INCLUDE, see build.sh): FEX's CMake files expect to
# be the top-level project, so libgow3cpu is added to FEX's build once all of its targets exist.
# EVAL expands the path now; deferred arguments are expanded when the call runs.
cmake_language(EVAL CODE "cmake_language(DEFER DIRECTORY \"${CMAKE_SOURCE_DIR}\" CALL include \"${CMAKE_CURRENT_LIST_DIR}/gow3cpu.cmake\")")

# Bionic（Android NDK）构建：FEX 的 CMake 只认识 Linux 和 Windows。Android 在内核侧就是
# Linux；toolchain 文件设置的 ANDROID 变量保留，供需要区分的地方判断。
if(ANDROID AND CMAKE_SYSTEM_NAME STREQUAL "Android")
  set(CMAKE_SYSTEM_NAME Linux)
  # gow3 的 FEX 用 getdents64 遍历目录（FileUtils.cpp）。glibc 默认暴露该声明，
  # Bionic 既不声明也不导出它（只有内核 syscall __NR_getdents64）；用
  # cpu/bionic_compat.h 的 syscall 包装补上（见该文件说明）。
  add_compile_definitions(_GNU_SOURCE)
  add_compile_options("-include" "${CMAKE_CURRENT_LIST_DIR}/bionic_compat.h")
endif()
