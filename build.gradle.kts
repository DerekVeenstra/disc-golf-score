// Top-level build file. Plugin versions are declared here (applied per-module with `apply false`
// at the root, then `id(...)` without a version in app/build.gradle.kts).
//
// AGP 9+ has built-in Kotlin support: no org.jetbrains.kotlin.android or
// org.jetbrains.kotlin.plugin.compose plugin needed. See
// https://developer.android.com/build/migrate-to-built-in-kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
