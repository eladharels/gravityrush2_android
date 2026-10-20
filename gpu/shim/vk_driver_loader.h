// gow3port: 全进程唯一加载 Vulkan 驱动的地方（见 vk_driver_loader.cpp）。
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*Gow3VkVoidFunction)(void);
typedef Gow3VkVoidFunction (*Gow3VkGetInstanceProcAddr)(void* instance, const char* name);

/// 只加载一次 Vulkan 驱动（GOW3_VULKAN_DRIVER 未设置：系统 loader 的 libvulkan.so；设置为路径：
/// 直接加载那个驱动库）并返回它的 vkGetInstanceProcAddr，失败返回 NULL（已打日志）。
/// 进程里其它所有 Vulkan 函数都通过它解析。
Gow3VkGetInstanceProcAddr gow3_vk_get_instance_proc_addr(void);

#ifdef __cplusplus
}
#endif
