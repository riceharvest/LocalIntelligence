# llama.cpp JNI for PiDroid. Built by :android via externalNativeBuild.
#
# The llama.cpp sources are NOT vendored in this repository: they are ~200 MB
# of upstream and they move. This file fetches a pinned tag with
# FetchContent, so a build is reproducible from the pin rather than from
# whatever happened to be on disk.
#
# -----------------------------------------------------------------------
# To build with the native library (required for a real APK):
#
#   git submodule update --init --recursive
#   ./gradlew :android:assembleDebug
#
# ...or, without submodules, let CMake fetch it (needs network on the first
# build; sources are then cached in android/.cxx for subsequent builds):
#
#   PIDROID_LLAMA_FETCH=1 ./gradlew :android:assembleDebug
#
# ...or point at a checkout you already have:
#
#   PIDROID_LLAMA_DIR=/path/to/llama.cpp ./gradlew :android:assembleDebug
#
# These are environment variables rather than Gradle -P properties because AGP
# does not forward -P values to CMake as -D defines, and the Gradle build file
# belongs to another workstream.
#
# Building llama.cpp for arm64-v8a AND x86_64 is a heavy cross-compile: expect
# roughly 5-15 minutes cold on a laptop and 1-2 GB of disk in android/.cxx.
# There is no way around that. It is a real C++ project with a few hundred
# translation units, and ggml's CPU kernels are compiled once per ABI. A warm
# build (sources already in .cxx) is about a minute.
#
# -----------------------------------------------------------------------

cmake_minimum_required(VERSION 3.22.1)
project(pidroid_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -----------------------------------------------------------------------
# Locate llama.cpp
# -----------------------------------------------------------------------
#
# Resolution order:
#   1. PIDROID_LLAMA_DIR     - an explicit path
#   2. <repo>/llama.cpp      - a submodule checkout at the repo root
#   3. FetchContent          - when PIDROID_LLAMA_FETCH is ON
#
# If none of these produce sources the configure step FAILS LOUDLY rather than
# producing a stub library. A libpidroid_llama_jni.so with no llama.cpp behind
# it would build clean, load clean, and then fail at the first JNI call, which
# is far more expensive to diagnose than a build error naming the fix.

set(PIDROID_LLAMA_DIR "" CACHE PATH "Path to a llama.cpp source checkout")
set(PIDROID_LLAMA_TAG "b4661" CACHE STRING "llama.cpp git tag to build against")
option(PIDROID_LLAMA_FETCH "Fetch llama.cpp with FetchContent if not found locally" OFF)

# AGP does not forward Gradle `-P` properties to CMake as -D defines, and
# android/build.gradle.kts is owned by another workstream, so the supported way
# to point this at a checkout without editing the build file is an environment
# variable. CMake inherits the Gradle process's environment, so:
#
#   PIDROID_LLAMA_DIR=/path/to/llama.cpp ./gradlew :android:assembleDebug
#   PIDROID_LLAMA_FETCH=1            ./gradlew :android:assembleDebug
#
# Both are documented at the top of this file. A future change to
# android/build.gradle.kts can pass them through as -D arguments instead; the
# cache variables above already work for that.
if(NOT PIDROID_LLAMA_DIR AND DEFINED ENV{PIDROID_LLAMA_DIR})
    set(PIDROID_LLAMA_DIR "$ENV{PIDROID_LLAMA_DIR}")
endif()
if(NOT PIDROID_LLAMA_FETCH AND DEFINED ENV{PIDROID_LLAMA_FETCH})
    set(PIDROID_LLAMA_FETCH ON)
endif()

set(_llama_src_dir "")

if(PIDROID_LLAMA_DIR AND EXISTS "${PIDROID_LLAMA_DIR}/include/llama.h")
    set(_llama_src_dir "${PIDROID_LLAMA_DIR}")
    message(STATUS "llama.cpp: using PIDROID_LLAMA_DIR=${_llama_src_dir}")
endif()

if(NOT _llama_src_dir)
    # Submodule layout: <repo>/android/src/main/cpp -> <repo>/llama.cpp
    get_filename_component(_repo_root "${CMAKE_CURRENT_SOURCE_DIR}/../../../../" ABSOLUTE)
    if(EXISTS "${_repo_root}/llama.cpp/include/llama.h")
        set(_llama_src_dir "${_repo_root}/llama.cpp")
        message(STATUS "llama.cpp: using submodule at ${_llama_src_dir}")
    endif()
endif()

if(NOT _llama_src_dir AND PIDROID_LLAMA_FETCH)
    message(STATUS "llama.cpp: FetchContent ${PIDROID_LLAMA_TAG} (first build only)")
    include(FetchContent)
    # GIT_SHALLOW keeps the clone to one commit: the tag is a fixed point, so
    # history buys nothing and costs ~200 MB.
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${PIDROID_LLAMA_TAG}
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(llama)
    set(_llama_src_dir "${llama_SOURCE_DIR}")
endif()

if(NOT _llama_src_dir)
    message(FATAL_ERROR
        "llama.cpp sources not found.\n"
        "Run one of:\n"
        "  git submodule update --init --recursive\n"
        "  PIDROID_LLAMA_FETCH=1 ./gradlew :android:assembleDebug\n"
        "or point CMake at a checkout:\n"
        "  PIDROID_LLAMA_DIR=/path/to/llama.cpp ./gradlew :android:assembleDebug\n"
        "See android/src/main/cpp/CMakeLists.txt for details.")
endif()

# -----------------------------------------------------------------------
# Build llama.cpp
# -----------------------------------------------------------------------
#
# ggml/llama are built as static libraries: shipping them as .so files would
# mean three native libraries to keep in step, and llama.cpp does not offer a
# stable C ABI for third-party consumers. Static keeps one .so to ship.
#
# Options chosen for a phone, not for a workstation:
#   GGML_NATIVE         OFF  - the build machine is not the phone. -march=native
#                               would produce a binary that faults on most
#                               phones.
#   GGML_LTO            OFF  - a multi-minute LTO link per ABI for a ~2% size
#                               win on a library dwarfed by the model file.
#   GGML_OPENMP         OFF  - bionic ships no OpenMP runtime to link against;
#                               llama.cpp uses its own thread pool.
#   GGML_CPU_ALL_VARIANTS OFF - that flag builds every micro-arch variant (NEON
#                               + dotprod + fp16 arithmetic). For a 1-4B model
#                               the win is a few percent of tokens/sec for 3x
#                               the build time and 3x the binary size. Turn it
#                               on if you want the last 10%.
#   LLAMA_CURL          OFF  - no network in the inference path.
#   tests/examples/server OFF - none of them ship inside an APK.

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_LTO             OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE          OFF CACHE BOOL "" FORCE)
set(GGML_INSTALL         OFF CACHE BOOL "" FORCE)

option(PIDROID_GGML_CPU_ALL_VARIANTS "Build every CPU kernel variant" OFF)
set(GGML_CPU_ALL_VARIANTS ${PIDROID_GGML_CPU_ALL_VARIANTS} CACHE BOOL "" FORCE)
option(PIDROID_GGML_CPU_REPACK "Build the repack kernels" OFF)
set(GGML_CPU_REPACK ${PIDROID_GGML_CPU_REPACK} CACHE BOOL "" FORCE)

add_subdirectory("${_llama_src_dir}" llama.cpp EXCLUDE_FROM_ALL)

# -----------------------------------------------------------------------
# The JNI layer
# -----------------------------------------------------------------------

add_library(pidroid_llama_jni SHARED
    llama_jni.cpp
)

target_include_directories(pidroid_llama_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${_llama_src_dir}/include"
    "${_llama_src_dir}/ggml/include"
)

target_link_libraries(pidroid_llama_jni PRIVATE
    llama
    ggml
    android
    log
    # c++_shared, declared in android/build.gradle.kts as
    # -DANDROID_STL=c++_shared. Both sides have to agree or the process dies
    # the first time a C++ exception unwinds.
)

target_compile_options(pidroid_llama_jni PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# No JNIEXPORT override here. The NDK's jni.h already defines it as
# __attribute__((visibility("default"))), which is exactly what the JNI entry
# points need, and redefining it as a CMake compile definition word-splits the
# attribute into separate -D flags and breaks the build.
