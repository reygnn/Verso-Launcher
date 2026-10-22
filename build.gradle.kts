// Top-level build file. AGP 9 ships Kotlin built-in — do NOT add the
// org.jetbrains.kotlin.android plugin. Verso Launcher applies NO Kotlin plugins
// at all (no Compose, no serialization): built-in Kotlin is enough.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt) apply false
}
