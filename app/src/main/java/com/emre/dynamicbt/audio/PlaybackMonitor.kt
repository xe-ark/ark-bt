package com.emre.dynamicbt.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.emre.dynamicbt.policy.ActiveTrackInfo

class PlaybackMonitor(
    private val context: Context,
    private val onAudioStateChanged: () -> Unit,
) {
    private companion object {
        /**
         * Track probing shells out to dumpsys. Re-probing on every service
         * tick (1 s) was a steady source of CPU spikes, so results are reused
         * until the next playback callback or this TTL expires.
         */
        const val TRACK_CACHE_TTL_MS = 2_500L
    }

    private data class TrackCache(val atMs: Long, val tracks: List<ActiveTrackInfo>)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var started = false

    @Volatile
    private var trackCache: TrackCache? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            trackCache = null
            onAudioStateChanged()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            trackCache = null
            onAudioStateChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            trackCache = null
            onAudioStateChanged()
        }
    }

    fun start() {
        if (started) return
        val thread = HandlerThread("DynamicBtAudioMonitor").apply { start() }
        val h = Handler(thread.looper)
        handlerThread = thread
        handler = h
        audioManager.registerAudioPlaybackCallback(playbackCallback, h)
        audioManager.registerAudioDeviceCallback(deviceCallback, h)
        started = true
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (_: Exception) {
        }
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        trackCache = null
    }

    fun queryActiveTracks(allowRootFallback: Boolean = true): List<ActiveTrackInfo> {
        val now = SystemClock.elapsedRealtime()
        trackCache?.let {
            if (now - it.atMs <= TRACK_CACHE_TTL_MS) return it.tracks
        }

        val configs = try {
            audioManager.activePlaybackConfigurations
        } catch (_: Exception) {
            emptyList()
        }
        val tracks = TrackFormatProbe.probeActiveTracks(
            context = context,
            audioManager = audioManager,
            configs = configs,
            allowRootFallback = allowRootFallback,
        )
        trackCache = TrackCache(now, tracks)
        return tracks
    }

    fun isBluetoothA2dpOutputActive(): Boolean {
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            return false
        }
        return outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }
}
