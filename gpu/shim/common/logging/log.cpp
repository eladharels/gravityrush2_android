// gow3: replaces shadPS4's spdlog-based logger with a small stderr sink behind the upstream
// common/logging/log.h interface. The level comes from GOW3_GPU_LOG
// (trace|debug|info|warning|error|critical; default warning) and applies to every log class.
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <fmt/format.h>
#include "common/logging/log.h"

namespace Common::Log {

static Level Threshold() {
    const char* v = std::getenv("GOW3_GPU_LOG");
    if (!v) {
        return Level::Warning;
    }
    static constexpr const char* names[] = {"trace", "debug", "info", "warning", "error",
                                            "critical"};
    for (int i = 0; i < 6; ++i) {
        if (!std::strcmp(v, names[i])) {
            return Level(i);
        }
    }
    return Level::Warning;
}

std::array<Level, NUM_LOG_CLASSES> g_class_levels = [] {
    std::array<Level, NUM_LOG_CLASSES> levels{};
    levels.fill(Threshold());
    return levels;
}();

void VLog(Class log_class, Level level, const char* file, int line, const char* func,
          fmt::string_view format, fmt::format_args args) {
    static std::mutex mutex;
    static constexpr const char* names[] = {"Trace", "Debug",    "Info", "Warning",
                                            "Error", "Critical", "Off"};
    const std::string message = fmt::vformat(format, args);
    const char* base = std::strrchr(file, '/');
    if (const char* back = std::strrchr(file, '\\'); back && (!base || back > base)) {
        base = back;
    }
    const auto name = NameOf(log_class);
    std::scoped_lock lock{mutex};
    std::fprintf(stderr, "GPU [%.*s] <%s> %s:%d %s: %s\n", int(name.size()), name.data(),
                 names[static_cast<int>(level)], base ? base + 1 : file, line, func,
                 message.c_str());
}

void Flush() {}

} // namespace Common::Log
