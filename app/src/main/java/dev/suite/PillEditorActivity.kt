package dev.suite

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** TEMPORARY: one-off editor to place the status pill. Delete once the values are noted. */
class PillEditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Island.load(this)
        val g = Island.geo
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 280, 40, 80) }

        fun slider(name: String, min: Float, max: Float, step: Float, get: () -> Float, set: (Float) -> Unit) {
            val label = TextView(this).apply { textSize = 15f }
            fun show() { label.text = "$name: ${"%.1f".format(get())}" }
            show()
            col.addView(label)
            val steps = ((max - min) / step).toInt()
            col.addView(SeekBar(this).apply {
                this.max = steps
                progress = ((get() - min) / step).toInt().coerceIn(0, steps)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                        if (!user) return
                        set(min + p * step); show(); Island.save(this@PillEditorActivity)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
            // fine steps
            val row = LinearLayout(this)
            row.addView(Button(this).apply { text = "−"; setOnClickListener { set(get() - step); show(); Island.save(this@PillEditorActivity) } })
            row.addView(Button(this).apply { text = "+"; setOnClickListener { set(get() + step); show(); Island.save(this@PillEditorActivity) } })
            col.addView(row)
        }

        col.addView(TextView(this).apply { textSize = 13f; text = "Pixels. \"Show\" keeps the pill out while you adjust. Hole = the black circle that must cover the camera exactly." })
        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply { text = "Show"; setOnClickListener { Island.show(this@PillEditorActivity, "Morning", still = true) } })
        buttons.addView(Button(this).apply { text = "Play"; setOnClickListener { Island.hide(); postDelayed({ Island.show(this@PillEditorActivity, "Morning") }, 1000) } })
        buttons.addView(Button(this).apply { text = "Hide"; setOnClickListener { Island.hide() } })
        col.addView(buttons)
        slider("Hole X", 600f, 840f, 1f, { g.holeX }) { g.holeX = it }
        slider("Hole Y", 0f, 140f, 1f, { g.holeY }) { g.holeY = it }
        slider("Hole radius", 5f, 70f, 0.5f, { g.holeR }) { g.holeR = it }
        slider("Gap hole → pill", -40f, 200f, 1f, { g.gap }) { g.gap = it }
        slider("Pill Y", 20f, 160f, 1f, { g.pillY }) { g.pillY = it }
        slider("Pill height", 30f, 140f, 1f, { g.pillH }) { g.pillH = it }
        slider("Text size (sp)", 8f, 20f, 0.5f, { g.textSp }) { g.textSp = it }
        slider("Left limit (clock)", 80f, 500f, 1f, { g.leftLimit }) { g.leftLimit = it }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
