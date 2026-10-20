# launcher 自身 JNI 桥库的构建清单：libgow3present / libgow3prepare。
# 由 Gradle externalNativeBuild 在每次 APK 构建中原生产出（Android Studio 与命令行
# 同一条路径）；曾有的脚本旁路与预生成 .so 搬运已随本次迁移删除。
# 编译契约与脚本时代逐条等价：arm64-v8a 单 ABI、隐藏符号可见性、
# --gc-sections、16KB 页对齐、链接 liblog/libandroid、静态 libc++（由
# build.gradle.kts 的 ANDROID_STL=c++_static 单点声明）。
cmake_minimum_required(VERSION 3.22.1)
project(gow3launcher LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# 线格式协议头保持仓库级唯一来源（tools/android/present/gow3_present_proto.h），
# 用相对路径引用，不在此目录复制副本——双源必腐化。
set(GOW3_REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_library(gow3present SHARED gow3present/gow3present.c)
add_library(gow3prepare SHARED
        gow3prepare/gow3_prepare.cpp
        gow3prepare/gow3_prepare_jni.cpp
        gow3link/gow3_link.cpp)

foreach(target gow3present gow3prepare)
    target_include_directories(${target} PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/gow3present
            ${CMAKE_CURRENT_SOURCE_DIR}/gow3prepare
            ${CMAKE_CURRENT_SOURCE_DIR}/gow3link
            ${GOW3_REPO_ROOT}/tools/android/present)
    # -llog：__android_log_print（平台 liblog）；-landroid：gow3present 用
    # AHardwareBuffer_*（libandroid）。缺一则 dlopen 时报 "cannot locate symbol"。
    # 16KB 页对齐是 16KB 页设备（部分新机型）加载 native 库的硬性要求。
    target_link_libraries(${target} PRIVATE log android)
    target_link_options(${target} PRIVATE
            -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endforeach()
