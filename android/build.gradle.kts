// :android — the ONLY module allowed to import android.*
//
// Android tool implementations, the Room-backed stores, and the llama.cpp JNI
// binding. No agent logic lives here: if you find yourself writing a decision in
// this module, it belongs in :core behind an interface.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.localintelligence.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 36

        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // armeabi-v7a is dropped: a 1-4B model does not fit in 32-bit
                // address space alongside a JVM. 64-bit only, on purpose.
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // The second ModelBackend runtime, so tok/s on a real phone can be compared
    // against llama.cpp rather than argued about. Google's Maven, not Central.
    //
    // Its classes are Java 21 bytecode (class file v65). That is fine to COMPILE
    // against from a jvmToolchain(21) build -- Kotlin reads the higher class
    // version without complaint -- but it means these classes cannot be LOADED by
    // a JDK 17 unit-test JVM. That is precisely why every LiteRT-LM type is
    // reached only through `LiteRtLmEngine`, and why the whole test suite runs
    // against a fake engine. See LiteRtLmEngine's KDoc.
    implementation(libs.litertlm.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
