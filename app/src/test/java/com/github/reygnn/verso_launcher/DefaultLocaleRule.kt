package com.github.reygnn.verso_launcher

import org.junit.rules.ExternalResource
import java.util.Locale

/**
 * Lets a test call [Locale.setDefault] freely: saves the JVM's default locale before the test
 * and restores it after — all three of them. `Locale.setDefault(x)` also overwrites the FORMAT
 * and DISPLAY defaults, so restoring only the main one (the old hand-written try/finally) left
 * them at the main locale and leaked into every later test of the run.
 */
class DefaultLocaleRule : ExternalResource() {
    private lateinit var main: Locale
    private lateinit var format: Locale
    private lateinit var display: Locale

    override fun before() {
        main = Locale.getDefault()
        format = Locale.getDefault(Locale.Category.FORMAT)
        display = Locale.getDefault(Locale.Category.DISPLAY)
    }

    override fun after() {
        Locale.setDefault(main)  // sets all three — so the categories go back after it
        Locale.setDefault(Locale.Category.FORMAT, format)
        Locale.setDefault(Locale.Category.DISPLAY, display)
    }
}
