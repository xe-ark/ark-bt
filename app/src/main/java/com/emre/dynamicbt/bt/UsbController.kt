package com.emre.dynamicbt.bt

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class UsbAudioState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val sampleRates: List<Int> = emptyList(),
    val encodings: List<Int> = emptyList(),
)

class UsbController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun readState(): UsbAudioState {
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            return UsbAudioState()
        }

        val usbDevices = outputs.filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (usbDevices.isEmpty()) {
            return UsbAudioState(connected = false)
        }

        val first = usbDevices.first()
        val rates = usbDevices.flatMap { it.sampleRates.toList() }.distinct().sorted()
        val encs = usbDevices.flatMap { it.encodings.toList() }.distinct().sorted()
        val name = first.productName?.toString()?.takeIf { it.isNotBlank() }

        return UsbAudioState(
            connected = true,
            deviceName = name,
            sampleRates = rates,
            encodings = encs,
        )
    }
}
