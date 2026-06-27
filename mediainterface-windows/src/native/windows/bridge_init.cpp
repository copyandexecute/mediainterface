#include "bridge_shared.h"

#include <Windows.h>
#include <atomic>

// ---------------------------------------------------------------------------
// Dedicated WinRT apartment-owner thread.
//
// winrt::init_apartment / uninit_apartment are PER-THREAD: the apartment must
// be uninitialised on the exact thread that initialised it. The Java side calls
// nativeInit and nativeShutdown from two different short-lived worker threads
// ("SpotifyMediaState-init" / "-shutdown"), so a naive init-here / uninit-here
// scheme could never balance — the old code detected the thread mismatch and
// simply skipped uninit_apartment(), leaking the apartment until process exit.
//
// Instead we spawn one long-lived thread that owns the MTA for the whole
// library lifetime: it init_apartment()s, signals ready, then blocks until
// shutdown is requested and uninit_apartment()s on that same thread. The
// process-wide MTA stays alive the entire time, so the Java executor threads
// keep making WinRT calls via the implicit MTA exactly as before — only the
// teardown is now clean.
// ---------------------------------------------------------------------------

namespace {

HANDLE g_comThread = nullptr;
HANDLE g_comReady = nullptr;       // auto-reset: set once the init attempt finished
HANDLE g_comShutdown = nullptr;    // auto-reset: set to request uninit + exit
std::atomic<bool> g_comApartmentOk{false};

DWORD WINAPI com_apartment_thread(LPVOID) noexcept {
    bool ok = false;
    try {
        // /EHa (set in CMakeLists) lets catch(...) intercept a cold-boot access
        // violation from COM's activation code, same defence the bridge uses
        // elsewhere. A fresh thread has no prior apartment, so RPC_E_CHANGED_MODE
        // cannot occur here.
        init_apartment(apartment_type::multi_threaded);
        ok = true;
    } catch (...) {
        ok = false;
    }
    g_comApartmentOk.store(ok, std::memory_order_release);
    SetEvent(g_comReady);
    if (!ok) {
        return 0;
    }
    // Hold the MTA alive for the library's lifetime, then tear it down on this
    // same thread when shutdown is signalled.
    WaitForSingleObject(g_comShutdown, INFINITE);
    uninit_apartment();
    return 0;
}

void close_com_handles() {
    if (g_comThread) { CloseHandle(g_comThread); g_comThread = nullptr; }
    if (g_comReady) { CloseHandle(g_comReady); g_comReady = nullptr; }
    if (g_comShutdown) { CloseHandle(g_comShutdown); g_comShutdown = nullptr; }
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeInit(JNIEnv* env, jclass clazz, jboolean eventDriven) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeInit enter");
    g_eventDriven = (eventDriven == JNI_TRUE);
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (g_initRefCount > 0) {
        ++g_initRefCount;
        trace_native(env, "nativeInit reused existing apartment ref");
        return;
    }

    g_comApartmentOk.store(false, std::memory_order_release);
    g_comReady = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    g_comShutdown = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (g_comReady == nullptr || g_comShutdown == nullptr) {
        close_com_handles();
        trace_native(env, "nativeInit failed to create apartment-thread events");
        throw_illegal_state(env, "Failed to create WinRT apartment thread events");
        return;
    }

    g_comThread = CreateThread(nullptr, 0, com_apartment_thread, nullptr, 0, nullptr);
    if (g_comThread == nullptr) {
        close_com_handles();
        trace_native(env, "nativeInit failed to start apartment thread");
        throw_illegal_state(env, "Failed to start WinRT apartment thread");
        return;
    }

    trace_native(env, "nativeInit waiting for apartment thread");
    WaitForSingleObject(g_comReady, INFINITE);
    if (!g_comApartmentOk.load(std::memory_order_acquire)) {
        // init_apartment() failed — the thread has already returned. Join + clean.
        WaitForSingleObject(g_comThread, 5000);
        close_com_handles();
        trace_native(env, "nativeInit apartment thread failed to initialize MTA");
        throw_illegal_state(env, "Failed to initialize WinRT apartment");
        return;
    }

    g_initRefCount = 1;
    trace_native(env, "nativeInit success (dedicated apartment thread)");
}

extern "C" JNIEXPORT void JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeShutdown(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeShutdown enter");
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (g_initRefCount == 0) {
        trace_native(env, "nativeShutdown no-op refCount=0");
        return;
    }
    --g_initRefCount;
    if (g_initRefCount > 0) {
        trace_native(env, "nativeShutdown decremented but retained apartment");
        return;
    }

    // Last reference. Release the cached SMTC manager (a COM object) while the
    // MTA is still alive, then signal the owner thread to uninit + exit and
    // join it so the apartment is torn down deterministically.
    invalidate_manager_cache();
    if (g_comThread != nullptr) {
        trace_native(env, "nativeShutdown signalling apartment thread to uninit");
        SetEvent(g_comShutdown);
        WaitForSingleObject(g_comThread, 5000);
        close_com_handles();
    }
    trace_native(env, "nativeShutdown apartment released");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeIsEventDrivenEnabled(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, std::string("nativeIsEventDrivenEnabled -> ") + (g_eventDriven ? "true" : "false"));
    return g_eventDriven ? JNI_TRUE : JNI_FALSE;
}
