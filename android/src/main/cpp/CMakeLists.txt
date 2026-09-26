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
#   ./gradlew :android:assembleDebug
#
# ...which fetches llama.cpp on the first build (needs network once; sources
# are then cached in android/.cxx for subsequent builds). There is no git
# submodule in this repository - do not run 'git submodule update'.
#
# ...or, to skip the fetch, point at a checkout you already have:
#
#   PIDROID_LLAMA_DIR=/path/to/llama.cpp ./gradlew :android:assembleDebug
#
# Both routes build the same library. They used to build DIFFERENT ones - the
# shipped path was quietly producing a stub with no llama.cpp behind it. See
# the block below "SET BEFORE THE SOURCES ARE ADDED" for the evidence and the
# reason it happened.
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
project(LocalIntelligence_llama CXX)

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
# producing a stub library. A libLocalIntelligence_llama_jni.so with no llama.cpp behind
# it would build clean, load clean, and then fail at the first JNI call, which
# is far more expensive to diagnose than a build error naming the fix.

set(PIDROID_LLAMA_DIR "" CACHE PATH "Path to a llama.cpp source checkout")
set(PIDROID_LLAMA_TAG "b4661" CACHE STRING "llama.cpp git tag to build against")
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

# -----------------------------------------------------------------------
# Build options for llama.cpp -- SET BEFORE THE SOURCES ARE ADDED
# -----------------------------------------------------------------------
#
# WHY THIS BLOCK COMES FIRST, AND WHY THAT IS NOT COSMETIC:
#
# Every llama.cpp and ggml setting below is consumed at *configure* time by
# option()/add_library() inside llama.cpp's own CMakeLists. If any of them is
# set after the llama.cpp tree has been added, it is a no-op for the build even
# though the cache variable reads back as the value we asked for. CMake's
# option() only defines a cache entry when one does not already exist, and
# add_library() fixes a target's SHARED/STATIC type at the moment it is called.
# Writing the cache afterwards changes the cache, not the targets.
#
# That is not theoretical. With this block placed after the FetchContent call
# (where it used to be), the two acquisition paths disagreed. Measured on
# arm64-v8a, llama.cpp b4661, NDK 27.1.12297006:
#
#   local checkout -> add_subdirectory() ran AFTER the set()s, so
#                     BUILD_SHARED_LIBS=OFF reached ggml: one self-contained
#                     22,341,528-byte JNI library, 5,090 dynamic symbols,
#                     zero undefined llama_/ggml_ references.
#
#   FetchContent    -> FetchContent_MakeAvailable() had already called
#                     add_subdirectory() while BUILD_SHARED_LIBS was still at
#                     upstream's default of ON, so ggml, ggml-base, ggml-cpu
#                     and llama were all built SHARED. The JNI library came out
#                     439,344 bytes with 161 dynamic symbols - ZERO defined
#                     ggml_ functions, 35 UNDEFINED llama_ references - and
#                     four DT_NEEDED entries on sibling .so files the APK had
#                     to ship and the device linker had to find. GGML_OPENMP=ON
#                     leaked in the same way, compiling 9 translation units
#                     with -fopenmp and adding libomp.so to the APK.
#
# Both paths now produce a byte-identical library: same size, same 5,090
# dynamic symbols, same defined-symbol set, differing only in the linker's
# GNU build-id (a digest of the unstripped output, which embeds the debug
# paths). Verified by building both and diffing every section.
#
# The fix is ordering, not more flags: declare every option before the sources
# enter the build, so both paths configure ggml identically. BUILD_SHARED_LIBS
# is set as a normal variable as well as in the cache, because the subdirectory
# inherits the normal variable first and that is the one add_library() reads.
#
# Options chosen for a phone, not for a workstation:
#   GGML_NATIVE         OFF  - the build machine is not the phone. -march=native
#                               would produce a binary that faults on most
#                               phones.
#   GGML_LTO            OFF  - a multi-minute LTO link per ABI for a ~2% size
#                               win for a library dwarfed by the model file.
#   GGML_OPENMP         OFF  - bionic ships no OpenMP runtime to link against;
#                               llama.cpp uses its own thread pool. Left at
#                               upstream's default ON this also drags libomp.so
#                               into the APK.
#   GGML_CPU_ALL_VARIANTS OFF - that flag builds every micro-arch variant (NEON
#                               + dotprod + fp16 arithmetic) as separately
#                               loadable modules. For a 1-4B model the win is a
#                               few percent of tokens/sec for 3x the build time
#                               and 3x the binary size. Turn it on if you want
#                               the last 10%; see the note below.
#   LLAMA_CURL          OFF  - no network in the inference path.
#   tests/examples/server OFF - none of them ship inside an APK.

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# One .so to ship. Static keeps llama+ggml inside the JNI library, so the
# device loads one file with no DT_NEEDED dance and no way to half-resolve.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF)

set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_LTO             OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE          OFF CACHE BOOL "" FORCE)

# -----------------------------------------------------------------------
# Per-ABI instruction set: why this is NOT GGML_NATIVE, and why it is split
# by ABI at all.
# -----------------------------------------------------------------------
#
# GGML_NATIVE=OFF above is correct and must stay. GGML_NATIVE=ON is not a
# way to ask for tuned kernels in a cross-compile; it is a way to get a
# broken one. Reading ggml/src/ggml-cpu/CMakeLists.txt (b4661) at the
# GGML_NATIVE branch in the ARM case, it does two things that both assume
# the build machine is the run machine:
#
#   1. execute_process(${CMAKE_C_COMPILER} -mcpu=native -E -v -)
#   2. check_arm_feature() -> check_cxx_source_runs(), which COMPILES AND
#      EXECUTES a probe program.
#
# Under the NDK neither holds. Measured with NDK 27.1 clang on an x86_64
# host targeting aarch64:
#
#   $ clang --target=aarch64-none-linux-android26 -mcpu=native -E -v -
#   clang: error: unsupported argument 'native' to option '-mcpu='
#
# So step 1 finds no -mcpu= string and falls back to the literal
# -mcpu=native, which step 1's own compiler has just rejected. And step 2
# would have to run an aarch64 binary on an x86_64 host, which cannot
# happen, so every feature probe returns false and upstream appends
# "+nodotprod", "+nosve", "+noi8mm" to the flag - producing a binary
# claiming ARMv8 tuning with none of it. Turning GGML_NATIVE on here does
# not make the library faster; it makes the build lie about the target.
#
# The correct knob is per-ABI and explicit, and upstream already has one:
#
#   GGML_CPU_ARM_ARCH -> -march=<value>   (ggml/src/ggml-cpu/CMakeLists.txt)
#   GGML_AVX / GGML_AVX2 / GGML_FMA / GGML_F16C -> -mavx -mavx2 -mfma -mf16c
#     plus the matching ARCH_DEFINITIONS. Defaults come from INS_ENB
#     (ggml/CMakeLists.txt), which is OFF whenever GGML_NATIVE is OFF.
#
# INS_ENB is an x86 concept and only ever feeds x86 options. It says
# nothing about ARM, which is worth stating because the earlier reading of
# this build - "GGML_NATIVE=OFF turns off tuned ARM kernels" - does not
# survive contact with the ARM branch of that file. ARM's baseline is
# decided by -march alone.
#
# Two facts about the arm64 baseline that the symbol evidence forced, and
# that neither is visible from the CMake options:
#
#   1. NEON IS ALREADY ON. Advanced SIMD is mandatory in ARMv8-A, so
#      __ARM_NEON is defined with no -march at all:
#        clang --target=aarch64-none-linux-android26 -dM -E - </dev/null
#          #define __ARM_NEON 1
#      The Q4_K/Q6_K/Q5_K NEON branches were therefore already being
#      compiled. "Generic NEON" was the wrong description of the old build.
#
#   2. WHAT WAS ACTUALLY MISSING IS HARDWARE SDOT. With
#      __ARM_FEATURE_DOTPROD undefined, ggml-cpu-impl.h defines
#      ggml_vdotq_s32() as a SOFTWARE EMULATOR -
#      two vmull_s8 plus two vpaddlq_s16 in place of one sdot - and the
#      Q4_K NEON path calls it four times per 64-element block. The
#      baseline .so carries it as a real out-of-line function:
#        0000000000420f9c 0000000000000124 t ggml_vdotq_s32
#      so the hot Q4_K kernel was paying a function call and four
#      widening multiplies to emulate one instruction.
#
# That is the gap worth closing, and -march=armv8.2-a+dotprod closes it.
#
# THE TRADEOFF, STATED PLAINLY: ggml dispatches the K-quant vec_dot
# kernels at COMPILE TIME. The __ARM_NEON branch of ggml_vec_dot_q4_K_q8_K
# contains no ggml_cpu_has_*() call, so sdot is emitted unconditionally -
# there is no runtime hwcaps check standing between this binary and a
# pre-ARMv8.2 CPU. Contrast the repack path in ggml-cpu-aarch64.cpp, which
# is guarded twice, once at compile time and once by ggml_cpu_has_dotprod()
# at run time, and is therefore safe to compile in at any -march.
#
# ARMv8.2-A+dotprod is not universal. Cortex-A53 (Snapdragon 625/630/660,
# Helio A22/M22) is ARMv8.0 and has no SDOT, and those parts are still
# shipping in budget Android. So the arm64 default below is armv8.0-safe
# and the dotprod build is opt-in via PIDROID_GGML_ARM_DOTPROD, precisely
# because a SIGILL on a user's phone is a worse outcome than a few percent
# of tokens/sec, and because nothing in this repository has been run on a
# physical ARM device to measure what that trade is actually worth. Flip
# it once there is a device and a tok/s number to justify it.
#
# x86_64 is the opposite case and is on by default: it exists for the
# emulator, and the emulator runs on the developer's own AVX2 host. There
# is no armv8.0-analogue risk here. It is also the ABI that was most
# starved: with -msse4.2 and nothing above it, the built x86_64 library
# contained no AVX/SSE vector arithmetic at all in the mul_mat kernels.
option(PIDROID_GGML_ARM_DOTPROD "Compile ARMv8.2-A+dotprod into the arm64 kernels" OFF)

# Same env-var convention as PIDROID_LLAMA_DIR / PIDROID_LLAMA_FETCH above,
# for the same reason: AGP does not forward Gradle -P properties to CMake as
# -D defines, and this build belongs to another workstream, so an environment
# variable is the only switch that does not require editing a Gradle file.
#   PIDROID_GGML_ARM_DOTPROD=1 ./gradlew :android:assembleRelease
if(NOT PIDROID_GGML_ARM_DOTPROD AND DEFINED ENV{PIDROID_GGML_ARM_DOTPROD})
    set(PIDROID_GGML_ARM_DOTPROD ON)
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    if(PIDROID_GGML_ARM_DOTPROD)
        # -march only, never -mcpu: -mcpu also retunes for a specific core
        # layout, and "native" would be the build machine's, not the
        # phone's. armv8.2-a is the dotprod floor.
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
    else()
        # "armv8-a", not "armv8.0-a": clang spells the A-profile baseline
        # without the minor version and rejects the latter outright
        # ("unsupported argument 'armv8.0-a' to option '-march='"). This is
        # also the spelling the NDK itself uses as its arm64 default, and
        # it is the value a cross-compile already gets by passing nothing,
        # so the else-branch is a no-op restated for the reader rather
        # than a behaviour change.
        set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
    endif()
elseif(ANDROID_ABI STREQUAL "x86_64")
    # Haswell (2013) baseline. These are the only x86 knobs INS_ENB would
    # have supplied; without them ggml compiles its __SSE2__ path.
    set(GGML_AVX    ON CACHE BOOL "" FORCE)
    set(GGML_AVX2   ON CACHE BOOL "" FORCE)
    set(GGML_FMA    ON CACHE BOOL "" FORCE)
    set(GGML_F16C   ON CACHE BOOL "" FORCE)
endif()

# GGML_BACKEND_DL makes the CPU backend a separately dlopen()ed module instead
# of code linked into ggml. It is only legal with BUILD_SHARED_LIBS=ON, so
# leaving it at its default is what keeps a single self-contained JNI library.
# Pinned explicitly because "whatever upstream defaults to" is not a build
# decision this project should be inheriting by accident.
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)

option(PIDROID_GGML_CPU_ALL_VARIANTS "Build every CPU kernel variant" OFF)
if(PIDROID_GGML_CPU_ALL_VARIANTS)
    # Upstream hard-errors on this combination (ggml/src/CMakeLists.txt:
    # "GGML_CPU_ALL_VARIANTS requires GGML_BACKEND_DL"), and honouring it
    # means going back to shared backends plus per-variant modules that have to
    # be packaged and found at runtime. Say so here rather than failing later
    # with an upstream message that does not mention this project.
    message(FATAL_ERROR
        "PIDROID_GGML_CPU_ALL_VARIANTS=ON is not supported: it requires "
        "GGML_BACKEND_DL=ON, which in turn requires BUILD_SHARED_LIBS=ON, "
        "which breaks the one-self-contained-.so property this build depends "
        "on. Leave it OFF, or implement the runtime module packaging first.")
endif()

# -----------------------------------------------------------------------
# One resolution path, one option set, one add_subdirectory.
# -----------------------------------------------------------------------

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
    # Populate downloads and unpacks; it does NOT call add_subdirectory(). That
    # is the whole point. FetchContent_MakeAvailable() does both in one step,
    # which means the llama.cpp tree is configured at a moment this file does
    # not control - and that is precisely how BUILD_SHARED_LIBS=OFF ended up
    # being written to the cache after ggml had already read it as ON.
    # Populate leaves the tree under our control so both acquisition paths
    # reach the single add_subdirectory() below, with the same options already
    # in scope. (Deprecated in CMake 3.30 in favour of MakeAvailable, but
    # MakeAvailable cannot express "download now, configure later", and this
    # project pins CMake 3.22.1 where it is the supported spelling.)
    FetchContent_Populate(llama)
    set(_llama_src_dir "${llama_SOURCE_DIR}")
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
# Make the compiled output independent of where llama.cpp happens to live.
# -----------------------------------------------------------------------
#
# __FILE__ expands to the path the compiler was given, and llama.cpp bakes
# those strings into .rodata (GGML_ABORT and friends) and into the debug info.
# FetchContent puts the sources in <build>/_deps/llama-src; a local checkout
# sits wherever the developer put it. That made the two acquisition paths
# produce binaries that differ in content for no reason other than the
# directory name - and it leaks the build machine's filesystem layout into a
# shipped APK.
#
# -ffile-prefix-map rewrites the prefix to a fixed token, so both paths compile
# the same bytes. This is set as a directory property BEFORE the llama.cpp
# tree is added, because add_subdirectory() inherits it at that moment and not
# afterwards.
if(_llama_src_dir)
    add_compile_options("-ffile-prefix-map=${_llama_src_dir}=llama.cpp")
endif()

# -----------------------------------------------------------------------
# Add the llama.cpp tree
# -----------------------------------------------------------------------
#
# The options are set ABOVE, before we get here, on purpose: add_subdirectory()
# configures llama.cpp immediately, so anything set after this point is
# decoration. See the block at the top.
#
# There is exactly ONE add_subdirectory() in this file, and both acquisition
# paths reach it: a local checkout arrives via PIDROID_LLAMA_DIR, and the
# fetch arrives via FetchContent_Populate, which downloads without configuring.
# They used to diverge - the fetch path relied on MakeAvailable() having added
# the tree at a moment of its own choosing, and the local path added it here.
# Two entry points meant two chances to configure ggml differently, which is
# exactly the bug this file used to have. Now there is one path, so the two
# acquisition methods cannot disagree about the build.
# -----------------------------------------------------------------------
add_subdirectory("${_llama_src_dir}" llama.cpp EXCLUDE_FROM_ALL)

# -----------------------------------------------------------------------
# Verify the build is the one we asked for.
# -----------------------------------------------------------------------
#
# The failure this guards against is silent and expensive: BUILD_SHARED_LIBS
# reaching ggml as ON splits llama/ggml out into sibling .so files, the JNI
# library shrinks to a stub, and the cache still reads BUILD_SHARED_LIBS=OFF,
# so nothing in the log says anything went wrong. Assert the shape of the
# actual targets rather than trusting the cache.
get_target_property(_llama_type llama TYPE)
get_target_property(_ggml_type ggml TYPE)
if(NOT _llama_type STREQUAL "STATIC_LIBRARY" OR NOT _ggml_type STREQUAL "STATIC_LIBRARY")
    message(FATAL_ERROR
        "llama/ggml were configured as ${_llama_type}/${_ggml_type}, not "
        "STATIC_LIBRARY. Something set BUILD_SHARED_LIBS after the llama.cpp "
        "tree was added, or upstream changed its default. The result is a JNI "
        "library with no llama.cpp behind it. Check the ordering of the "
        "option block in android/src/main/cpp/CMakeLists.txt.")
endif()
message(STATUS "llama.cpp: static llama+ggml, one self-contained JNI library")

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
