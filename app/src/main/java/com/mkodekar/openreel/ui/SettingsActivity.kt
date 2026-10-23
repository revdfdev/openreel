// SPDX-License-Identifier: GPL-3.0-or-later
package com.mkodekar.openreel.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mkodekar.openreel.settings.Settings

/**
 * Settings UI: the five groups that mirror MX Player's preferences, re-implemented.
 * Values persist via Settings (SharedPreferences) and are read by the player.
 */
class SettingsActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        setContent {
            MaterialTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
                    Column(
                        Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                    ) {
                        Group("Decoder")
                        SwitchRow("Hardware decoding", s.hardwareDecoding) { s.hardwareDecoding = it }

                        Group("Player")
                        SwitchRow("Resume where I left off", s.autoResume) { s.autoResume = it }
                        StepperRow("Double-tap seek", s.seekStepSeconds, "s", 5, 60, 5) {
                            s.seekStepSeconds = it
                        }

                        Group("Audio")
                        StepperRow("Audio delay step", s.audioDelayStepMs, "ms", 10, 250, 10) {
                            s.audioDelayStepMs = it
                        }

                        Group("Subtitle")
                        SwitchRow("Auto-load sibling subtitles", s.autoLoadSubtitles) {
                            s.autoLoadSubtitles = it
                        }
                        StepperRow("Subtitle size", s.subtitleFontScale, "%", 50, 300, 10) {
                            s.subtitleFontScale = it
                        }

                        Group("General")
                        ThemeRow(s.theme) { s.theme = it }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun Group(title: String) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        HorizontalDivider()
    }

    @Composable
    private fun SwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        var checked by remember { mutableStateOf(initial) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            Switch(checked, { checked = it; onChange(it) })
        }
    }

    @Composable
    private fun StepperRow(
        label: String, initial: Int, unit: String,
        min: Int, max: Int, step: Int, onChange: (Int) -> Unit,
    ) {
        var value by remember { mutableIntStateOf(initial) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            OutlinedButton(onClick = {
                value = (value - step).coerceAtLeast(min); onChange(value)
            }) { Text("âˆ’") }
            Text("$value$unit", Modifier.padding(horizontal = 12.dp))
            OutlinedButton(onClick = {
                value = (value + step).coerceAtMost(max); onChange(value)
            }) { Text("+") }
        }
    }

    @Composable
    private fun ThemeRow(initial: String, onChange: (String) -> Unit) {
        var sel by remember { mutableStateOf(initial) }
        val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            options.forEach { (key, label) ->
                FilterChip(
                    selected = sel == key,
                    onClick = { sel = key; onChange(key) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}
