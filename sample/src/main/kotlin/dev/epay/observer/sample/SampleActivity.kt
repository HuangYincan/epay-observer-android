package dev.epay.observer.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** External launches can only see fixed status and stop; they cannot start sampling. */
class SampleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(TextView(this).apply { setText(R.string.sample_notice) })
        val status = TextView(this)
        layout.addView(status)
        layout.addView(Button(this).apply {
            setText(R.string.sample_stop)
            setOnClickListener { SampleRuntime.gate(this@SampleActivity).stop() }
        })
        setContentView(layout)
        status.post(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val state = SampleRuntime.gate(this@SampleActivity).snapshot()
                status.text = getString(R.string.sample_status, state.phase.name, state.count)
                status.postDelayed(this, 500)
            }
        })
    }
}
