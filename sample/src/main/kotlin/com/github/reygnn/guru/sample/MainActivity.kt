package com.github.reygnn.guru.sample

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.github.reygnn.guru.Guru

/**
 * Buttons to die by: a crash on the main thread, and — debug builds only — an ANR and two crashes
 * in a row. On the next start Guru saves what it caught to Download/Guru and says so.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(!(application as SampleApp).crashBeforeSave) { "Guru sample: the second of two crashes, before the save" }
        setContentView(content())
        Guru.showPending(this, ::onUi)
    }

    // The host's way back to the main thread: a destroyed activity gets nothing.
    private fun onUi(block: () -> Unit) = mainExecutor.execute { if (!isDestroyed) block() }

    private fun content(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { setText(R.string.sample_intro) })
        addView(button(R.string.sample_crash) { error("Guru sample: crash on the main thread") })
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Blocks the main thread; touch the screen until the system declares the ANR.
            addView(button(R.string.sample_anr) { Thread.sleep(ANR_BLOCK_MS) })
            addView(button(R.string.sample_crash_twice) { (application as SampleApp).crashTwice() })
        }
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val pad = (PAD_DP * resources.displayMetrics.density).toInt()
            view.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }
    }

    private fun button(text: Int, onClick: () -> Unit) = Button(this).apply {
        setText(text)
        setOnClickListener { onClick() }
    }

    private companion object {
        const val ANR_BLOCK_MS = 60_000L
        const val PAD_DP = 16
    }
}
