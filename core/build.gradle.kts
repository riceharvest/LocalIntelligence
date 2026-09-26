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

    // Pure-JVM unit tests ONLY. This is a `testImplementation` and it never
    // touches the main classpath, so the pure-JVM boundary of :core is
    // unaffected.
    //
    // Restored deliberately after an earlier blanket deletion, and scoped hard:
    // every test here is deterministic, in-process, and needs no Android device
    // and no model — the same class of code the deleted "agent E2E" suite
    // pretended to cover while never performing a real task. What is tested is
    // the part that is pure logic: URL validation, context ordering, memory
    // policy, routing, token budgeting, run-gate arbitration. The bugs that
    // actually shipped in this project (a task duplicated in the prompt, a
    // backend that loaded under one runtime and generated under another,
    // credentials scored as high-priority memory) are all catchable here in
    // under five lines each. See docs/threat-model.md and the note in AGENTS.md.
    //
    // There is no androidTest source set and there should not be one: device
    // verification is a separate, manual, explicitly-not-automated gate.
    testImplementation(libs.junit)

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
