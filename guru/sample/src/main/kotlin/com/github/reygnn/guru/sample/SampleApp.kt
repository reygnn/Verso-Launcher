package com.github.reygnn.guru.sample

import android.app.Application
import com.github.reygnn.guru.Guru
import com.github.reygnn.guru.GuruSink
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Installs Guru the way a host does: its own single IO thread, its own words. */
class SampleApp : Application() {

    /** The host's IO thread — single, so tasks run in submission order, as Guru requires. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        Guru.install(this, GuruSink.Share(), io = io::execute, texts = SampleGuruTexts(this))
    }
}
