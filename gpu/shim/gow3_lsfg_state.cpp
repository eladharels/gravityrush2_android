// gow3port: see gow3_lsfg_state.h.
#ifdef __ANDROID__
#include "gow3_lsfg_state.h"

#include <sys/stat.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace Gow3LsfgState {

namespace {

float ClampFlow(double flow) {
    return static_cast<float>(std::clamp(flow, 0.25, 1.0));
}

bool IsFile(const std::string& path) {
    struct stat st{};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0;
}

} // namespace

const char* FilePath() {
    const char* path = std::getenv("GOW3_LSFG_STATE_FILE");
    return path && *path ? path : nullptr;
}

bool Read(const char* path, State& state) {
    std::FILE* file = std::fopen(path, "r");
    if (!file) {
        return false;
    }
    char text[512];
    const size_t size = std::fread(text, 1, sizeof text - 1, file);
    std::fclose(file);
    text[size] = '\0';
    State parsed = state;
    bool has_multiplier = false;
    const char* line = text;
    while (const char* end = std::strchr(line, '\n')) { // complete lines only
        const std::string entry(line, end);
        line = end + 1;
        const size_t eq = entry.find('=');
        if (eq == std::string::npos) {
            continue;
        }
        const std::string key = entry.substr(0, eq);
        const char* value = entry.c_str() + eq + 1;
        char* stop = nullptr;
        if (key == "multiplier") {
            const long m = std::strtol(value, &stop, 10);
            if (stop != value) {
                parsed.multiplier = m >= 2 && m <= 4 ? static_cast<u32>(m) : 0;
                has_multiplier = true;
            }
        } else if (key == "flow") {
            const double flow = std::strtod(value, &stop);
            if (stop != value) {
                parsed.flow = ClampFlow(flow);
            }
        } else if (key == "performance") {
            const long p = std::strtol(value, &stop, 10);
            if (stop != value) {
                parsed.performance = p != 0;
            }
        } else if (key == "fp16") {
            const long h = std::strtol(value, &stop, 10);
            if (stop != value) {
                parsed.fp16 = h != 0;
            }
        }
    }
    if (!has_multiplier) {
        return false;
    }
    state = parsed;
    return true;
}

State FromEnvironment() {
    State state;
    const char* multiplier = std::getenv("GOW3_LSFG_MULTIPLIER");
    const long m = multiplier ? std::strtol(multiplier, nullptr, 10) : 0;
    state.multiplier = m >= 2 && m <= 4 ? static_cast<u32>(m) : 0;
    if (const char* flow = std::getenv("GOW3_LSFG_FLOW_SCALE"); flow && *flow) {
        char* stop = nullptr;
        const double parsed = std::strtod(flow, &stop);
        if (stop != flow) {
            state.flow = ClampFlow(parsed);
        }
    }
    if (const char* performance = std::getenv("GOW3_LSFG_PERFORMANCE"); performance && *performance) {
        state.performance = std::strtol(performance, nullptr, 10) != 0;
    }
    if (const char* fp16 = std::getenv("GOW3_LSFG_FP16"); fp16 && *fp16) {
        state.fp16 = std::strtol(fp16, nullptr, 10) != 0;
    }
    return state;
}

State Initial() {
    State state = FromEnvironment();
    if (const char* path = FilePath()) {
        Read(path, state);
    }
    return state;
}

std::string FindDll() {
    if (const char* env = std::getenv("GOW3_LSFG_DLL"); env && IsFile(env)) {
        return env;
    }
    if (const char* path = FilePath()) {
        std::string beside = path;
        const size_t slash = beside.rfind('/');
        beside = (slash == std::string::npos ? std::string{} : beside.substr(0, slash + 1)) + "Lossless.dll";
        if (IsFile(beside)) {
            return beside;
        }
    }
    // gow3port：游戏目录同级（GOW3_GAME_DIR 由 GameActivity 传入）与应用私有
    // gow3port/（Home 导入/Steam 下载的落点）。BB 的 Bloodborne/ 路径不适用。
    if (const char* game = std::getenv("GOW3_GAME_DIR"); game && *game) {
        std::string beside = game;
        if (beside.back() == '/') beside.pop_back();
        beside = beside.substr(0, beside.rfind('/')) + "/Lossless.dll";
        if (IsFile(beside)) {
            return beside;
        }
    }
    if (const char* state = FilePath()) {
        // state = <files>/gow3data/framegen.state → <files>/gow3port/Lossless.dll
        std::string files = state;
        files = files.substr(0, files.find("/gow3data/"));
        const std::string own = files + "/gow3port/Lossless.dll";
        if (IsFile(own)) {
            return own;
        }
    }
    return std::string{};
}

bool Watcher::Poll(State& state) {
    const char* path = FilePath();
    if (!path) {
        return false;
    }
    const auto now = std::chrono::steady_clock::now();
    if (last_check != std::chrono::steady_clock::time_point{} &&
        now - last_check < std::chrono::milliseconds(250)) {
        return false;
    }
    last_check = now;
    struct stat st{};
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) {
        return false; // missing: no change
    }
    const long long time = static_cast<long long>(st.st_mtim.tv_sec) * 1'000'000'000LL + st.st_mtim.tv_nsec;
    const long long size = static_cast<long long>(st.st_size);
    const unsigned long long inode = static_cast<unsigned long long>(st.st_ino);
    if (seen && time == signature_time && size == signature_size && inode == signature_inode) {
        return false;
    }
    const bool first = !seen;
    seen = true;
    signature_time = time;
    signature_size = size;
    signature_inode = inode;
    State next = last;
    if (!Read(path, next)) {
        return false; // partial, empty or unreadable: wait for the next write
    }
    // The first look is the file the game started with: nothing to apply unless it differs.
    if (first && next == last) {
        return false;
    }
    last = next;
    state = next;
    return true;
}

} // namespace Gow3LsfgState
#endif // __ANDROID__
