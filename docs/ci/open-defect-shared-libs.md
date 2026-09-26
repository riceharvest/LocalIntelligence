# An open defect the fresh-clone job has just exposed

**Status: reported, not fixed. The file that needs the change is
`android/src/main/cpp/CMakeLists.txt`, which the CI workstream does not own.**

Adding the fresh-clone job to CI made this visible for the first time. It is
worth writing down rather than leaving in a PR comment, because the next person
to look at the APK sizes will hit it.

## What was found

The two llama.cpp source-resolution paths produce **different APKs**. Not
different builds of the same thing — different native payloads.

`PIDROID_LLAMA_DIR` (what CI used to do, and what a local developer with a
llama.cpp checkout still does):

```
lib/arm64-v8a/liblocalintelligence_llama_jni.so    5,003,256 bytes
```

`FetchContent` (the default, and therefore what every stranger gets):

```
lib/arm64-v8a/liblocalintelligence_llama_jni.so      110,888 bytes
lib/arm64-v8a/libllama.so                          3,833,344 bytes
lib/arm64-v8a/libggml.so                             177,768 bytes
lib/arm64-v8a/libggml-base.so                        960,176 bytes
lib/arm64-v8a/libggml-cpu.so                         560,328 bytes
lib/arm64-v8a/libomp.so                              961,440 bytes
```

Measured from two real builds of the same commit: a `PIDROID_LLAMA_DIR` build
(run `36207317459`'s APK) and a fresh-clone FetchContent build of `8f71dad`.

## Why

`BUILD_SHARED_LIBS` and `GGML_OPENMP` are forced **after** the fetched tree has
already been added to the build.

`android/src/main/cpp/CMakeLists.txt`:

- **line 115** — `FetchContent_MakeAvailable(llama)`. This does not merely
  download; it calls `add_subdirectory` on the fetched tree **immediately**,
  which configures ggml and llama **right there**.
- **line 163** — `set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)`
- **line 167** — `set(GGML_OPENMP OFF CACHE BOOL "" FORCE)`

So by the time lines 163 and 167 run, ggml has already read `BUILD_SHARED_LIBS`
and built itself. The `FORCE` is applied to a cache variable nothing reads
again. On the `PIDROID_LLAMA_DIR` path, by contrast, `add_subdirectory` happens
at **line 188** — *after* the options are set — which is why that path produces
the static, single-`.so` build the file's own comments describe.

`llvm-readelf -d` on the FetchContent JNI library confirms it is dynamically
linked against the split libraries rather than statically linked:

```
NEEDED  libllama.so
NEEDED  libggml.so
NEEDED  libggml-cpu.so
NEEDED  libggml-base.so
NEEDED  libc++_shared.so   (+ libandroid, liblog, libm, libdl, libc)
```

`libomp.so` is in the APK at all, which it should not be: `GGML_OPENMP` is
documented in this project as `OFF` because *"bionic ships no OpenMP runtime to
link against"*.

## Is the shipped APK broken?

**Not obviously, and this is not a crash claim.** The build succeeds, all
`NEEDED` entries resolve to libraries that are present in the same APK, and the
JNI library is packaged. It loads.

What it *is*: a different artifact than the one that was being tested, with
~6.5 MB of extra shared libraries per ABI that the build file explicitly says
should not exist, an OpenMP runtime that the build file explicitly says should
be off, and a change to the linking strategy that nobody chose and nobody
reviewed. A 1–4B model dwarfs 6.5 MB, so the size argument is weak on its own.
The argument that matters is that **the thing CI validates is no longer the
thing most users get** — which is the entire reason the fresh-clone job exists,
one level up.

## The fix

Move the option-setting **above** the `FetchContent_MakeAvailable` call, so
ggml sees them when it is configured. Concretely, in
`android/src/main/cpp/CMakeLists.txt`: relocate the block of `set(... CACHE ...
FORCE)` lines (currently 158-174) to immediately before the
`FetchContent_MakeAvailable(llama)` at line 115, keeping them ahead of both
`add_subdirectory` call sites.

A belt-and-braces alternative is to pass them as explicit `-D` arguments from
`android/build.gradle.kts`'s `externalNativeBuild` block, which sets them in the
CMake cache before anything is configured and so cannot be ordered wrongly:

```kotlin
arguments += listOf(
    "-DANDROID_STL=c++_shared",
    "-DBUILD_SHARED_LIBS=OFF",
    "-DGGML_OPENMP=OFF",
)
```

The second is more robust because it removes the ordering hazard entirely rather
than relying on a comment to keep the two in order — but it edits a build file
that is also not owned by this workstream.

## How to see it yourself

```sh
# default path
git clone <repo> fresh && cd fresh
./gradlew --no-build-cache :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'libllama|libggml|libomp|liblocalintelligence'

# the old path, for comparison
PIDROID_LLAMA_DIR=/path/to/llama.cpp ./gradlew :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'libllama|libggml|libomp|liblocalintelligence'
```

## What CI does about it *today*

Nothing, deliberately — this branch cannot edit that file. The fresh-clone job
reports the full library inventory on every run
("Report what was actually built"), so a change in either direction is visible
in the log rather than silent. The `inspect-apk.sh` gate deliberately does **not**
assert an exact library set, because pinning the current (wrong) set would turn
a known bug into a hard-failing gate that blocks every PR until the CMake file
is fixed — and a gate that blocks all work gets deleted rather than fixed.
