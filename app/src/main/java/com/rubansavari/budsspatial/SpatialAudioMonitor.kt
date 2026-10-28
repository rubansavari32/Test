package com.rubansavari.budsspatial

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of everything the UI needs to know about spatial audio on this phone. */
data class SpatialState(
    /** Name of the connected Bluetooth headset, or null if none. */
    val headsetName: String? = null,
    /** True when the connected headset looks like a pair of OnePlus Buds 4. */
    val isBuds4: Boolean = false,
    /** Android's Spatializer API exists on this OS version (Android 12L / API 32+). */
    val spatializerSupported: Boolean = false,
    /** The OS has a spatializer that can render to the current output device. */
    val spatializerAvailable: Boolean = false,
    /** The user has spatial audio turned on in system settings. */
    val spatializerEnabled: Boolean = false,
    /** One of the Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_* constants, or -1. */
    val immersiveLevel: Int = -1,
    /** 5.1 content would be spatialized right now. */
    val canSpatializeSurround: Boolean = false,
    /** Stereo content would be spatialized right now. */
    val canSpatializeStereo: Boolean = false,
    /** A head tracking sensor (in the buds) is available to the spatializer. */
    val headTrackerAvailable: Boolean = false,
) {
    /** Everything is lined up: OS spatializer is on and will process audio. */
    val fullyActive: Boolean
        get() = spatializerAvailable && spatializerEnabled && canSpatializeSurround
}

/**
 * Watches the audio route and the platform [Spatializer] and publishes a [SpatialState].
 *
 * Detection uses [AudioManager.getDevices], which reports the Bluetooth product name
 * without needing the BLUETOOTH_CONNECT runtime permission.
 */
class SpatialAudioMonitor(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val executor = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(SpatialState())
    val state: StateFlow<SpatialState> = _state.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private var spatializerListener: Any? = null
    private var headTrackerListener: Any? = null

    fun start() {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT >= 32) registerSpatializerListeners()
        refresh()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= 32) unregisterSpatializerListeners()
    }

    fun refresh() {
        val headset = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in BLUETOOTH_OUTPUT_TYPES }
        val name = headset?.productName?.toString()?.takeIf { it.isNotBlank() }

        var next = SpatialState(headsetName = name, isBuds4 = name != null && isOnePlusBuds4(name))
        if (Build.VERSION.SDK_INT >= 32) next = withSpatializerInfo(next)
        _state.value = next
    }

    @RequiresApi(32)
    private fun withSpatializerInfo(base: SpatialState): SpatialState {
        val spatializer = audioManager.spatializer
        val attrs = mediaAttributes()
        return base.copy(
            spatializerSupported = true,
            spatializerAvailable = spatializer.isAvailable,
            spatializerEnabled = spatializer.isEnabled,
            immersiveLevel = spatializer.immersiveAudioLevel,
            canSpatializeSurround = spatializer.canBeSpatialized(
                attrs, pcmFormat(AudioFormat.CHANNEL_OUT_5POINT1)
            ),
            canSpatializeStereo = spatializer.canBeSpatialized(
                attrs, pcmFormat(AudioFormat.CHANNEL_OUT_STEREO)
            ),
            headTrackerAvailable = Build.VERSION.SDK_INT >= 33 && spatializer.isHeadTrackerAvailable,
        )
    }

    @RequiresApi(32)
    private fun registerSpatializerListeners() {
        val spatializer = audioManager.spatializer
        val stateListener = object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerEnabledChanged(spat: Spatializer, enabled: Boolean) = refresh()
            override fun onSpatializerAvailableChanged(spat: Spatializer, available: Boolean) = refresh()
        }
        spatializer.addOnSpatializerStateChangedListener(executor, stateListener)
        spatializerListener = stateListener

        if (Build.VERSION.SDK_INT >= 33) {
            val trackerListener = Spatializer.OnHeadTrackerAvailableListener { _, _ -> refresh() }
            spatializer.addOnHeadTrackerAvailableListener(executor, trackerListener)
            headTrackerListener = trackerListener
        }
    }

    @RequiresApi(32)
    private fun unregisterSpatializerListeners() {
        val spatializer = audioManager.spatializer
        (spatializerListener as? Spatializer.OnSpatializerStateChangedListener)?.let {
            spatializer.removeOnSpatializerStateChangedListener(it)
        }
        spatializerListener = null
        if (Build.VERSION.SDK_INT >= 33) {
            (headTrackerListener as? Spatializer.OnHeadTrackerAvailableListener)?.let {
                spatializer.removeOnHeadTrackerAvailableListener(it)
            }
        }
        headTrackerListener = null
    }

    companion object {
        private val BLUETOOTH_OUTPUT_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }

        fun isOnePlusBuds4(name: String): Boolean {
            val n = name.lowercase()
            return "buds" in n && "4" in n && ("oneplus" in n || "one plus" in n)
        }

        /** Attributes for movie-style media, which is what spatializers target. */
        fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .apply {
                if (Build.VERSION.SDK_INT >= 32) {
                    setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO)
                }
            }
            .build()

        fun pcmFormat(channelMask: Int, sampleRate: Int = 48_000): AudioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
    }
}
