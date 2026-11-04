// :app — Compose UI and nothing else.
//
// No business logic, no agent decisions, no data access. If a screen needs to
// decide something, that decision belongs in :core.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// -PplayNoSms=true builds the play flavor without texts and call-log permissions (docs/build.md).
// Default false: the Play build declares them, with the assistant-role declaration (docs/roadmap.md).
val playNoSms: Boolean = (findProperty("playNoSms") as? String)?.toBoolean() ?: false

// Release signing. The keystores and their passwords live outside the repository, in the
// properties file named by `liSigningProps` in ~/.gradle/gradle.properties (see docs/build.md,
// "Release builds"). Without that property, or when the file is missing (every other machine and
// CI), nothing here is created: release builds stay unsigned and every build still configures.
val releaseSigning: Properties? = (findProperty("liSigningProps") as? String)
    ?.let { file(it) }
    ?.takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

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

        // The Extras backend address. Empty by default, which hides the Extras row and makes
        // no billing or backend call. Set it with -PextrasBackendUrl=https://... when the
        // Worker is deployed; it is never a secret.
        buildConfigField("String", "EXTRAS_BACKEND_URL", "\"${(findProperty("extrasBackendUrl") as? String).orEmpty()}\"")

        // The reply-report form (Play's AI content policy needs in-app reporting). Both are
        // public identifiers, not secrets. The defaults are the owner's form; -PreportFormUrl and
        // -PreportFormEntry override them. Empty values make a report say "not set up in this build".
        buildConfigField("String", "REPORT_FORM_URL", "\"${(findProperty("reportFormUrl") as? String)
            ?: "https://docs.google.com/forms/d/e/1FAIpQLSce98cRSCHqOMjlOexJvYeDDqlKNox4i40oHUqvG3X20H_-Lg/formResponse"}\"")
        buildConfigField("String", "REPORT_FORM_ENTRY", "\"${(findProperty("reportFormEntry") as? String) ?: "entry.959047461"}\"")
    }

    // Declared before productFlavors, which look these up by name.
    signingConfigs {
        if (releaseSigning != null) {
            // Play upload key (docs/build.md): Play App Signing can reset it.
            create("playRelease") {
                storeFile = file(releaseSigning.getProperty("play.storeFile"))
                storeType = "pkcs12"
                storePassword = releaseSigning.getProperty("play.storePassword")
                keyAlias = releaseSigning.getProperty("play.keyAlias")
                keyPassword = releaseSigning.getProperty("play.keyPassword")
            }
            // Full key: installed copies can never update if it is lost.
            create("fullRelease") {
                storeFile = file(releaseSigning.getProperty("full.storeFile"))
                storeType = "pkcs12"
                storePassword = releaseSigning.getProperty("full.storePassword")
                keyAlias = releaseSigning.getProperty("full.keyAlias")
                keyPassword = releaseSigning.getProperty("full.keyPassword")
            }
        }
    }

    // Two distributions (docs/adr/0002-play-and-full-builds.md): Play ships what Google Play
    // allows; full installs side by side with its own package id and adds capabilities Play forbids.
    flavorDimensions += "dist"
    productFlavors {
        create("play") {
            dimension = "dist"
            isDefault = true
            buildConfigField("boolean", "FULL_BUILD", "false")
            // A flavor's signingConfig also reaches debug; the debug buildType below overrides it.
            signingConfig = signingConfigs.findByName("playRelease")
        }
        create("full") {
            dimension = "dist"
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "FULL_BUILD", "true")
            signingConfig = signingConfigs.findByName("fullRelease")
        }
    }

    // The overlay replaces the play flavor's manifest only when -PplayNoSms=true; the default play
    // build keeps the texts and call-log permissions. The full flavor is never affected.
    if (playNoSms) {
        sourceSets.getByName("play") {
            manifest.srcFile("src/playNoSms/AndroidManifest.xml")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Debug always uses the debug keystore, never the release keys set on the flavors.
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    // Android 13+ per-app language: AGP writes the locale list from the values-xx folders
    // into the APK (android:localeConfig), so a language appears in the system picker once
    // its strings exist. English (res/resources.properties) is the unqualified default.
    androidResources {
        generateLocaleConfig = true
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
        // android-mail and android-activation both ship the same NOTICE.md and LICENSE.md;
        // one copy of each stays in the APK.
        resources.pickFirsts += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
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
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Google Play Billing, for Extras only. Nothing else in the app uses it.
    implementation(libs.billing.ktx)

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
