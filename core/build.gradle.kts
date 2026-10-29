// :core is a PURE JVM module on purpose.
//
// No Android plugin. No android.* imports. Ever. The agent loop, tool registry,
// tool selection, loop detection, context building and memory live here.
//
// If you need an Android capability, declare an interface here and implement it
// in :android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
