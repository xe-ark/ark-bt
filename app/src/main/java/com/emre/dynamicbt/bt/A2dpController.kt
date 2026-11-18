package com.emre.dynamicbt.bt

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.emre.dynamicbt.audio.TrackFormatProbe
import com.emre.dynamicbt.policy.SinkCapabilities
import java.lang.reflect.InvocationTargetException

data class A2dpStateSnapshot(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val codecType: Int = CodecCaps.CODEC_SBC,
    val codecName: String = "NONE",
    val sampleRateHz: Int = 0,
    val bitsPerSample: Int = 0,
    val selectableRateMask: Int = CodecCaps.RATE_MASK_NONE,
    val selectableBitsMask: Int = CodecCaps.BITS_MASK_NONE,
    val lastError: String? = null,
) {
    fun toSinkCapabilities(): SinkCapabilities = SinkCapabilities(
        connected = connected,
        codecType = codecType,
        currentRateHz = sampleRateHz,
        currentBits = bitsPerSample,
        selectableRateMask = selectableRateMask,
        selectableBitsMask = selectableBitsMask,
    )
}

class A2dpController(
    private val context: Context,
    private val onProfileChanged: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "DynamicBtA2dp"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null

    @Volatile
    private var lastError: String? = null

    private var started = false

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = proxy as? BluetoothA2dp
                Log.i(TAG, "BluetoothA2dp proxy connected")
                onProfileChanged?.invoke()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
                Log.i(TAG, "BluetoothA2dp proxy disconnected")
                onProfileChanged?.invoke()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        try {
            adapter?.getProfileProxy(context, serviceListener, BluetoothProfile.A2DP)
        } catch (e: SecurityException) {
            lastError = "getProfileProxy denied: ${e.message}"
            Log.w(TAG, lastError!!)
        } catch (e: Exception) {
            lastError = "getProfileProxy error: ${e.message}"
            Log.w(TAG, lastError!!)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        val proxy = a2dpProxy
        a2dpProxy = null
        if (proxy != null) {
            try {
                adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            } catch (_: Exception) {
            }
        }
    }

    fun readState(allowRootFallback: Boolean = true): A2dpStateSnapshot {
        val proxy = a2dpProxy
        if (proxy != null) {
            try {
                val device = resolveActiveDevice(proxy)
                if (device == null) {
                    return A2dpStateSnapshot(connected = false, lastError = lastError)
                }
                val codecStatus = getCodecStatus(proxy, device)
                val activeCfg = codecStatus?.codecConfig
                if (activeCfg != null) {
                    val codecType = activeCfg.codecType
                    val selectable = codecStatus.codecsSelectableCapabilities
                        .firstOrNull { it.codecType == codecType }
                    val devName = safeDeviceName(device)
                    val devAddr = safeDeviceAddress(device)
                    return A2dpStateSnapshot(
                        connected = true,
                        deviceName = devName,
                        deviceAddress = devAddr,
                        codecType = codecType,
                        codecName = CodecCaps.codecName(codecType),
                        sampleRateHz = CodecCaps.rateMaskToSingleHz(activeCfg.sampleRate),
                        bitsPerSample = CodecCaps.bitsMaskToSingleBits(activeCfg.bitsPerSample),
                        selectableRateMask = selectable?.sampleRate ?: CodecCaps.RATE_MASK_NONE,
                        selectableBitsMask = selectable?.bitsPerSample ?: CodecCaps.BITS_MASK_NONE,
                        lastError = lastError,
                    )
                }
            } catch (e: Throwable) {
                val cause = unwrapThrowable(e)
                lastError = "${cause.javaClass.simpleName}: ${cause.message}"
                Log.w(TAG, "Proxy readState failed, falling back to dump: $lastError")
            }
        }

        return readStateFromDump(allowRootFallback)
    }

    fun applyCodecConfig(targetRateHz: Int, targetBits: Int): Boolean {
        val proxy = a2dpProxy
        if (proxy == null) {
            lastError = "BluetoothA2dp proxy unavailable"
            Log.w(TAG, lastError!!)
            return false
        }

        val device = try {
            resolveActiveDevice(proxy)
        } catch (e: Throwable) {
            val cause = unwrapThrowable(e)
            lastError = "resolveActiveDevice failed: ${cause.message}"
            Log.w(TAG, lastError!!)
            return false
        }

        if (device == null) {
            lastError = "No active A2DP device"
            Log.w(TAG, lastError!!)
            return false
        }

        val status = try {
            getCodecStatus(proxy, device)
        } catch (e: Throwable) {
            val cause = unwrapThrowable(e)
            lastError = "getCodecStatus denied: ${cause.message}"
            Log.w(TAG, lastError!!)
            return false
        }

        val currentCfg = status?.codecConfig
        if (currentCfg == null) {
            lastError = "Current BluetoothCodecConfig is null"
            Log.w(TAG, lastError!!)
            return false
        }

        val rateMask = CodecCaps.hzToRateMask(targetRateHz)
        val bitsMask = CodecCaps.bitsToBitsMask(targetBits)
        if (rateMask == CodecCaps.RATE_MASK_NONE || bitsMask == CodecCaps.BITS_MASK_NONE) {
            lastError = "Invalid target rate=$targetRateHz or bits=$targetBits"
            Log.w(TAG, lastError!!)
            return false
        }

        val channelMode = currentCfg.channelMode.takeIf { it != 0 }
            ?: BluetoothCodecConfig.CHANNEL_MODE_STEREO

        val builder = BluetoothCodecConfig.Builder()
            .setCodecType(currentCfg.codecType)
            .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
            .setSampleRate(rateMask)
            .setBitsPerSample(bitsMask)
            .setChannelMode(channelMode)
            .setCodecSpecific1(currentCfg.codecSpecific1)
            .setCodecSpecific2(currentCfg.codecSpecific2)
            .setCodecSpecific3(currentCfg.codecSpecific3)
            .setCodecSpecific4(currentCfg.codecSpecific4)

        val newConfig = builder.build()

        return try {
            val method = proxy.javaClass.getMethod(
                "setCodecConfigPreference",
                BluetoothDevice::class.java,
                BluetoothCodecConfig::class.java,
            )
            method.isAccessible = true
            method.invoke(proxy, device, newConfig)
            lastError = null
            Log.i(
                TAG,
                "Applied codec config: codec=${CodecCaps.codecName(currentCfg.codecType)} " +
                    "rate=${targetRateHz}Hz bits=${targetBits} specific1=0x${currentCfg.codecSpecific1.toString(16)}"
            )
            true
        } catch (e: Throwable) {
            val cause = unwrapThrowable(e)
            lastError = "${cause.javaClass.simpleName}: ${cause.message}"
            Log.e(TAG, "setCodecConfigPreference failed: $lastError", cause)
            false
        }
    }

    private fun readStateFromDump(allowRootFallback: Boolean): A2dpStateSnapshot {
        val dump = TrackFormatProbe.readDumpsys("bluetooth_manager", allowRootFallback)
        if (dump.isBlank()) {
            return A2dpStateSnapshot(connected = false, lastError = lastError)
        }
        val parsed = CodecCaps.parseBtManagerDump(dump)
            ?: return A2dpStateSnapshot(connected = false, lastError = lastError)
        return A2dpStateSnapshot(
            connected = parsed.connected,
            deviceName = null,
            deviceAddress = parsed.deviceAddress,
            codecType = parsed.codecType,
            codecName = parsed.codecName,
            sampleRateHz = parsed.sampleRateHz,
            bitsPerSample = parsed.bitsPerSample,
            selectableRateMask = parsed.selectableRateMask,
            selectableBitsMask = parsed.selectableBitsMask,
            lastError = lastError,
        )
    }

    private fun resolveActiveDevice(proxy: BluetoothA2dp): BluetoothDevice? {
        try {
            val m = proxy.javaClass.getMethod("getActiveDevice")
            m.isAccessible = true
            val dev = m.invoke(proxy) as? BluetoothDevice
            if (dev != null) return dev
        } catch (_: NoSuchMethodException) {
        } catch (e: InvocationTargetException) {
            throw unwrapThrowable(e)
        }
        return proxy.connectedDevices.firstOrNull()
    }

    private fun getCodecStatus(proxy: BluetoothA2dp, device: BluetoothDevice): BluetoothCodecStatus? {
        val m = proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
        m.isAccessible = true
        return m.invoke(proxy, device) as? BluetoothCodecStatus
    }

    private fun safeDeviceName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null
    }

    private fun safeDeviceAddress(device: BluetoothDevice): String? = try {
        device.address
    } catch (_: SecurityException) {
        null
    }

    private fun unwrapThrowable(t: Throwable): Throwable =
        if (t is InvocationTargetException && t.cause != null) t.cause!! else t
}
