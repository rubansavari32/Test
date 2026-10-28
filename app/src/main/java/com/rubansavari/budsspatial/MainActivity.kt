package com.rubansavari.budsspatial

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.rubansavari.budsspatial.ui.AppTheme
import com.rubansavari.budsspatial.ui.MainScreen
import com.rubansavari.budsspatial.ui.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var monitor: SpatialAudioMonitor
    private val testTone = SpatialTestTone()
    private lateinit var player: ExoPlayer
    private val playerState = MutableStateFlow(PlayerUiState())

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playFile(uri)
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The service runs either way; the permission only makes its notification visible.
            MusicSpatialService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        monitor = SpatialAudioMonitor(this)
        player = buildPlayer()
        handleEqualizerIntent(intent)

        setContent {
            AppTheme {
                MainScreen(
                    spatialState = monitor.state,
                    toneAzimuth = testTone.azimuth,
                    tonePlaying = testTone.playing,
                    playerState = playerState,
                    onPlayTone = { mode ->
                        player.pause()
                        testTone.start(mode)
                    },
                    onStopTone = testTone::stop,
                    onOrbitSpeed = { testTone.orbitSeconds = it },
                    onPickFile = {
                        pickMedia.launch(arrayOf("audio/*", "video/*"))
                    },
                    onTogglePlayback = {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            testTone.stop()
                            player.play()
                        }
                    },
                    musicState = MusicSpatialService.state,
                    initialStrength = MusicSpatialService.strength(this),
                    initialGlobalMode = MusicSpatialService.globalMode(this),
                    onMusicSpatialToggle = ::setMusicSpatial,
                    onMusicSettings = { strength, global -> MusicSpatialService.update(this, strength, global) },
                    onOpenSpotify = ::openSpotify,
                    onOpenBluetoothSettings = { launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onOpenSoundSettings = { launch(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                    onOpenCompanionApp = ::openCompanionApp,
                    onRefresh = monitor::refresh,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEqualizerIntent(intent)
    }

    /** Spotify's "Equalizer" button opens us with its audio session; spatialize it. */
    private fun handleEqualizerIntent(intent: Intent?) {
        if (intent?.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (session != AudioEffect.ERROR && session != 0) {
            MusicSpatialService.attachSession(this, session, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
        }
    }

    override fun onStart() {
        super.onStart()
        monitor.start()
    }

    override fun onResume() {
        super.onResume()
        // The user may have just toggled spatial audio in Settings.
        monitor.refresh()
    }

    override fun onStop() {
        monitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        testTone.stop()
        player.release()
        super.onDestroy()
    }

    /**
     * ExoPlayer checks the platform Spatializer itself and keeps multichannel tracks
     * intact (instead of downmixing to stereo) when they can be spatialized.
     */
    private fun buildPlayer(): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .build()
        return ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playerState.value = playerState.value.copy(isPlaying = isPlaying)
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        playerState.value = playerState.value.copy(channelCount = selectedChannels(tracks))
                    }
                })
            }
    }

    private fun selectedChannels(tracks: Tracks): Int? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    return group.getTrackFormat(i).channelCount.takeIf { it > 0 }
                }
            }
        }
        return null
    }

    private fun playFile(uri: Uri) {
        testTone.stop()
        playerState.value = PlayerUiState(title = uri.lastPathSegment?.substringAfterLast('/'))
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }

    private fun setMusicSpatial(enabled: Boolean) {
        if (!enabled) {
            MusicSpatialService.stop(this)
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MusicSpatialService.start(this)
        }
    }

    private fun openSpotify() {
        val intent = packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPOTIFY_PACKAGE"))
        launch(intent)
    }

    /**
     * OnePlus phones have Buds settings built into Bluetooth settings; on other phones
     * the HeyMelody app controls the buds' own spatial audio / head tracking switches.
     */
    private fun openCompanionApp() {
        for (pkg in COMPANION_PACKAGES) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                launch(it)
                return
            }
        }
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${COMPANION_PACKAGES.first()}"))
        if (!launch(store, showError = false)) {
            launch(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${COMPANION_PACKAGES.first()}"),
                )
            )
        }
    }

    private fun launch(intent: Intent, showError: Boolean = true): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        if (showError) Toast.makeText(this, "No app found to handle this", Toast.LENGTH_SHORT).show()
        false
    }

    private companion object {
        /** HeyMelody first (what the Play Store link points to), then the older OnePlus app. */
        const val SPOTIFY_PACKAGE = "com.spotify.music"

        val COMPANION_PACKAGES = listOf("com.heytap.headset", "com.oneplus.twspods")
    }
}
