#pragma once

#include <jni.h>
#include <winerror.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <winrt/Windows.Storage.Streams.h>

#include <chrono>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Media::Control;
using namespace Windows::Security::Cryptography;
using namespace Windows::Storage::Streams;

inline constexpr std::chrono::milliseconds kAsyncTimeout{2000};

// A native .get() can't be interrupted by Java shutdownNow(); without a timeout a hung
// cross-process call leaks the calling thread. On timeout: cancel + throw → caller's catch.
template <typename TOp>
auto await_with_timeout(TOp const& op, std::chrono::milliseconds timeout = kAsyncTimeout) {
    if (op.wait_for(timeout) != winrt::Windows::Foundation::AsyncStatus::Completed) {
        op.Cancel();
        throw winrt::hresult_error(HRESULT_FROM_WIN32(ERROR_TIMEOUT), L"WinRT async timed out");
    }
    return op.GetResults();
}

extern bool g_eventDriven;
extern std::mutex g_initMutex;
extern int g_initRefCount;

extern std::mutex g_traceMutex;
extern jclass g_bridgeClassGlobal;
extern jmethodID g_traceMethod;

std::string to_utf8(jstring value, JNIEnv* env);
jstring to_jstring(JNIEnv* env, const std::string& value);
jobjectArray new_string_array(JNIEnv* env, const std::vector<std::string>& values);
void throw_illegal_state(JNIEnv* env, const std::string& message);
void ensure_trace_bridge(JNIEnv* env, jclass bridgeClass);
void trace_native(JNIEnv* env, const std::string& message);
void trace_hresult(JNIEnv* env, const char* context, const hresult_error& e);
int64_t ticks_to_millis(int64_t ticks);
int64_t millis_to_ticks(int64_t millis);
// Calls RequestAsync().get() on a dedicated Win32 thread with two-layer
// exception defence (C++ try/catch + SEH __try/__except).
// Returns true and fills *out on success, false on any exception or timeout.
// Heap-allocates context with refcount so a timeout cannot use-after-free.
bool smtc_try_request_manager(GlobalSystemMediaTransportControlsSessionManager* out, JNIEnv* env) noexcept;

// Wraps smtc_try_request_manager with up to 3 retries for cold-boot AVs.
// The successfully-obtained manager is cached and reused on subsequent calls;
// invalidate_manager_cache() drops it so the next call re-requests.
std::optional<GlobalSystemMediaTransportControlsSessionManager> request_manager_safe(JNIEnv* env);

// Drops the cached SMTC manager (call when a WinRT op on it fails, or before
// uninit_apartment so the COM ref doesn't outlive the apartment).
void invalidate_manager_cache();
std::optional<GlobalSystemMediaTransportControlsSession> find_session(const std::string& sessionId, JNIEnv* env = nullptr);
