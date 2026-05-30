cmake_minimum_required(VERSION 3.20)
project(mediainterface_winrt LANGUAGES CXX)

if (NOT WIN32)
    message(FATAL_ERROR "This project builds only on Windows.")
endif()

if (NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME is required to build the JNI DLL.")
endif()

set(JAVA_HOME $ENV{JAVA_HOME})

# Statically link the MSVC C++ runtime into the DLL (/MT) so we don't depend
# on the user having a compatible msvcp140/vcruntime140 installed. Shipping
# against the dynamic runtime (the CMake default /MD) produced a DLL that
# crashed with EXCEPTION_ACCESS_VIOLATION in msvcp140+0x13080 on systems
# where the runtime version diverged from the GitHub-Actions build image.
# Must be set BEFORE add_library so the policy applies to the target.
cmake_policy(SET CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded" CACHE STRING "" FORCE)

add_library(mediainterface_winrt SHARED
    bridge_shared.cpp
    bridge_seh.cpp
    bridge_init.cpp
    bridge_query.cpp
    bridge_controls.cpp
)

target_compile_features(mediainterface_winrt PRIVATE cxx_std_20)
target_compile_definitions(mediainterface_winrt PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)

# /EHa: asynchronous (SEH) exception handling so catch(...) intercepts
# access violations from WinRT's RPC layer during cold-boot service init.
# CMake injects /EHsc into CMAKE_CXX_FLAGS by default; strip it first so it
# cannot override /EHa regardless of flag ordering on the command line.
if (MSVC)
    string(REPLACE "/EHsc" "/EHa" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    target_compile_options(mediainterface_winrt PRIVATE /EHa)
endif()

target_include_directories(mediainterface_winrt PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/win32"
)

set_target_properties(mediainterface_winrt PROPERTIES
    OUTPUT_NAME "mediainterface_winrt"
)

# windowsapp = WinRT umbrella; windowscodecs = WIC (artwork downscale);
# ole32 = classic COM (CoCreateInstance + CreateStreamOnHGlobal).
target_link_libraries(mediainterface_winrt PRIVATE windowsapp windowscodecs ole32)
