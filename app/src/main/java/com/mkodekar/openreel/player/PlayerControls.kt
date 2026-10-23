// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Minimal transport overlay: play/pause + scrubber + time. Toggled by a single tap
 * on the video. Built in code so the scaffold needs no XML; swap for your own
 * styled layout later. Implements MpvCore.Observer to stay in sync.
 */
@SuppressLint("ViewConstructor")
class PlayerControls(
    context: Context,
    private val mpv: MpvCore,
    private val onTracks: () -> Unit,
    private val onOptions: () -> Unit,
) : FrameLayout(context), MpvCore.Observer {

    private val playPause = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_media_pause)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { mpv.togglePause() }
    }
    private val tracksBtn = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_sort_by_size)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "Audio & subtitles"
        setOnClickListener { onTracks() }
    }
    private val optionsBtn = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_manage)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "Speed & sleep timer"
        setOnClickListener { onOptions() }
    }
    private val seekBar = SeekBar(context).apply { max = 1000 }
    private val timeLabel = TextView(context).apply {
        setTextColor(Color.WHITE); text = "0:00 / 0:00"
    }

    private var userScrubbing = false

    init {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 16, 24, 16)
            addView(playPause)
            addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timeLabel)
            addView(tracksBtn)
            addView(optionsBtn)
        }
        addView(bar, LayoutParams(-1, -2, Gravity.BOTTOM))

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { userScrubbing = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userScrubbing = false
                val dur = mpv.durationMs
                if (dur > 0) mpv.seekTo(dur * sb.progress / 1000)
            }
        })
        mpv.observer = this
    }

    fun toggleVisibility() {
        visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // MpvCore.Observer -------------------------------------------------------
    override fun onPosition(positionMs: Long, durationMs: Long) {
        post {
            if (!userScrubbing && durationMs > 0) {
                seekBar.progress = (positionMs * 1000 / durationMs).toInt()
            }
            timeLabel.text = "${fmt(positionMs)} / ${fmt(durationMs)}"
        }
    }

    override fun onPauseChanged(paused: Boolean) {
        post {
            playPause.setImageResource(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            )
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }
}
