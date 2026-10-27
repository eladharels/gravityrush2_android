// SPDX-License-Identifier: GPL-2.0-or-later
// gow3port: the JNI face of gow3_prepare (see gow3_prepare.h), called by com.gow3.android.data.Gow3Prepare.
//
// Thin on purpose: the launcher already parses the patch XML and knows the selection, so it passes
// the flattened text in and gets a report JSON back. Nothing here parses XML or JSON, and nothing
// hashes - the launcher does that with MessageDigest.
#include <jni.h>

#include <fstream>
#include <string>
#include <vector>

#include "gow3_link.h"
#include "gow3_prepare.h"

namespace {

std::string ToStdString(JNIEnv* env, jstring value) {
    if (!value) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

bool ReadFile(const std::string& path, std::vector<u8>& out) {
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        return false;
    }
    out.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
    return true;
}

bool WriteFile(const std::string& path, const std::vector<u8>& data) {
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file) {
        return false;
    }
    file.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    return file.good();
}

} // namespace

extern "C" {

/**
 * Compiles the selected patches for one game and writes <outDir>/patches.bin.
 * Returns the patch status as JSON, or a report carrying reason "prepare-failed" when the game's
 * SELF could not be read - the launcher shows that instead of starting a game that cannot boot.
 */
JNIEXPORT jstring JNICALL Java_com_gow3_android_data_Gow3Prepare_nativeCompile(
    JNIEnv* env, jclass, jstring eboot_path, jstring patch_input, jstring out_dir, jstring app_version,
    jobjectArray selected, jint base_dmem_mb, jint engine_fps, jlong device_mem_mb) {
    using namespace gow3_prepare;
    const std::string eboot_path_str = ToStdString(env, eboot_path);
    const std::string input = ToStdString(env, patch_input);
    const std::string out_dir_str = ToStdString(env, out_dir);
    const std::string version = ToStdString(env, app_version);

    Report report;
    std::vector<std::string> names;
    if (selected) {
        const jsize count = env->GetArrayLength(selected);
        for (jsize i = 0; i < count; ++i) {
            auto element = static_cast<jstring>(env->GetObjectArrayElement(selected, i));
            if (element) {
                names.push_back(ToStdString(env, element));
                env->DeleteLocalRef(element);
            }
        }
    }
    report.selected = names;
    report.requested = names;

    std::vector<u8> raw;
    if (!ReadFile(eboot_path_str, raw)) {
        report.reason = "prepare-failed";
        return env->NewStringUTF(ReportJson("", version, "", report).c_str());
    }
    Eboot eboot;
    std::string error;
    if (!ParseSelf(raw.data(), raw.size(), eboot.elf, error) || !FlattenLoads(eboot.elf, eboot, error)) {
        report.reason = "prepare-failed";
        report.skipped.emplace_back("eboot", error.empty() ? "cannot read the game image" : error);
        return env->NewStringUTF(ReportJson("", version, "", report).c_str());
    }

    std::vector<Patch> patches;
    if (!ParsePatchInput(input, patches, error)) {
        report.reason = "prepare-failed";
        report.skipped.emplace_back("patches", error);
        return env->NewStringUTF(ReportJson("", version, "", report).c_str());
    }

    std::vector<Write> writes;
    const std::string disabled = SelectionGate(names);
    if (!disabled.empty()) {
        for (const auto& name : names) {
            report.skipped.emplace_back(name, disabled);
        }
        report.reason = disabled;
    } else {
        writes = CompilePatches(eboot, patches, names, version, report);
        report.reason = report.applied.empty()
                            ? (report.skipped.empty() ? "no-patches" : "no-applicable-patches")
                            : (report.skipped.empty() ? "compiled" : "partially-compiled");
    }

    // DMEM and VBlank come from the notes of the patches that compiled, on top of the platform
    // base the launcher passes - the same arithmetic compile_plan does.
    Effective& effective = report.effective;
    PatchRequirements(patches, report.applied, version, effective);
    const u64 budget = static_cast<u64>(device_mem_mb);
    effective.device_mem_mb = budget;
    effective.budget_checked = budget > 0;
    if (!report.reason.empty() && report.reason != "compiled" &&
        report.reason != "partially-compiled") {
        effective.dmem_mb = static_cast<u32>(base_dmem_mb);
        effective.dmem_source = "platform-default";
    } else {
        effective.dmem_mb = static_cast<u32>(base_dmem_mb) > effective.minimum_dmem_mb
                                ? static_cast<u32>(base_dmem_mb)
                                : effective.minimum_dmem_mb;
        effective.dmem_source = effective.minimum_dmem_mb ? "compiled-patches" : "platform-default";
    }
    if (effective.minimum_dmem_mb && effective.budget_checked &&
        effective.minimum_dmem_mb > budget) {
        report.reason = "insufficient-memory-budget";
    }
    effective.vblank_hz = effective.vblank_hz ? effective.vblank_hz
                                               : (engine_fps > 0 ? static_cast<u32>(engine_fps) : 60);
    effective.vblank_source = "compiled-patches";
    for (const auto& name : names) {
        if (name.rfind("Resolution Patch - ", 0) == 0) {
            effective.render_resolution = name.substr(std::string("Resolution Patch - ").size());
        }
    }
    report.write_count = writes.size();
    for (const auto& write : writes) {
        report.write_bytes += write.data.size();
    }

    if (!WriteFile(out_dir_str + "/patches.bin", PatchBlob(writes))) {
        report.reason = "prepare-failed";
        report.skipped.emplace_back("patches.bin", "cannot write into " + out_dir_str);
    }
    return env->NewStringUTF(ReportJson("", version, "", report).c_str());
}

/** Writes <outDir>/content.bin: the AppContent profile probe.c validates before the run. */
JNIEXPORT jboolean JNICALL Java_com_gow3_android_data_Gow3Prepare_nativeContentProfile(JNIEnv* env, jclass,
                                                                                   jstring out_dir,
                                                                                   jint sku,
                                                                                   jintArray user_params) {
    const std::string dir = ToStdString(env, out_dir);
    u32 params[4] = {0, 0, 0, 0};
    if (user_params && env->GetArrayLength(user_params) == 4) {
        jint values[4];
        env->GetIntArrayRegion(user_params, 0, 4, values);
        for (int i = 0; i < 4; ++i) {
            params[i] = static_cast<u32>(values[i]);
        }
    }
    return WriteFile(dir + "/content.bin",
                     gow3_prepare::ContentProfile(static_cast<u32>(sku), params))
               ? JNI_TRUE
               : JNI_FALSE;
}

} // extern "C"

// ---------------------------------------------------------------- gow3_link
// The boot image is built here too so the launcher needs one native library, and because it is the
// same eboot the patches are compiled against: one parse, one set of segment checks.

extern "C" {

namespace {

void ThrowPrepare(JNIEnv* env, const std::string& what) {
    jclass failure = env->FindClass("java/lang/RuntimeException");
    if (failure) {
        env->ThrowNew(failure, what.c_str());
    }
}

} // namespace

JNIEXPORT jboolean JNICALL Java_com_gow3_android_data_Gow3Prepare_nativeBootLinked(
    JNIEnv* env, jclass, jstring game_dir, jstring out_dir, jobjectArray module_files, jstring executable_name) {
    const std::string game = ToStdString(env, game_dir);
    const std::string out = ToStdString(env, out_dir);
    // uncharted_android: the game's executable (eboot.bin, big2-/big3-ps4_Shipping.elf).
    std::string executable = executable_name ? ToStdString(env, executable_name) : std::string();
    if (executable.empty()) executable = "eboot.bin";
    std::vector<u8> eboot, libc;
    if (!ReadFile(game + "/" + executable, eboot)) {
        ThrowPrepare(env, "cannot read " + game + "/" + executable);
        return JNI_FALSE;
    }
    const jsize count = module_files ? env->GetArrayLength(module_files) : 0;
    std::vector<std::vector<u8>> modules;
    std::string first = "libc.prx";
    for (jsize i = 0; i < count; ++i) {
        auto element = static_cast<jstring>(env->GetObjectArrayElement(module_files, i));
        const std::string name = ToStdString(env, element);
        env->DeleteLocalRef(element);
        std::vector<u8> bytes;
        // gravityrush2_android: a name with a '/' is relative to the game folder
        // (sysdata/libSceSecure.prx), the others are in sce_module/.
        const std::string path = name.find('/') != std::string::npos ? game + "/" + name : game + "/sce_module/" + name;
        if (!ReadFile(path, bytes)) {
            // 常见误选：把 -patch 升级目录当成游戏目录（它也有 eboot.bin，能过选择器校验），
            // 但 sce_module 只在基础游戏目录里。给出可行动的提示而不是裸路径。
            std::string hint;
            if (game.size() > 6 && game.compare(game.size() - 6, 6, "-patch") == 0)
                hint = " (this looks like the -patch update folder: select the base game folder, e.g. CUSA01740, as the game folder)";
            else if (name != "libc.prx")
                hint = " (check the linked_modules setting in gow3.ini)";
            ThrowPrepare(env, "cannot read " + path + hint);
            return JNI_FALSE;
        }
        if (i == 0) {
            first = name;
            libc = bytes;
        }
        modules.push_back(std::move(bytes));
    }
    if (libc.empty()) {
        ThrowPrepare(env, "the first module has to be the game's libc");
        return JNI_FALSE;
    }
    std::string error;
    std::vector<u8> boot, linked;
    if (!gow3_link::PrepareBoot(eboot.data(), eboot.size(), libc.data(), libc.size(), boot, error)) {
        ThrowPrepare(env, "cannot prepare " + game + "/" + executable + ": " + error);
        return JNI_FALSE;
    }
    if (!gow3_link::LinkBoot(boot, eboot, modules, linked, error)) {
        ThrowPrepare(env, "cannot link " + first + ": " + error);
        return JNI_FALSE;
    }
    if (!WriteFile(out + "/boot-linked.bin", linked)) {
        ThrowPrepare(env, "cannot write boot-linked.bin into " + out);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

} // extern "C"
