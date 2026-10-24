// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.settings.Settings

/**
 * Settings, grouped the way MX Player groups its preferences (Decoder, Player,
 * Audio, Subtitle, General). Drawn with the MX widgets, so the switches and
 * steppers match the rest of the app rather than Material's.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        setContent {
            MxTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Mx.colors.background)
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    MxToolbar(title = "Settings", onBack = { finish() })
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                    ) {
                        MxSectionLabel("Decoder")
                        SwitchRow(
                            "Hardware decoding",
                            "Use the device decoder when the codec allows it",
                            s.hardwareDecoding,
                        ) { s.hardwareDecoding = it }

                        MxSectionLabel("Player")
                        SwitchRow("Resume where I left off", null, s.autoResume) {
                            s.autoResume = it
                        }
                        StepperRow("Double-tap seek", s.seekStepSeconds, "s", 5, 60, 5) {
                            s.seekStepSeconds = it
                        }

                        MxSectionLabel("Audio")
                        StepperRow("Audio delay step", s.audioDelayStepMs, "ms", 10, 250, 10) {
                            s.audioDelayStepMs = it
                        }

                        MxSectionLabel("Subtitle")
                        SwitchRow("Auto-load sibling subtitles", null, s.autoLoadSubtitles) {
                            s.autoLoadSubtitles = it
                        }
                        StepperRow("Subtitle size", s.subtitleFontScale, "%", 50, 300, 10) {
                            s.subtitleFontScale = it
                        }

                        MxSectionLabel("General")
                        ThemeRow(s.theme) { s.theme = it }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(
        label: String,
        hint: String?,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        var on by remember { mutableStateOf(initial) }
        Row(
            Modifier
                .fillMaxWidth()
                .mxClickable { on = !on; onChange(on) }
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MxText(label, Mx.type.primary)
                if (hint != null) {
                    Spacer(Modifier.height(2.dp))
                    MxText(hint, Mx.type.secondary, Mx.colors.textSecondary)
                }
            }
            MxSwitch(on)
        }
        MxDivider()
    }

    /** Flat track + knob, matching MX's BlueModernSwitch rather than Material's. */
    @Composable
    private fun MxSwitch(on: Boolean) {
        val track by animateColorAsState(
            if (on) Mx.colors.accent else Mx.colors.surface, tween(140), label = "track",
        )
        val knobOffset by animateDpAsState(if (on) 18.dp else 2.dp, tween(140), label = "knob")
        Box(
            Modifier
                .size(40.dp, 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(track),
        ) {
            Box(
                Modifier
                    .padding(start = knobOffset, top = 2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
            )
        }
    }

    @Composable
    private fun StepperRow(
        label: String,
        initial: Int,
        unit: String,
        min: Int,
        max: Int,
        step: Int,
        onChange: (Int) -> Unit,
    ) {
        var value by remember { mutableIntStateOf(initial) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MxText(label, Mx.type.primary, modifier = Modifier.weight(1f))
            StepButton("−", value > min) {
                value = (value - step).coerceAtLeast(min); onChange(value)
            }
            Box(Modifier.width(62.dp), contentAlignment = Alignment.Center) {
                MxText("$value$unit", Mx.type.mediumPrimary)
            }
            StepButton("+", value < max) {
                value = (value + step).coerceAtMost(max); onChange(value)
            }
        }
        MxDivider()
    }

    @Composable
    private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Mx.colors.surface)
                .mxClickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            MxText(
                glyph,
                Mx.type.mediumPrimary,
                if (enabled) Mx.colors.textPrimary else Mx.colors.textSecondary.copy(alpha = 0.4f),
            )
        }
    }

    @Composable
    private fun ThemeRow(initial: String, onChange: (String) -> Unit) {
        var sel by remember { mutableStateOf(initial) }
        val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        Column(Modifier.padding(vertical = 10.dp)) {
            MxText("Theme", Mx.type.primary)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (key, label) ->
                    val active = sel == key
                    val bg by animateColorAsState(
                        if (active) Mx.colors.accent else Mx.colors.surface,
                        tween(130), label = "themeChip",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(
                                when (i) {
                                    0 -> RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                                    options.lastIndex -> RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                                    else -> RoundedCornerShape(0.dp)
                                }
                            )
                            .background(bg)
                            .mxClickable { sel = key; onChange(key) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MxText(
                            label,
                            Mx.type.secondary,
                            if (active) Mx.colors.onAccent else Mx.colors.textSecondary,
                        )
                    }
                    if (i < options.lastIndex) Spacer(Modifier.width(2.dp))
                }
            }
        }
        MxDivider()
    }
}
