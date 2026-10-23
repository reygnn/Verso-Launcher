// Top-level build file. AGP 9 ships Kotlin built-in — do NOT add the
// org.jetbrains.kotlin.android plugin. Guru applies no Kotlin plugins at all.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt) apply false
}
