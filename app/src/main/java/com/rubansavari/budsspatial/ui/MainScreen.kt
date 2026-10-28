package com.rubansavari.budsspatial.ui

import android.media.Spatializer
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rubansavari.budsspatial.SpatialState
import com.rubansavari.budsspatial.SpatialTestTone
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.sin

data class PlayerUiState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val channelCount: Int? = null,
)

private val Good = Color(0xFF2E9E5B)
private val Bad = Color(0xFFD64545)

@Composable
fun MainScreen(
    spatialState: StateFlow<SpatialState>,
    toneAzimuth: StateFlow<Float>,
    tonePlaying: StateFlow<SpatialTestTone.Mode?>,
    playerState: StateFlow<PlayerUiState>,
    onPlayTone: (SpatialTestTone.Mode) -> Unit,
    onStopTone: () -> Unit,
    onOrbitSpeed: (Float) -> Unit,
    onPickFile: () -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenSoundSettings: () -> Unit,
    onOpenCompanionApp: () -> Unit,
    onRefresh: () -> Unit,
) {
    val state by spatialState.collectAsStateWithLifecycle()
    val azimuth by toneAzimuth.collectAsStateWithLifecycle()
    val playing by tonePlaying.collectAsStateWithLifecycle()
    val player by playerState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Buds Spatial", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Spatial audio for OnePlus Buds 4",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HeadsetCard(state, onRefresh)
            StatusCard(state)
            SetupCard(state, onOpenBluetoothSettings, onOpenSoundSettings, onOpenCompanionApp)
            TestCard(state, azimuth, playing, onPlayTone, onStopTone, onOrbitSpeed)
            PlayerCard(state, player, onPickFile, onTogglePlayback)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun HeadsetCard(state: SpatialState, onRefresh: () -> Unit) {
    SectionCard("Headset") {
        when {
            state.isBuds4 -> StatusRow(true, "${state.headsetName} connected")
            state.headsetName != null -> {
                StatusRow(true, "${state.headsetName} connected")
                Text(
                    "This doesn't look like OnePlus Buds 4, but the spatial audio controls below still apply.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                StatusRow(false, "No Bluetooth headset connected")
                Text(
                    "Put your OnePlus Buds 4 in your ears and connect them in Bluetooth settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }
}

@Composable
private fun StatusCard(state: SpatialState) {
    SectionCard("Spatial audio status") {
        if (!state.spatializerSupported) {
            StatusRow(false, "Android ${Build.VERSION.RELEASE} has no system spatializer (needs Android 12L+)")
            Text(
                "You can still use the buds' own spatial mode (set in the OnePlus / HeyMelody app) " +
                    "and this app's binaural test.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@SectionCard
        }
        val banner = when {
            state.fullyActive -> "Spatial audio is ON"
            state.spatializerAvailable -> "Spatial audio is available but turned off"
            else -> "Spatial audio isn't available for the current output"
        }
        Text(
            banner,
            fontWeight = FontWeight.Bold,
            color = if (state.fullyActive) Good else MaterialTheme.colorScheme.onSurface,
        )
        StatusRow(state.spatializerAvailable, "Phone can spatialize to this headset")
        StatusRow(state.spatializerEnabled, "Spatial audio switched on")
        StatusRow(state.canSpatializeSurround, "Surround (5.1) content is spatialized")
        StatusRow(state.canSpatializeStereo, "Stereo music is spatialized")
        StatusRow(state.headTrackerAvailable, "Head tracking sensor available")
        Text(
            "Immersive level: ${immersiveLevelLabel(state.immersiveLevel)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupCard(
    state: SpatialState,
    onOpenBluetoothSettings: () -> Unit,
    onOpenSoundSettings: () -> Unit,
    onOpenCompanionApp: () -> Unit,
) {
    SectionCard("Turn on spatial audio") {
        val steps = buildList {
            add("Connect your OnePlus Buds 4.")
            add("Bluetooth settings → tap ⚙ next to the buds → turn on \"Spatial audio\" (and \"Head tracking\" if shown).")
            add("On non-OnePlus phones, also open HeyMelody → Spatial audio, and pick Fixed or Head tracking mode.")
            add("Come back here — the status above updates automatically. Then run the test below.")
        }
        steps.forEachIndexed { i, step ->
            Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.spatializerSupported && !state.spatializerAvailable && state.headsetName != null) {
            Text(
                "Your phone reports no spatializer for this headset. Use the buds' built-in spatial " +
                    "mode from the companion app instead.",
                style = MaterialTheme.typography.bodySmall,
                color = Bad,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenBluetoothSettings) { Text("Bluetooth") }
            OutlinedButton(onClick = onOpenSoundSettings) { Text("Sound") }
        }
        OutlinedButton(onClick = onOpenCompanionApp) { Text("Open OnePlus / HeyMelody app") }
    }
}

@Composable
private fun TestCard(
    state: SpatialState,
    azimuth: Float,
    playing: SpatialTestTone.Mode?,
    onPlayTone: (SpatialTestTone.Mode) -> Unit,
    onStopTone: () -> Unit,
    onOrbitSpeed: (Float) -> Unit,
) {
    SectionCard("Spatial test") {
        Text(
            "A sound circles your head. With spatial audio working it should feel like it moves " +
                "in front of and behind you, not just left to right.",
            style = MaterialTheme.typography.bodySmall,
        )
        Box(Modifier.fillMaxWidth()) {
            OrbitView(azimuth = azimuth, active = playing != null, modifier = Modifier.align(Alignment.Center))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onPlayTone(SpatialTestTone.Mode.SURROUND) },
                enabled = playing != SpatialTestTone.Mode.SURROUND,
            ) { Text("5.1 (system)") }
            OutlinedButton(
                onClick = { onPlayTone(SpatialTestTone.Mode.BINAURAL) },
                enabled = playing != SpatialTestTone.Mode.BINAURAL,
            ) { Text("Binaural (app)") }
        }
        if (playing != null) {
            OutlinedButton(onClick = onStopTone) { Text("Stop") }
        }
        Text(
            when (playing) {
                SpatialTestTone.Mode.SURROUND ->
                    if (state.canSpatializeSurround) "Playing 5.1 through Android's spatializer."
                    else "Playing 5.1, but the spatializer is off — Android will downmix it to plain stereo."
                SpatialTestTone.Mode.BINAURAL -> "Playing the app's own binaural rendering (no head tracking)."
                null -> "\"5.1 (system)\" uses the phone's spatializer; \"Binaural\" works on any phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var orbit by remember { mutableFloatStateOf(8f) }
        Text("Orbit time: ${orbit.toInt()} s", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = orbit,
            onValueChange = {
                orbit = it
                onOrbitSpeed(it)
            },
            valueRange = 2f..20f,
        )
    }
}

@Composable
private fun OrbitView(azimuth: Float, active: Boolean, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val head = MaterialTheme.colorScheme.onSurface
    val dot = if (active) MaterialTheme.colorScheme.primary else ring
    Canvas(modifier.size(160.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 - 12.dp.toPx()
        drawCircle(ring, radius, center, style = Stroke(2.dp.toPx()))
        drawCircle(head, 16.dp.toPx(), center)
        // Nose, so it's clear which way is "front".
        drawCircle(head, 5.dp.toPx(), Offset(center.x, center.y - 18.dp.toPx()))
        val rad = Math.toRadians(azimuth.toDouble())
        val pos = Offset(
            center.x + (radius * sin(rad)).toFloat(),
            center.y - (radius * cos(rad)).toFloat(),
        )
        drawCircle(dot, 10.dp.toPx(), pos)
    }
}

@Composable
private fun PlayerCard(
    state: SpatialState,
    player: PlayerUiState,
    onPickFile: () -> Unit,
    onTogglePlayback: () -> Unit,
) {
    SectionCard("Play your own media") {
        Text(
            "Pick a song or movie from your phone. Multichannel files (5.1 / 7.1, Dolby) give the " +
                "strongest effect.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFile) { Text("Choose file") }
            if (player.title != null) {
                OutlinedButton(onClick = onTogglePlayback) { Text(if (player.isPlaying) "Pause" else "Play") }
            }
        }
        player.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        player.channelCount?.let { channels ->
            val spatial = if (channels > 2) state.canSpatializeSurround else state.canSpatializeStereo
            StatusRow(spatial, "${channelLabel(channels)} track — ${if (spatial) "spatialized" else "not spatialized"}")
        }
    }
}

@Composable
private fun StatusRow(ok: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "✕", color = if (ok) Good else Bad, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun channelLabel(channels: Int) = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels-channel"
}

private fun immersiveLevelLabel(level: Int): String {
    if (Build.VERSION.SDK_INT < 32) return "n/a"
    return when (level) {
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE -> "none"
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> "multichannel"
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_OTHER -> "other"
        else -> "unknown"
    }
}
