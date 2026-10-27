// gow3: host threads that may call guest code (AvPlayer allocator callbacks).
// Each thread gets a guest TCB (GS base, TLS) from the C runtime before running.
#pragma once
#include <atomic>
#include <chrono>
#include <cstdio>
#include <functional>
#include <memory>
#include <stop_token>
#include <system_error>
#include <thread>
#include "common/types.h"

extern "C" void runtime_thread_attach_host(const char* name);

namespace Libraries::Kernel {
class Thread {
public:
    Thread() = default;
    ~Thread() { Stop(); }
    void Run(std::function<void(std::stop_token)>&& func) {
        finished = std::make_shared<std::atomic<bool>>(false);
        thread = std::jthread([func = std::move(func), done = finished](std::stop_token stop) {
            runtime_thread_attach_host("gow3:hle");
            func(stop);
            done->store(true);
        });
    }
    // A thread may stop its own Thread object (AvPlayer does); it detaches instead of joining.
    // gow3: these are called from guest code, which a C++ exception cannot unwind: a failed
    // join is logged and the thread detached instead.
    void Join() {
        if (!thread.joinable()) return;
        if (thread.get_id() == std::this_thread::get_id()) {
            // gravityrush2_android: Bionic's pthread_detach fails with EINVAL while another
            // thread is joining this one (Gravity Rush 2 stops an AvPlayer from its game thread
            // while the player's controller thread stops itself). The joiner waits for it to end;
            // this object only has to stop owning it, or its destructor would join itself.
            Release();
            return;
        }
        try {
            thread.join();
        } catch (const std::system_error& error) {
            // Windows (libc++ on Win32 threads): the join can fail although the thread is fine.
            // Wait for its function to return, so nothing it uses is freed under it, then let go.
            std::fprintf(stderr, "HLE thread: join failed (%s); waiting for it to finish\n", error.what());
            for (int waited = 0; finished && !finished->load() && waited < 5000; ++waited) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
            Release();
        }
    }
    bool Joinable() const { return thread.joinable(); }
    void Stop() {
        if (thread.joinable()) {
            thread.request_stop();
            Join();
        }
    }

private:
    // Lets go of the thread without an exception reaching guest code: detached if possible,
    // else moved to a jthread that is never destroyed (its destructor would join or terminate).
    void Release() {
        if (!thread.joinable()) return;
        try {
            thread.detach();
        } catch (const std::system_error& error) {
            std::fprintf(stderr, "HLE thread: detach failed (%s); thread object kept\n", error.what());
            new std::jthread(std::move(thread));
        }
    }
    std::jthread thread;
    std::shared_ptr<std::atomic<bool>> finished;
};
} // namespace Libraries::Kernel
