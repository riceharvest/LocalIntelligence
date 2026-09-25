plugins {
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// :core is the frozen interface surface. :android and :app are implementation
// detail and are not API-frozen.
apiValidation {
    ignoredProjects += listOf("android", "app")
}
