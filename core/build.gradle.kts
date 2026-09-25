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
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

// The agent eval suite. Pure JVM, runs in seconds, no device required.
//   ./gradlew :core:evals
tasks.register<JavaExec>("evals") {
    group = "verification"
    description = "Run the deterministic agent evaluation suite."
    mainClass.set("dev.localintelligence.core.eval.EvalMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    val model = project.findProperty("localintelligence.model") as String?
    args(if (model != null) listOf("--model", model) else emptyList())
}

// The model benchmark runner. Same JVM entry-point convention as `evals`.
//
// DESIGN NOTE — WHY THIS LIVES IN THE TEST SOURCE SET AND NOT A `benchmark:`
// GRADLE MODULE. It is a sanctioned fallback, chosen for a concrete reason
// rather than convenience: the thing it must reuse is the eval suite
// (`TaskSuite`, `EvalScorer`, `buildTaskTools`), and `buildTaskTools` is Kotlin
// `internal` in this source set. A separate module cannot see it. Reaching it
// would need `-Xfriend-paths` pointing at a test compilation — a compiler flag
// that breaks silently on a Gradle upgrade, which is the worst possible
// property for the tool whose entire job is to be trusted. Upstream, the real
// fix is to promote the task suite out of the test source set into a published
// `:evalkit` module; that is a change to existing files and so is out of scope
// for this PR.
//
// Usage (a real model, therefore a real device or a desktop llama.cpp):
//   ./gradlew :core:benchmark -Plocalintelligence.model=/sdcard/models/qwen.gguf
//   ./gradlew :core:benchmark -Plocalintelligence.modelDir=/sdcard/models
//
// With no model it runs the deterministic scripted backend, which measures the
// harness and prints `backendKind: "scripted"`. That is honest, and the label is
// there precisely so nobody mistakes it for a model measurement.
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Run the model benchmark and emit diffable JSON results."
    mainClass.set("dev.localintelligence.core.eval.harness.BenchmarkMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    val model = project.findProperty("localintelligence.model") as String?
    val modelDir = project.findProperty("localintelligence.modelDir") as String?
    val out = project.findProperty("localintelligence.out") as String?
    val requiredContext = project.findProperty("localintelligence.context") as String?
    args(
        buildList {
            model?.let { addAll(listOf("--gguf", it)) }
            modelDir?.let { addAll(listOf("--model-dir", it)) }
            out?.let { addAll(listOf("--out", it)) }
            requiredContext?.let { addAll(listOf("--context", it)) }
        },
    )
    // A refusal must fail the build. Silently returning 0 on "no model" is how
    // a benchmark starts reporting zeros, so this is not optional.
    isIgnoreExitValue = false
}
