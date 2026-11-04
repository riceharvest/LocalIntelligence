// :android — the ONLY module allowed to import android.*
//
// Android tool implementations, the Room-backed stores, and the llama.cpp JNI
// binding. No agent logic lives here: if you find yourself writing a decision in
// this module, it belongs in :core behind an interface.
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.localintelligence.android"
    compileSdk = 36

    // Pinned explicitly. With no declaration AGP 8.13.2 falls back to a
    // default compiled into the plugin (27.0.12077973), which is not the NDK
    // this module is built and tested against. That made the local build and
    // the CI build silently use different toolchains, and meant CI had to
    // guess. See docs/build.md.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 36

        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // armeabi-v7a is dropped: a 1-4B model does not fit in 32-bit
                // address space alongside a JVM. 64-bit only, on purpose.
                arguments += listOf("-DANDROID_STL=c++_shared")
                // 4 compile jobs matches the systemd CPUQuota=400%; unbounded ninja on a 32-core box exceeds the 6 GB cap.
                arguments += listOf(
                    "-DCMAKE_JOB_POOLS=compile=4;link=1",
                    "-DCMAKE_JOB_POOL_COMPILE=compile",
                    "-DCMAKE_JOB_POOL_LINK=link",
                )
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

// Room writes each database version's schema here, and the files are committed.
// A migration copies its CREATE statements from them, so the migrated table is
// exactly the table Room validates against on open.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

abstract class DownloadSherpaOnnxAarTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun fetch() {
        val target = outputFile.get().asFile
        val expected = sha256.get().lowercase()
        if (target.isFile && sha256Hex(target) == expected) return
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.part")
        URI(url.get()).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256Hex(tmp)
        check(actual == expected) {
            tmp.delete()
            "SHA-256 mismatch for ${url.get()}: expected $expected, got $actual"
        }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Could not move ${tmp.absolutePath} to ${target.absolutePath}" }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }
}

val downloadSherpaOnnxAar = tasks.register<DownloadSherpaOnnxAarTask>("downloadSherpaOnnxAar") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar")
    sha256.set("07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575")
    outputFile.set(layout.buildDirectory.file("sherpa-onnx/sherpa-onnx-static-link-onnxruntime-2.0.1.aar"))
}

dependencies {
    api(project(":core"))

    // Open-vocabulary wake word spotting (Zipformer transducer KWS). Fetched into
    // build/ and verified by SHA-256 rather than committed to the repository.
    implementation(files(downloadSherpaOnnxAar))

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
    // a JDK 17 JVM. That is why every LiteRT-LM type is reached only through
    // `LiteRtLmEngine`. See LiteRtLmEngine's KDoc.
    implementation(libs.litertlm.android)
    // Only the Tensor dispatch .so. Not transitive: a second LiteRT runtime next
    // to the one inside litertlm-android is exactly the version mismatch to avoid.
    implementation(libs.litert.npu.google.tensor) { isTransitive = false }

    // PDF text extraction for the Documents plugin (PDFTextStripper). Init: DocumentImport.
    implementation(libs.pdfbox.android)
    implementation(libs.jsch)

    // The sandbox script plugins run in. See scripts/ScriptRunner.kt.
    implementation(libs.androidx.javascriptengine)

    // IMAP and SMTP for the Email plugin (tools/email). javax.mail packages, with the
    // activation classes JavaMail needs for MIME parts.
    implementation(libs.android.mail)
    implementation(libs.android.activation)
}
