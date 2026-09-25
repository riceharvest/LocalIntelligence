// :core is a PURE JVM module on purpose.
//
// No Android plugin. No android.* imports. Ever. The agent loop, tool registry,
// tool selection, loop detection, context building, memory and the entire eval
// suite live here and must stay unit-testable with plain JUnit on the JVM,
// because that is the only verification loop fast enough for a swarm of agents.
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

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

// The agent eval suite lived in core/src/test and ran against fakes, not the
// real loop and a real model. It reported 50/50 for a harness that could not
// fail, and the app it "verified" could not answer a single question. Removed
// with the rest of the test sources. Verification now means running a real
// model on a real device, not a green suite.
