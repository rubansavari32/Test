package com.rubansavari.budsspatial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.audiofx.AudioEffect
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One other app's audio session that we've attached a virtualizer to. */
data class AttachedSession(
    val sessionId: Int,
    val packageName: String?,
    /** True when the virtualizer accepted binaural (headphone 3D) mode. */
    val binaural: Boolean,
    val error: String? = null,
)

data class MusicSpatialState(
    val running: Boolean = false,
    val sessions: List<AttachedSession> = emptyList(),
)

/**
 * Spatializes stereo music playing in other apps, such as Spotify.
 *
 * Android doesn't let an app read or re-process another app's audio. What it does allow:
 * music apps broadcast [AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] with their
 * audio session ID so equalizer apps can attach platform effects to it. We attach the
 * platform [Virtualizer] in binaural mode, which renders stereo as if it came from speakers
 * in front of you instead of inside your head.
 *
 * The service runs in the foreground so the effects stay alive while the app is closed.
 */
class MusicSpatialService : Service() {

    private val virtualizers = mutableMapOf<Int, Virtualizer>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
            if (session == AudioEffect.ERROR || session == 0) return
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(session, pkg)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detach(session)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // Sent by other apps, so the receiver must be exported.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        _state.value = MusicSpatialState(running = true)
        if (prefs(this).getBoolean(PREF_GLOBAL, false)) attach(GLOBAL_SESSION, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                val strength = strength(this)
                virtualizers.values.forEach { runCatching { it.setStrength(strength) } }
                val global = prefs(this).getBoolean(PREF_GLOBAL, false)
                if (global && GLOBAL_SESSION !in virtualizers) attach(GLOBAL_SESSION, null)
                if (!global) detach(GLOBAL_SESSION)
            }
            ACTION_ATTACH -> {
                val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
                if (session != AudioEffect.ERROR && session != 0) {
                    attach(session, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        virtualizers.values.forEach { it.release() }
        virtualizers.clear()
        _state.value = MusicSpatialState(running = false)
        super.onDestroy()
    }

    private fun attach(session: Int, pkg: String?) {
        detach(session)
        val info = try {
            val v = Virtualizer(EFFECT_PRIORITY, session)
            val binaural = runCatching {
                v.canVirtualize(AudioFormat.CHANNEL_OUT_STEREO, Virtualizer.VIRTUALIZATION_MODE_BINAURAL) &&
                    v.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL)
            }.getOrDefault(false)
            if (v.strengthSupported) v.setStrength(strength(this))
            v.enabled = true
            virtualizers[session] = v
            AttachedSession(session, pkg, binaural)
        } catch (e: Exception) {
            AttachedSession(session, pkg, binaural = false, error = e.message ?: e.javaClass.simpleName)
        }
        publish(info)
    }

    private fun detach(session: Int) {
        virtualizers.remove(session)?.release()
        _state.value = _state.value.copy(sessions = _state.value.sessions.filter { it.sessionId != session })
    }

    private fun publish(info: AttachedSession) {
        val others = _state.value.sessions.filter { it.sessionId != info.sessionId }
        _state.value = _state.value.copy(sessions = others + info)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Music spatializer", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MusicSpatialService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Spatializing music")
            .setContentText("Spotify and other music apps get 3D sound on your buds")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL_ID = "music_spatializer"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_UPDATE = "com.rubansavari.budsspatial.UPDATE"
        private const val ACTION_ATTACH = "com.rubansavari.budsspatial.ATTACH"
        private const val ACTION_STOP = "com.rubansavari.budsspatial.STOP"
        private const val EFFECT_PRIORITY = 1000
        private const val PREF_STRENGTH = "virtualizer_strength"
        private const val PREF_GLOBAL = "global_mode"

        /** Session 0 is the deprecated global output mix; many phones now ignore it. */
        const val GLOBAL_SESSION = 0

        private val _state = MutableStateFlow(MusicSpatialState())
        val state: StateFlow<MusicSpatialState> = _state.asStateFlow()

        private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun strength(context: Context): Short = prefs(context).getInt(PREF_STRENGTH, 800).toShort()

        fun globalMode(context: Context): Boolean = prefs(context).getBoolean(PREF_GLOBAL, false)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MusicSpatialService::class.java))
        }

        /** Starts the service (if needed) and attaches to a session another app handed us. */
        fun attachSession(context: Context, session: Int, packageName: String?) {
            val intent = Intent(context, MusicSpatialService::class.java)
                .setAction(ACTION_ATTACH)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MusicSpatialService::class.java))
        }

        /** Saves settings and applies them to the running service. */
        fun update(context: Context, strength: Short, global: Boolean) {
            prefs(context).edit()
                .putInt(PREF_STRENGTH, strength.toInt())
                .putBoolean(PREF_GLOBAL, global)
                .apply()
            if (_state.value.running) {
                context.startService(Intent(context, MusicSpatialService::class.java).setAction(ACTION_UPDATE))
            }
        }
    }
}
