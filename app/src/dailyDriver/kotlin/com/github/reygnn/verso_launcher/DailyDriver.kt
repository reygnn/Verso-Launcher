package com.github.reygnn.verso_launcher

/**
 * The daily-driver build: debug builds and a release built with `-PdailyDriver`. Its twin in
 * src/public/kotlin says false; app/build.gradle.kts picks one, so a public release has the
 * personal-only surfaces (`~crash`) compiled out.
 */
internal object DailyDriver {
    const val ENABLED = true
}
