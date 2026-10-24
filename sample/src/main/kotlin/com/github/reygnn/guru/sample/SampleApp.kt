package com.github.reygnn.guru.sample

import android.annotation.SuppressLint
import android.app.Application
import com.github.reygnn.guru.Guru
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Installs Guru the way a host does: its own single IO thread, its own words. */
class SampleApp : Application() {

    /** The host's IO thread — single, so tasks run in submission order, as Guru requires. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** This start dies before Guru's save step runs ([crashTwice]); MainActivity does the dying. */
    var crashBeforeSave = false
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(CRASH_BEFORE_SAVE, false)) {
            // Cleared before the crash, or every start would crash.
            prefs.edit().remove(CRASH_BEFORE_SAVE).commitNow()
            // Ahead of Guru's steps on the single io thread: they wait while this start dies.
            io.execute { Thread.sleep(HOLD_IO_MS) }
            crashBeforeSave = true
        }
        Guru.install(this, io = io::execute, texts = SampleGuruTexts(this))
    }

    /**
     * Debug: a crash now, and another at the next start before Guru saves — so the start after
     * that has two reports to save. No hand is fast enough for that otherwise.
     */
    fun crashTwice(): Nothing {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CRASH_BEFORE_SAVE, true).commitNow()
        error("Guru sample: the first of two crashes")
    }

    // commit, not apply: the process dies right after, and apply would write too late.
    @SuppressLint("ApplySharedPref")
    private fun android.content.SharedPreferences.Editor.commitNow() {
        commit()
    }

    private companion object {
        const val PREFS = "sample"
        const val CRASH_BEFORE_SAVE = "crash-before-save"
        const val HOLD_IO_MS = 5_000L
    }
}
