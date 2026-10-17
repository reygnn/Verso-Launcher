package com.github.reygnn.verso_launcher

import org.junit.rules.ExternalResource
import java.util.Locale

/**
 * Restores all three default locales after a test: `Locale.setDefault(x)` also overwrites the
 * FORMAT and DISPLAY defaults, so restoring only the main one leaks them into later tests.
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
        Locale.setDefault(main)  // sets all three, so the categories go back after it
        Locale.setDefault(Locale.Category.FORMAT, format)
        Locale.setDefault(Locale.Category.DISPLAY, display)
    }
}
