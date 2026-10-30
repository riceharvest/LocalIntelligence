# llama.cpp JNI for LocalIntelligence. Built by :android via externalNativeBuild.
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
project(LocalIntelligence_llama C CXX)

# Optimised even in the debug APK. AGP builds the debug variant's native code
# with CMAKE_BUILD_TYPE=Debug, i.e. -O0, and inference at -O0 is unusable:
# MEASURED 2026-10-30 on a Pixel 10 Pro, LFM3-1.7B Q4_K_M, in-app speed test:
# prefill 0.9 tok/s, decode 0.6 tok/s. Debugging the C++ is rare; running it
# is every message.
# Set as a normal variable as well as in the cache: the NDK toolchain defines
# CMAKE_<LANG>_FLAGS_DEBUG as a normal variable, which shadows a cache entry.
# (A cache-only override left every ggml file at -O0: MEASURED prefill 4.6,
# decode 1.5 tok/s with the armv9 kernels and no -O flag on the command line.)
foreach(_lang C CXX)
    set(CMAKE_${_lang}_FLAGS_DEBUG "-O3 -DNDEBUG -g0" CACHE STRING "" FORCE)
    set(CMAKE_${_lang}_FLAGS_DEBUG "-O3 -DNDEBUG -g0")
endforeach()

# 16 KB page alignment for every shared library this project links. Required
# on 16 KB-page devices and by Google Play for apps targeting Android 15+;
# without it Android warns at launch (seen on the Pixel 10 Pro).
foreach(_kind SHARED MODULE)
    set(CMAKE_${_kind}_LINKER_FLAGS "${CMAKE_${_kind}_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
endforeach()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp build options. Set BEFORE the tree is added, by either route:
# FetchContent_MakeAvailable() below adds the subdirectory immediately, so an
# option set after it would not apply on a fresh configure. That is how the
# fetch route used to build tests/tools and, with libmtmd, failed to find
# mtmd.h. The reasons for each value are in the "Build llama.cpp" section.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
# libmtmd: image and audio input, and speech output, for multimodal models.
# Built on its own (tools stay off); video needs an ffmpeg binary, so it is off.
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)
set(MTMD_VIDEO           OFF CACHE BOOL "" FORCE)
# Shared libraries, so the CPU kernels can be dynamic backends (below).
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)

set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_LTO             OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE          OFF CACHE BOOL "" FORCE)
set(GGML_INSTALL         OFF CACHE BOOL "" FORCE)

# Every CPU kernel variant, picked at runtime. ggml builds one CPU backend per
# feature level (Android: armv8.0, armv8.2+dotprod, +fp16, armv8.6+i8mm,
# armv9.0+SVE2, armv9.2+SME; x86_64: SSE4.2 up to AVX-512) and loads the best
# one the phone supports, so old phones still run and new ones get their
# fast instructions. Requires dynamic backends.
set(GGML_BACKEND_DL       ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
# Repacked Q4_0/IQ4_NL weights for the ARM matmul kernels, and Arm's KleidiAI
# micro-kernels where they apply. Both only change speed, never results.
set(GGML_CPU_REPACK       ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

# ---------------------------------------------------------------------------
# GPU backends: Vulkan (Adreno, Mali, PowerVR, Xclipse, Intel) and OpenCL
# (Qualcomm Adreno kernels). Both are dynamic backends like the CPU variants:
# a phone whose driver is missing or too old just doesn't load that library,
# and inference stays on the CPU. Which device runs a model is chosen at load
# time (Settings -> Model -> Compute), never assumed.
#
# The NDK ships the Vulkan C headers and libvulkan.so but not the C++ headers
# (vulkan.hpp) or SPIRV-Headers that ggml-vulkan needs, and no OpenCL at all,
# so the Khronos repositories are fetched at a pinned tag.
# ---------------------------------------------------------------------------
option(PIDROID_GPU "Build the Vulkan and OpenCL backends" ON)
if(PIDROID_GPU)
    include(FetchContent)
    set(_khronos_vk_tag  "vulkan-sdk-1.4.363.0")
    set(_khronos_cl_tag  "v2026.05.29")
    FetchContent_Declare(li_vulkan_headers GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
        GIT_TAG ${_khronos_vk_tag} GIT_SHALLOW TRUE)
    FetchContent_Declare(li_spirv_headers GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
        GIT_TAG ${_khronos_vk_tag} GIT_SHALLOW TRUE)
    FetchContent_Declare(li_opencl_headers GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        GIT_TAG ${_khronos_cl_tag} GIT_SHALLOW TRUE)
    FetchContent_Declare(li_opencl_icd GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
        GIT_TAG ${_khronos_cl_tag} GIT_SHALLOW TRUE)
    foreach(_dep li_vulkan_headers li_spirv_headers li_opencl_headers li_opencl_icd)
        FetchContent_GetProperties(${_dep})
        if(NOT ${_dep}_POPULATED)
            FetchContent_Populate(${_dep})
        endif()
    endforeach()

    # --- Vulkan ---------------------------------------------------------------
    # SPIRV-Headers is consumed through its CMake package config. Install the
    # header-only package into the build tree at configure time and point
    # find_package straight at it (the NDK toolchain restricts package search
    # to the sysroot, so a prefix path would not be searched).
    set(_spirv_install "${CMAKE_BINARY_DIR}/spirv-headers-install")
    if(NOT EXISTS "${_spirv_install}/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake")
        execute_process(COMMAND ${CMAKE_COMMAND} -S ${li_spirv_headers_SOURCE_DIR}
            -B ${CMAKE_BINARY_DIR}/spirv-headers-build -G ${CMAKE_GENERATOR}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM} -DCMAKE_INSTALL_PREFIX=${_spirv_install}
            -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DSPIRV_HEADERS_ENABLE_INSTALL=ON
            RESULT_VARIABLE _rc OUTPUT_QUIET)
        execute_process(COMMAND ${CMAKE_COMMAND} --install ${CMAKE_BINARY_DIR}/spirv-headers-build
            RESULT_VARIABLE _rc OUTPUT_QUIET)
    endif()
    set(SPIRV-Headers_DIR "${_spirv_install}/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)

    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        set(_ndk_host "linux-x86_64")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(_ndk_host "darwin-x86_64")
    else()
        set(_ndk_host "windows-x86_64")
    endif()
    set(Vulkan_INCLUDE_DIR "${li_vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    find_library(_li_libvulkan vulkan)
    set(Vulkan_LIBRARY "${_li_libvulkan}" CACHE FILEPATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${_ndk_host}/glslc" CACHE FILEPATH "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    # --- OpenCL ---------------------------------------------------------------
    # Linked against the Khronos ICD loader so the backend links with
    # --no-undefined; the loader's libOpenCL.so is NOT packaged (see
    # app/build.gradle.kts), so at runtime the phone vendor's libOpenCL.so,
    # declared in the manifest, is the one that loads.
    set(OPENCL_ICD_LOADER_HEADERS_DIR "${li_opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory(${li_opencl_icd_SOURCE_DIR} ${CMAKE_BINARY_DIR}/opencl-icd EXCLUDE_FROM_ALL)
    set(OpenCL_INCLUDE_DIR "${li_opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
endif()

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
# producing a stub library. A libLocalIntelligence_llama_jni.so with no llama.cpp behind
# it would build clean, load clean, and then fail at the first JNI call, which
# is far more expensive to diagnose than a build error naming the fix.

set(PIDROID_LLAMA_DIR "" CACHE PATH "Path to a llama.cpp source checkout")
# FORCE: without it a build directory configured against an older pin keeps
# that tag in CMakeCache.txt and compiles the JNI against stale headers. The
# JNI is written against this one tag's API, so overriding it is never useful.
set(PIDROID_LLAMA_TAG "b11988" CACHE STRING "llama.cpp git tag to build against" FORCE)
# WHY FETCH IS THE DEFAULT, NOT OPT-IN: it used to be OFF, so a fresh
# `git clone` followed by `./gradlew :app:assembleDebug` failed with
# "llama.cpp sources not found" and the error told the user to run
# `git submodule update --init --recursive` - for a submodule that does not
# exist. There is no .gitmodules in this repository. A first-time builder was
# sent down a dead end by our own error message, and the one route that
# actually worked (PIDROID_LLAMA_FETCH=1) was not discoverable from it.
#
# Fetching the pinned tag is slower than reusing a checkout, but it is the only
# option that works on a clean machine with no setup at all. Local developers
# keep the fast path by setting PIDROID_LLAMA_DIR, which is checked first.
option(PIDROID_LLAMA_FETCH "Fetch llama.cpp with FetchContent if not found locally" ON)

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
    # MakeAvailable has already added the subdirectory, so the later
    # add_subdirectory() must be skipped on this path.
    set(_llama_src_dir_from_fetchcontent ON)
endif()

if(NOT _llama_src_dir)
    message(FATAL_ERROR
        "llama.cpp sources not found.\n"
        "This build fetches llama.cpp automatically, so this normally means the\n"
        "fetch was disabled and no local checkout was given. Do either:\n"
        "  PIDROID_LLAMA_FETCH=1 ./gradlew :android:assembleDebug   (fetch the pinned tag)\n"
        "  PIDROID_LLAMA_DIR=/path/to/llama.cpp ./gradlew :android:assembleDebug\n"
        "The checkout must be at tag ${PIDROID_LLAMA_TAG}. There is no git submodule\n"
        "in this repository; do not run 'git submodule update'.\n"
        "See android/src/main/cpp/CMakeLists.txt for details.")
endif()

# -----------------------------------------------------------------------
# Build llama.cpp
# -----------------------------------------------------------------------
#
# ggml/llama/mtmd are shared libraries (one pinned tag, so they are always in
# step), because the CPU backends are dynamic: see GGML_BACKEND_DL above.
#
# Options chosen for a phone, not for a workstation:
#   GGML_NATIVE         OFF  - the build machine is not the phone. -march=native
#                               would produce a binary that faults on most
#                               phones.
#   GGML_LTO            OFF  - a multi-minute LTO link per ABI for a ~2% size
#                               win on a library dwarfed by the model file.
#   GGML_OPENMP         OFF  - bionic ships no OpenMP runtime to link against;
#                               llama.cpp uses its own thread pool.
#   GGML_CPU_ALL_VARIANTS ON - every micro-arch variant, chosen at runtime.
#                               It used to be OFF, which built plain armv8-a:
#                               no dotprod, no fp16, no i8mm on any phone.
#   LLAMA_OPENSSL       OFF  - no network in the inference path.
#   tests/examples/server/tools/app OFF - none of them ship inside an APK.

# (The llama.cpp options are set above, before the tree is added: see there.)

# ---------------------------------------------------------------------------
# Add the llama.cpp tree exactly once.
#
# WHY this is conditional: FetchContent_MakeAvailable(llama) has already
# called add_subdirectory() on _llama_src_dir by the time we get here. Doing it
# again - unconditionally, as this used to - makes CMake fail with
# `add_library cannot create target "llama"`, so the PIDROID_LLAMA_FETCH=1 path
# could never have worked. It stayed invisible because the local build sets
# PIDROID_LLAMA_DIR, which short-circuits the fetch branch entirely and
# therefore never exercised it.
# ---------------------------------------------------------------------------
if(NOT _llama_src_dir_from_fetchcontent)
    add_subdirectory("${_llama_src_dir}" llama.cpp EXCLUDE_FROM_ALL)
endif()

# -----------------------------------------------------------------------
# The JNI layer
# -----------------------------------------------------------------------

add_library(LocalIntelligence_llama_jni SHARED
    llama_jni.cpp
)

# WHY THIS EXISTS: the Kotlin side calls
# System.loadLibrary("localintelligence_llama_jni") — lowercase. CMake named the
# output libLocalIntelligence_llama_jni.so, the APK packaged that, and the
# device loader failed with
#   dlopen failed: library "liblocalintelligence_llama_jni.so" not found
# so the native backend was dead on arrival: every model load threw
# "the llama.cpp native library is not available on this device". Android's
# loader is case-sensitive and does NOT normalise the name. Pin OUTPUT_NAME to
# the exact soname the Kotlin constant uses.
set_target_properties(LocalIntelligence_llama_jni PROPERTIES
    OUTPUT_NAME "localintelligence_llama_jni"
)

target_include_directories(LocalIntelligence_llama_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${_llama_src_dir}/include"
    "${_llama_src_dir}/ggml/include"
)

target_link_libraries(LocalIntelligence_llama_jni PRIVATE
    mtmd
    llama
    ggml
    android
    log
    # c++_shared, declared in android/build.gradle.kts as
    # -DANDROID_STL=c++_shared. Both sides have to agree or the process dies
    # the first time a C++ exception unwinds.
)

target_compile_options(LocalIntelligence_llama_jni PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# No JNIEXPORT override here. The NDK's jni.h already defines it as
# __attribute__((visibility("default"))), which is exactly what the JNI entry
# points need, and redefining it as a CMake compile definition word-splits the
# attribute into separate -D flags and breaks the build.
