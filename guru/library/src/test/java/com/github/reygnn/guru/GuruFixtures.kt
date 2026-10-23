package com.github.reygnn.guru

import org.junit.rules.ExternalResource
import java.util.Locale

internal val testEnv = GuruEnv(
    packageName = "com.example.app",
    versionName = "1.2.3",
    versionCode = 42,
    androidRelease = "16",
    sdk = 36,
    brand = "google",
    model = "Pixel 9a",
    device = "tegu",
)

internal fun report(
    id: String = "0b6c2f8e-1d2a-4c3b-9e8f-112233445566",
    kind: GuruKind = GuruKind.CRASH,
    crashedAt: Long = 1_761_200_000_000,
    startedAt: Long = 1_761_199_000_000,
    thread: String = "main",
    trace: String = "java.lang.IllegalStateException: boom\n\tat com.example.app.Main.run(Main.kt:12)",
    env: GuruEnv = testEnv,
) = GuruReport(id, kind, crashedAt, startedAt, thread, trace, env)

/** Restores all three default locales (main, FORMAT, DISPLAY), as Verso's DefaultLocaleRule does. */
class GuruLocaleRule : ExternalResource() {
    private lateinit var main: Locale
    private lateinit var format: Locale
    private lateinit var display: Locale

    override fun before() {
        main = Locale.getDefault()
        format = Locale.getDefault(Locale.Category.FORMAT)
        display = Locale.getDefault(Locale.Category.DISPLAY)
    }

    override fun after() {
        Locale.setDefault(main)
        Locale.setDefault(Locale.Category.FORMAT, format)
        Locale.setDefault(Locale.Category.DISPLAY, display)
    }
}
