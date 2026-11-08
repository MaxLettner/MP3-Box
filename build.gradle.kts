plugins {
    // AGP 9 has built-in Kotlin support; the standalone Kotlin Android plugin is rejected.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
