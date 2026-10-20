# libgow3cpu.so: guest_cpu.h on FEXCore, linked statically against FEX's libraries.
set(GOW3CPU_DIR "${CMAKE_CURRENT_LIST_DIR}")
add_library(gow3cpu SHARED "${GOW3CPU_DIR}/guest_cpu_fex.cpp" "${GOW3CPU_DIR}/host_call_arm64.S")
target_include_directories(gow3cpu PRIVATE "${GOW3CPU_DIR}/../src" "${CMAKE_SOURCE_DIR}/Source" "${CMAKE_BINARY_DIR}/generated")
target_link_libraries(gow3cpu PRIVATE FEXCore Common JemallocLibs fmt::fmt)
target_link_options(gow3cpu PRIVATE -Wl,--no-undefined)
target_compile_options(gow3cpu PRIVATE ${FEX_TUNE_COMPILE_FLAGS})
set_target_properties(gow3cpu PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
  C_VISIBILITY_PRESET hidden
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN TRUE)

if(ANDROID)
  # libc++（Bionic 唯一可用的 C++ 库）不满足 fextl::move_only_function 对 noexcept
  # std::function 赋值的需求，而 FEXServerClient.cpp 依赖这一点。本库不跑 FEXServer，
  # 因此把该文件从 FEX 的 Common 移出；链接阶段会告诉我们是否还有别处需要它。
  get_target_property(gow3_common_sources Common SOURCES)
  list(FILTER gow3_common_sources EXCLUDE REGEX "FEXServerClient\\.cpp$")
  set_target_properties(Common PROPERTIES SOURCES "${gow3_common_sources}")
endif()
