// :app — Compose UI and nothing else.
//
// No business logic, no agent decisions, no data access. If a screen needs to
// decide something, that decision belongs in :core.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.localintelligence.app"
    compileSdk = 36
    // Same NDK as :android. Packaging strips native libraries with this
    // module's NDK; left unset it is AGP's default (27.0.12077973), which is
    // not installed, so every .so shipped with its debug info.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "dev.localintelligence.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // For the manual device probe in src/androidTest (DeviceModelProbe).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Extracted, not stored in the APK: LiteRT-LM's NPU backend loads the
        // dispatch library by path from nativeLibraryDir, and that directory is
        // empty when libraries stay inside the APK.
        jniLibs.useLegacyPackaging = true
        // Only models compiled ahead of time for the Tensor NPU are loaded, so
        // the on-device compiler plugin is dead weight.
        jniLibs.excludes += "**/libLiteRtCompilerPlugin_google_tensor.so"
        jniLibs.pickFirsts += "**/libLiteRtDispatch_GoogleTensor.so"
        // The Khronos OpenCL loader is only a link-time stand-in for the OpenCL
        // backend. Shipping it would shadow the phone vendor's libOpenCL.so
        // (declared in the manifest), so it stays out of the APK.
        jniLibs.excludes += "**/libOpenCL.so"
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // android-mail and android-activation both ship these; the licences are kept in the
        // app's own notices, so one copy is not needed in the APK.
        resources.excludes += "/META-INF/{NOTICE.md,LICENSE.md}"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
