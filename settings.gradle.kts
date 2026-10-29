// LocalIntelligence — minimal Android-native agent harness for small local LLMs.
//
// THE ONE ARCHITECTURAL RULE: :core is a PURE KOTLIN/JVM module with ZERO Android
// dependencies. The agent loop, tool registry, selection, loop detection, context
// building, and memory live there, so they compile and run on the JVM with no
// emulator and no device. No eval suite or tests exist in the tree.
//
// See docs/architecture.md.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LocalIntelligence"

// Coarse modules on purpose. Do NOT add a module per tool, per screen, or per
// workstream — package boundaries give the separation without the build cost.
include(":core")        // pure JVM: contracts, agent loop, tools, memory
include(":android")     // Android library: tool impls, Room, llama.cpp JNI
include(":app")         // Android application: Compose UI
