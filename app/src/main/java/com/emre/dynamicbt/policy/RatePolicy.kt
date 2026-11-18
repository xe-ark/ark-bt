package com.emre.dynamicbt.policy

import com.emre.dynamicbt.bt.CodecCaps

data class PolicyConfig(
    val enabled: Boolean = true,
    val defaultRateHz: Int = 48000,
    val defaultBits: Int = 24,
    val debounceMs: Long = 4000L,
    val applyCooldownMs: Long = 5000L,
)

data class ActiveTrackInfo(
    val uid: Int,
    val packageName: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
)

data class SinkCapabilities(
    val connected: Boolean,
    val codecType: Int,
    val currentRateHz: Int,
    val currentBits: Int,
    val selectableRateMask: Int,
    val selectableBitsMask: Int,
)

enum class PolicyMode {
    DISABLED,
    DYNAMIC,
    HOLD,
    DEFAULT,
}

data class PolicyDecision(
    val mode: PolicyMode,
    val targetRateHz: Int,
    val targetBits: Int,
    val shouldApply: Boolean,
    val activeTrack: ActiveTrackInfo?,
    val activeWhitelisted: Boolean,
)

class RatePolicy(
    initialRateHz: Int = 48000,
    initialBits: Int = 24,
) {
    private var whitelistEmptySinceMs: Long? = null
    private var wasDynamic: Boolean = false
    private var lastTargetRateHz: Int = initialRateHz
    private var lastTargetBits: Int = initialBits
    private var hasApplied: Boolean = false
    private var lastApplyMs: Long = 0L

    fun evaluate(
        nowMs: Long,
        config: PolicyConfig,
        whitelist: Set<String>,
        tracks: List<ActiveTrackInfo>,
        sink: SinkCapabilities,
        outputIsBtA2dp: Boolean,
    ): PolicyDecision {
        val whitelistedTrack = tracks.firstOrNull {
            it.packageName.isNotBlank() && it.packageName in whitelist
        }
        val activeTrack = whitelistedTrack ?: tracks.firstOrNull()
        val activeWhitelisted = whitelistedTrack != null

        val mode: PolicyMode
        val rawRateHz: Int
        val rawBits: Int

        if (!config.enabled) {
            whitelistEmptySinceMs = null
            wasDynamic = false
            mode = PolicyMode.DISABLED
            rawRateHz = config.defaultRateHz
            rawBits = config.defaultBits
        } else if (whitelistedTrack != null) {
            whitelistEmptySinceMs = null
            wasDynamic = true
            mode = PolicyMode.DYNAMIC
            rawRateHz = if (whitelistedTrack.sampleRateHz > 0) {
                whitelistedTrack.sampleRateHz
            } else {
                config.defaultRateHz
            }
            rawBits = if (whitelistedTrack.bitsPerSample > 0) {
                whitelistedTrack.bitsPerSample
            } else {
                config.defaultBits
            }
        } else if (wasDynamic && config.debounceMs > 0L) {
            if (whitelistEmptySinceMs == null) {
                whitelistEmptySinceMs = nowMs
            }
            val elapsed = nowMs - (whitelistEmptySinceMs ?: nowMs)
            if (elapsed < config.debounceMs) {
                mode = PolicyMode.HOLD
                rawRateHz = lastTargetRateHz
                rawBits = lastTargetBits
            } else {
                wasDynamic = false
                mode = PolicyMode.DEFAULT
                rawRateHz = config.defaultRateHz
                rawBits = config.defaultBits
            }
        } else {
            wasDynamic = false
            whitelistEmptySinceMs = null
            mode = PolicyMode.DEFAULT
            rawRateHz = config.defaultRateHz
            rawBits = config.defaultBits
        }

        val targetRateHz = CodecCaps.snapSampleRate(rawRateHz, sink.selectableRateMask)
        val targetBits = CodecCaps.snapBitsPerSample(rawBits, sink.selectableBitsMask)
        lastTargetRateHz = targetRateHz
        lastTargetBits = targetBits

        val rateMismatch = sink.currentRateHz != targetRateHz
        val bitsMismatch = sink.currentBits > 0 && sink.currentBits != targetBits
        val cooldownReady = !hasApplied || (nowMs - lastApplyMs >= config.applyCooldownMs)

        val shouldApply = config.enabled &&
            outputIsBtA2dp &&
            sink.connected &&
            CodecCaps.isDynamicCapable(sink.codecType) &&
            (rateMismatch || bitsMismatch) &&
            cooldownReady

        return PolicyDecision(
            mode = mode,
            targetRateHz = targetRateHz,
            targetBits = targetBits,
            shouldApply = shouldApply,
            activeTrack = activeTrack,
            activeWhitelisted = activeWhitelisted,
        )
    }

    fun markApplied(nowMs: Long) {
        hasApplied = true
        lastApplyMs = nowMs
    }

    fun reset() {
        whitelistEmptySinceMs = null
        wasDynamic = false
        hasApplied = false
        lastApplyMs = 0L
    }
}
