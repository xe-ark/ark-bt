package com.emre.dynamicbt.bt

data class CodecCapabilityEntry(
    val codecType: Int,
    val codecName: String,
    val rateMask: Int,
    val bitsMask: Int,
    val channelModeMask: Int,
)

data class BtCodecDumpStatus(
    val connected: Boolean,
    val deviceAddress: String?,
    val codecType: Int,
    val codecName: String,
    val sampleRateMask: Int,
    val sampleRateHz: Int,
    val bitsMask: Int,
    val bitsPerSample: Int,
    val selectableRateMask: Int,
    val selectableBitsMask: Int,
    val selectableCapabilities: List<CodecCapabilityEntry> = emptyList(),
)

object CodecCaps {
    // Android BluetoothCodecConfig standard codec types
    const val CODEC_SBC = 0
    const val CODEC_AAC = 1
    const val CODEC_APTX = 2
    const val CODEC_APTX_HD = 3
    const val CODEC_LDAC = 4
    const val CODEC_LC3 = 5
    const val CODEC_OPUS = 6

    // Xiaomi / MediaTek vendor LHDC codec types observed on klee
    const val CODEC_LHDC_V2 = 12
    const val CODEC_LHDC_V3 = 13
    const val CODEC_LHDC_V5 = 15

    // BluetoothCodecConfig sample rate bitmasks
    const val RATE_MASK_NONE = 0x0
    const val RATE_MASK_44100 = 0x1
    const val RATE_MASK_48000 = 0x2
    const val RATE_MASK_88200 = 0x4
    const val RATE_MASK_96000 = 0x8
    const val RATE_MASK_176400 = 0x10
    const val RATE_MASK_192000 = 0x20

    // BluetoothCodecConfig bits per sample bitmasks
    const val BITS_MASK_NONE = 0x0
    const val BITS_MASK_16 = 0x1
    const val BITS_MASK_24 = 0x2
    const val BITS_MASK_32 = 0x4

    private val RATE_TABLE = listOf(
        RATE_MASK_44100 to 44100,
        RATE_MASK_48000 to 48000,
        RATE_MASK_88200 to 88200,
        RATE_MASK_96000 to 96000,
        RATE_MASK_176400 to 176400,
        RATE_MASK_192000 to 192000,
    )

    private val BITS_TABLE = listOf(
        BITS_MASK_16 to 16,
        BITS_MASK_24 to 24,
        BITS_MASK_32 to 32,
    )

    private val DEFAULT_RATE_CANDIDATES = listOf(44100, 48000, 88200, 96000)
    private val DEFAULT_BITS_CANDIDATES = listOf(16, 24, 32)

    private val ACTIVE_HEADER_REGEX =
        Regex("""===\s*A2dpStateMachine for\s+([^\s(]+)\s*\(Active\)\s*===""")
    private val ANY_HEADER_REGEX =
        Regex("""===\s*A2dpStateMachine for\s+([^\s(=]+)(?:\s*\([^)]*\))?\s*===""")
    private val ACTIVE_DEVICE_REGEX =
        Regex("""mActiveDevice:\s*([0-9A-Fa-fXx:]{17})""")
    private val CODEC_BLOCK_REGEX =
        Regex("""\{([^}]*mCodecType:\d+[^}]*)\}""")
    private val FIELD_CODEC_NAME_REGEX = Regex("""codecName:([^,}]+)""")
    private val FIELD_CODEC_TYPE_REGEX = Regex("""mCodecType:(\d+)""")
    private val FIELD_SAMPLE_RATE_REGEX = Regex("""mSampleRate:0x([0-9A-Fa-f]+)(?:\(([^)]*)\))?""")
    private val FIELD_BITS_REGEX = Regex("""mBitsPerSample:0x([0-9A-Fa-f]+)(?:\(([^)]*)\))?""")
    private val FIELD_CHANNEL_REGEX = Regex("""mChannelMode:0x([0-9A-Fa-f]+)""")

    fun codecName(codecType: Int): String = when (codecType) {
        CODEC_SBC -> "SBC"
        CODEC_AAC -> "AAC"
        CODEC_APTX -> "aptX"
        CODEC_APTX_HD -> "aptX HD"
        CODEC_LDAC -> "LDAC"
        CODEC_LC3 -> "LC3"
        CODEC_OPUS -> "Opus"
        CODEC_LHDC_V2 -> "LHDC_V2"
        CODEC_LHDC_V3 -> "LHDC_V3"
        CODEC_LHDC_V5 -> "LHDC V5"
        else -> "UNKNOWN($codecType)"
    }

    fun isDynamicCapable(codecType: Int): Boolean = when (codecType) {
        CODEC_LDAC,
        CODEC_LC3,
        CODEC_LHDC_V2,
        CODEC_LHDC_V3,
        CODEC_LHDC_V5 -> true
        else -> false
    }

    fun rateMaskToHzList(mask: Int): List<Int> =
        RATE_TABLE.mapNotNull { (bit, hz) -> if ((mask and bit) != 0) hz else null }

    fun hzToRateMask(hz: Int): Int =
        RATE_TABLE.firstOrNull { (_, value) -> value == hz }?.first ?: RATE_MASK_NONE

    fun rateMaskToSingleHz(mask: Int): Int {
        val list = rateMaskToHzList(mask)
        return if (list.size == 1) list[0] else 0
    }

    fun bitsMaskToBitsList(mask: Int): List<Int> =
        BITS_TABLE.mapNotNull { (bit, bits) -> if ((mask and bit) != 0) bits else null }

    fun bitsToBitsMask(bits: Int): Int =
        BITS_TABLE.firstOrNull { (_, value) -> value == bits }?.first ?: BITS_MASK_NONE

    fun bitsMaskToSingleBits(mask: Int): Int {
        val list = bitsMaskToBitsList(mask)
        return if (list.size == 1) list[0] else 0
    }

    fun snapSampleRate(requestedHz: Int, selectableRateMask: Int): Int {
        val candidates = rateMaskToHzList(selectableRateMask).ifEmpty { DEFAULT_RATE_CANDIDATES }
        if (requestedHz <= 0) {
            return candidates.firstOrNull { it == 48000 } ?: candidates.min()
        }
        if (requestedHz in candidates) return requestedHz
        return candidates.firstOrNull { it >= requestedHz } ?: candidates.max()
    }

    fun snapBitsPerSample(requestedBits: Int, selectableBitsMask: Int): Int {
        val candidates = bitsMaskToBitsList(selectableBitsMask).ifEmpty { DEFAULT_BITS_CANDIDATES }
        if (requestedBits <= 0) {
            return candidates.firstOrNull { it == 24 } ?: candidates.min()
        }
        if (requestedBits in candidates) return requestedBits
        return candidates.firstOrNull { it >= requestedBits } ?: candidates.max()
    }

    fun parseBtManagerDump(dump: String): BtCodecDumpStatus? {
        if (dump.isBlank()) return null

        val block = extractActiveStateMachineBlock(dump)
        val searchRegion = block ?: dump

        val deviceAddress = block?.let {
            ANY_HEADER_REGEX.find(it)?.groupValues?.getOrNull(1)
        } ?: ACTIVE_DEVICE_REGEX.find(dump)?.groupValues?.getOrNull(1)

        val codecConfigLine = searchRegion.lineSequence()
            .firstOrNull { it.trimStart().startsWith("mCodecConfig:") }
            ?: return null

        val activeEntry = parseCapabilityEntry(codecConfigLine) ?: return null

        val selectableCaps = extractSelectableCapabilities(searchRegion)
        val matchingSelectable = selectableCaps.firstOrNull { it.codecType == activeEntry.codecType }

        val connected = searchRegion.contains("STATE_CONNECTED") ||
            searchRegion.contains("state=Connected")

        val singleHz = rateMaskToSingleHz(activeEntry.rateMask).let { hz ->
            if (hz > 0) hz else parseSingleIntInParens(codecConfigLine, FIELD_SAMPLE_RATE_REGEX)
        }
        val singleBits = bitsMaskToSingleBits(activeEntry.bitsMask).let { bits ->
            if (bits > 0) bits else parseSingleIntInParens(codecConfigLine, FIELD_BITS_REGEX)
        }

        return BtCodecDumpStatus(
            connected = connected,
            deviceAddress = deviceAddress,
            codecType = activeEntry.codecType,
            codecName = activeEntry.codecName.ifBlank { codecName(activeEntry.codecType) },
            sampleRateMask = activeEntry.rateMask,
            sampleRateHz = singleHz,
            bitsMask = activeEntry.bitsMask,
            bitsPerSample = singleBits,
            selectableRateMask = matchingSelectable?.rateMask ?: RATE_MASK_NONE,
            selectableBitsMask = matchingSelectable?.bitsMask ?: BITS_MASK_NONE,
            selectableCapabilities = selectableCaps,
        )
    }

    private fun extractActiveStateMachineBlock(dump: String): String? {
        val activeMatch = ACTIVE_HEADER_REGEX.find(dump)
        if (activeMatch != null) {
            return sliceStateMachineHeader(dump, activeMatch.range.first)
        }

        val activeAddr = ACTIVE_DEVICE_REGEX.find(dump)?.groupValues?.getOrNull(1)
        val headers = ANY_HEADER_REGEX.findAll(dump).toList()
        if (headers.isEmpty()) return null

        if (activeAddr != null && !activeAddr.equals("null", ignoreCase = true)) {
            val byAddr = headers.firstOrNull { it.groupValues[1].equals(activeAddr, ignoreCase = true) }
            if (byAddr != null) {
                return sliceStateMachineHeader(dump, byAddr.range.first)
            }
        }

        for (h in headers) {
            val slice = sliceStateMachineHeader(dump, h.range.first)
            if (slice.contains("STATE_CONNECTED")) return slice
        }
        return sliceStateMachineHeader(dump, headers.first().range.first)
    }

    private fun sliceStateMachineHeader(dump: String, startIndex: Int): String {
        val smLogIdx = dump.indexOf("StateMachine:", startIndex)
        val nextHeaderIdx = dump.indexOf("=== A2dpStateMachine", startIndex + 1)
        val endIndex = listOf(smLogIdx, nextHeaderIdx, dump.length)
            .filter { it > startIndex }
            .minOrNull() ?: dump.length
        return dump.substring(startIndex, endIndex)
    }

    private fun extractSelectableCapabilities(block: String): List<CodecCapabilityEntry> {
        val marker = "mCodecsSelectableCapabilities:"
        val idx = block.indexOf(marker)
        if (idx < 0) return emptyList()

        val afterMarker = block.substring(idx + marker.length)
        val entries = mutableListOf<CodecCapabilityEntry>()
        for (line in afterMarker.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.startsWith("{")) break
            parseCapabilityEntry(trimmed)?.let { entries.add(it) }
        }
        return entries
    }

    private fun parseCapabilityEntry(text: String): CodecCapabilityEntry? {
        val body = CODEC_BLOCK_REGEX.find(text)?.groupValues?.getOrNull(1) ?: return null
        val codecType = FIELD_CODEC_TYPE_REGEX.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val name = FIELD_CODEC_NAME_REGEX.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val rateMask = FIELD_SAMPLE_RATE_REGEX.find(body)?.groupValues?.getOrNull(1)
            ?.toIntOrNull(16) ?: RATE_MASK_NONE
        val bitsMask = FIELD_BITS_REGEX.find(body)?.groupValues?.getOrNull(1)
            ?.toIntOrNull(16) ?: BITS_MASK_NONE
        val chMask = FIELD_CHANNEL_REGEX.find(body)?.groupValues?.getOrNull(1)
            ?.toIntOrNull(16) ?: 0

        return CodecCapabilityEntry(
            codecType = codecType,
            codecName = name.ifBlank { codecName(codecType) },
            rateMask = rateMask,
            bitsMask = bitsMask,
            channelModeMask = chMask,
        )
    }

    private fun parseSingleIntInParens(text: String, regex: Regex): Int {
        val inner = regex.find(text)?.groupValues?.getOrNull(2)?.trim() ?: return 0
        return inner.toIntOrNull() ?: 0
    }
}
