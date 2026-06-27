#include "bridge_shared.h"

#include <atomic>
#include <chrono>
#include <sstream>
#include <thread>
#include <unordered_set>

bool g_eventDriven = true;
std::mutex g_initMutex;
int g_initRefCount = 0;

std::mutex g_traceMutex;
jclass g_bridgeClassGlobal = nullptr;
jmethodID g_traceMethod = nullptr;

std::string to_utf8(jstring value, JNIEnv* env) {
    if (value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv* env, const std::string& value) {
    hstring wide = to_hstring(value);
    auto* chars = reinterpret_cast<const jchar*>(wide.c_str());
    return env->NewString(chars, static_cast<jsize>(wide.size()));
}

jobjectArray new_string_array(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (array == nullptr) {
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
        env->SetObjectArrayElement(array, i, to_jstring(env, values[i]));
    }
    return array;
}

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    if (env == nullptr || env->ExceptionCheck()) {
        return;
    }
    jclass exClass = env->FindClass("java/lang/IllegalStateException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, message.c_str());
    }
}

void ensure_trace_bridge(JNIEnv* env, jclass bridgeClass) {
    if (env == nullptr || bridgeClass == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_traceMutex);
    if (g_bridgeClassGlobal != nullptr && g_traceMethod != nullptr) {
        return;
    }

    if (g_bridgeClassGlobal == nullptr) {
        g_bridgeClassGlobal = static_cast<jclass>(env->NewGlobalRef(bridgeClass));
        if (g_bridgeClassGlobal == nullptr) {
            return;
        }
    }
    if (g_traceMethod == nullptr) {
        g_traceMethod = env->GetStaticMethodID(g_bridgeClassGlobal, "traceFromNative", "(Ljava/lang/String;)V");
        if (g_traceMethod == nullptr) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }
}

void trace_native(JNIEnv* env, const std::string& message) {
    if (env == nullptr) {
        return;
    }
    jclass bridgeClass = nullptr;
    jmethodID traceMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_traceMutex);
        bridgeClass = g_bridgeClassGlobal;
        traceMethod = g_traceMethod;
    }
    if (bridgeClass == nullptr || traceMethod == nullptr) {
        return;
    }
    jstring msg = to_jstring(env, message);
    if (msg == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }
    env->CallStaticVoidMethod(bridgeClass, traceMethod, msg);
    env->DeleteLocalRef(msg);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

void trace_hresult(JNIEnv* env, const char* context, const hresult_error& e) {
    std::ostringstream out;
    out << context << " failed HRESULT=0x" << std::hex << static_cast<uint32_t>(e.code().value);
    trace_native(env, out.str());
}

int64_t ticks_to_millis(int64_t ticks) {
    return ticks / 10000;
}

int64_t millis_to_ticks(int64_t millis) {
    return millis * 10000;
}

// Set to true after the first successful RequestAsync() call. Once warm,
// the factory stays accessible and we skip the thread-based slow path.
static std::atomic<bool> g_factory_warm{false};

// The SMTC manager is a long-lived live object: GetSessions() on it always
// reflects the current set of sessions. Requesting a fresh one on every native
// call (RequestAsync().get() per playbackState/capabilities/nowPlaying poll,
// per session, per second) hammered the WinRT activation broker and leaked
// OS-side handles over multi-hour sessions. Request once, cache, reuse.
static std::mutex g_managerMutex;
static std::optional<GlobalSystemMediaTransportControlsSessionManager> g_cachedManager;

void invalidate_manager_cache() {
    std::lock_guard<std::mutex> lock(g_managerMutex);
    g_cachedManager.reset();
}

std::optional<GlobalSystemMediaTransportControlsSessionManager> request_manager_safe(JNIEnv* env) {
    // Cache hit: hand back a copy (COM AddRef — cheap, safe from any MTA thread).
    {
        std::lock_guard<std::mutex> lock(g_managerMutex);
        if (g_cachedManager.has_value()) {
            return g_cachedManager;
        }
    }

    // Fast path: factory already warmed up, request directly (cache was
    // invalidated after a failure — re-request and re-cache).
    if (g_factory_warm.load(std::memory_order_acquire)) {
        try {
            auto manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
            std::lock_guard<std::mutex> lock(g_managerMutex);
            g_cachedManager = manager;
            return g_cachedManager;
        } catch (...) {
            return std::nullopt;
        }
    }

    // Cold path: run RequestAsync() on a dedicated Win32 thread so that
    // __try/__except can catch the cold-boot AV without JVM frames interfering.
    constexpr int kMaxAttempts = 3;
    constexpr auto kRetryDelay = std::chrono::milliseconds(750);
    for (int attempt = 0; attempt < kMaxAttempts; ++attempt) {
        if (attempt > 0) {
            trace_native(env, std::string("request_manager_safe: retry attempt=") + std::to_string(attempt));
            std::this_thread::sleep_for(kRetryDelay);
        }
        trace_native(env, std::string("request_manager_safe: cold attempt=") + std::to_string(attempt));
        GlobalSystemMediaTransportControlsSessionManager manager{nullptr};
        if (smtc_try_request_manager(&manager, env)) {
            g_factory_warm.store(true, std::memory_order_release);
            trace_native(env, "request_manager_safe: factory warmed, success");
            std::lock_guard<std::mutex> lock(g_managerMutex);
            g_cachedManager = manager;
            return g_cachedManager;
        }
        trace_native(env, std::string("request_manager_safe: failed attempt=") + std::to_string(attempt));
    }
    trace_native(env, "request_manager_safe: all attempts failed, returning nullopt");
    return std::nullopt;
}

std::optional<GlobalSystemMediaTransportControlsSession> find_session(const std::string& sessionId, JNIEnv* env) {
    trace_native(env, std::string("find_session request id=") + sessionId);
    auto manager = request_manager_safe(env);
    if (!manager.has_value()) {
        trace_native(env, "find_session: manager unavailable");
        return std::nullopt;
    }
    try {
        for (auto const& session : manager.value().GetSessions()) {
            if (to_string(session.SourceAppUserModelId()) == sessionId) {
                trace_native(env, std::string("find_session hit id=") + sessionId);
                return session;
            }
        }
    } catch (...) {
        // Cached manager went stale (session-manager restart, user logoff, ...).
        // Drop it so the next call re-requests a fresh one.
        invalidate_manager_cache();
        trace_native(env, "find_session: GetSessions threw, invalidated manager cache");
        return std::nullopt;
    }
    trace_native(env, std::string("find_session miss id=") + sessionId);
    return std::nullopt;
}
