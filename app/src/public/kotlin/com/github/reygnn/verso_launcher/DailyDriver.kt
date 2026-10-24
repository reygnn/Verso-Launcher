package com.github.reygnn.verso_launcher

/**
 * The public release: built without `-PdailyDriver`. Its twin in src/dailyDriver/kotlin says
 * true; app/build.gradle.kts picks one, so the personal-only surfaces (`~crash`) are compiled out.
 */
internal object DailyDriver {
    const val ENABLED = false
}
