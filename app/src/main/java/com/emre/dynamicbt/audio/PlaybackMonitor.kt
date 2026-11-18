package com.emre.dynamicbt.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import com.emre.dynamicbt.policy.ActiveTrackInfo

class PlaybackMonitor(
    private val context: Context,
    private val onAudioStateChanged: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var started = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onAudioStateChanged()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            onAudioStateChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
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
    }

    fun queryActiveTracks(allowRootFallback: Boolean = true): List<ActiveTrackInfo> {
        val configs = try {
            audioManager.activePlaybackConfigurations
        } catch (_: Exception) {
            emptyList()
        }
        return TrackFormatProbe.probeActiveTracks(
            context = context,
            audioManager = audioManager,
            configs = configs,
            allowRootFallback = allowRootFallback,
        )
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
