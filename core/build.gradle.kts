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
