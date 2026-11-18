package com.emre.dynamicbt.tests

import com.emre.dynamicbt.audio.TrackFormatProbe
import com.emre.dynamicbt.bt.CodecCaps
import com.emre.dynamicbt.policy.ActiveTrackInfo
import com.emre.dynamicbt.policy.PolicyConfig
import com.emre.dynamicbt.policy.PolicyMode
import com.emre.dynamicbt.policy.RatePolicy
import com.emre.dynamicbt.policy.SinkCapabilities
import com.emre.dynamicbt.policy.WhitelistStore
import java.io.File

private fun check(condition: Boolean, message: String) {
    if (!condition) {
        throw AssertionError("ASSERTION FAILED: $message")
    }
}

private fun <T> checkEquals(expected: T, actual: T, label: String) {
    if (expected != actual) {
        throw AssertionError("ASSERTION FAILED [$label]: expected=<$expected> actual=<$actual>")
    }
}

private fun testCodecCapsBitmasks() {
    checkEquals("LDAC", CodecCaps.codecName(CodecCaps.CODEC_LDAC), "codecName LDAC")
    checkEquals("LC3", CodecCaps.codecName(CodecCaps.CODEC_LC3), "codecName LC3")
    checkEquals("LHDC V5", CodecCaps.codecName(CodecCaps.CODEC_LHDC_V5), "codecName LHDC V5")
    checkEquals("SBC", CodecCaps.codecName(CodecCaps.CODEC_SBC), "codecName SBC")
    checkEquals("AAC", CodecCaps.codecName(CodecCaps.CODEC_AAC), "codecName AAC")

    check(CodecCaps.isDynamicCapable(CodecCaps.CODEC_LDAC), "LDAC is dynamic")
    check(CodecCaps.isDynamicCapable(CodecCaps.CODEC_LC3), "LC3 is dynamic")
    check(CodecCaps.isDynamicCapable(CodecCaps.CODEC_LHDC_V2), "LHDC_V2 is dynamic")
    check(CodecCaps.isDynamicCapable(CodecCaps.CODEC_LHDC_V3), "LHDC_V3 is dynamic")
    check(CodecCaps.isDynamicCapable(CodecCaps.CODEC_LHDC_V5), "LHDC_V5 is dynamic")
    check(!CodecCaps.isDynamicCapable(CodecCaps.CODEC_SBC), "SBC not dynamic")
    check(!CodecCaps.isDynamicCapable(CodecCaps.CODEC_AAC), "AAC not dynamic")

    // Rate conversions including LHDC V5 192k (0x2b = 44100|48000|96000|192000)
    checkEquals(
        listOf(44100, 48000, 96000, 192000),
        CodecCaps.rateMaskToHzList(0x2b),
        "rateMaskToHzList(0x2b)"
    )
    checkEquals(0x20, CodecCaps.hzToRateMask(192000), "hzToRateMask(192000)")
    checkEquals(0x8, CodecCaps.hzToRateMask(96000), "hzToRateMask(96000)")
    checkEquals(192000, CodecCaps.rateMaskToSingleHz(0x20), "rateMaskToSingleHz(0x20)")
    checkEquals(0, CodecCaps.rateMaskToSingleHz(0xb), "rateMaskToSingleHz(0xb) multi-bit")

    // Bits conversions
    checkEquals(listOf(16, 24, 32), CodecCaps.bitsMaskToBitsList(0x7), "bitsMaskToBitsList(0x7)")
    checkEquals(0x2, CodecCaps.bitsToBitsMask(24), "bitsToBitsMask(24)")
    checkEquals(32, CodecCaps.bitsMaskToSingleBits(0x4), "bitsMaskToSingleBits(0x4)")
    checkEquals(0, CodecCaps.bitsMaskToSingleBits(0x7), "bitsMaskToSingleBits(0x7) multi-bit")
}

private fun testSnappingAgainstSoundpeatsSpacePro() {
    val ldacMask = 0xb // 44100 | 48000 | 96000 (no 88200)
    checkEquals(44100, CodecCaps.snapSampleRate(44100, ldacMask), "snap 44100")
    checkEquals(48000, CodecCaps.snapSampleRate(48000, ldacMask), "snap 48000")
    checkEquals(96000, CodecCaps.snapSampleRate(88200, ldacMask), "snap 88200 -> 96000")
    checkEquals(96000, CodecCaps.snapSampleRate(96000, ldacMask), "snap 96000")
    checkEquals(96000, CodecCaps.snapSampleRate(192000, ldacMask), "snap 192000 -> 96000")

    // Bits snapping
    checkEquals(24, CodecCaps.snapBitsPerSample(24, 0x7), "snap 24 in 0x7")
    checkEquals(24, CodecCaps.snapBitsPerSample(32, 0x3), "snap 32 in 0x3 (LHDC max 24)")
    checkEquals(32, CodecCaps.snapBitsPerSample(24, 0x5), "snap 24 in 0x5 -> 32")
}

private fun testRealDeviceDumpParsing(dumpPath: String) {
    val file = File(dumpPath)
    check(file.exists(), "Dump file exists: $dumpPath")
    val parsed = CodecCaps.parseBtManagerDump(file.readText(Charsets.UTF_8))
    check(parsed != null, "parseBtManagerDump returned non-null")
    requireNotNull(parsed)

    checkEquals(true, parsed.connected, "dump connected")
    checkEquals("XX:XX:XX:XX:9E:CB", parsed.deviceAddress, "dump deviceAddress")
    checkEquals(CodecCaps.CODEC_LDAC, parsed.codecType, "dump codecType == 4 (LDAC)")
    checkEquals(96000, parsed.sampleRateHz, "dump sampleRateHz == 96000")
    checkEquals(32, parsed.bitsPerSample, "dump bitsPerSample == 32")
    checkEquals(0xb, parsed.selectableRateMask, "dump selectableRateMask == 0xb")
    checkEquals(0x7, parsed.selectableBitsMask, "dump selectableBitsMask == 0x7")
}

private fun testRatePolicyTransitions() {
    val policy = RatePolicy()
    val config = PolicyConfig(
        enabled = true,
        defaultRateHz = 48000,
        defaultBits = 24,
        debounceMs = 4000L,
        applyCooldownMs = 5000L,
    )
    val whitelist = setOf("com.maxmpz.audioplayer", "com.apple.android.music")

    val sink96k32 = SinkCapabilities(
        connected = true,
        codecType = CodecCaps.CODEC_LDAC,
        currentRateHz = 96000,
        currentBits = 32,
        selectableRateMask = 0xb,
        selectableBitsMask = 0x7,
    )

    // 1. Cold start: no whitelisted track -> DEFAULT 48000/24, triggers switch from 96000/32
    val d0 = policy.evaluate(0L, config, whitelist, emptyList(), sink96k32, outputIsBtA2dp = true)
    checkEquals(PolicyMode.DEFAULT, d0.mode, "initial mode DEFAULT")
    checkEquals(48000, d0.targetRateHz, "initial targetRateHz")
    checkEquals(24, d0.targetBits, "initial targetBits")
    checkEquals(true, d0.shouldApply, "initial shouldApply")
    policy.markApplied(0L)

    // After switching to 48000/24, no more apply needed
    val sink48k24 = sink96k32.copy(currentRateHz = 48000, currentBits = 24)
    val d1 = policy.evaluate(1000L, config, whitelist, emptyList(), sink48k24, outputIsBtA2dp = true)
    checkEquals(PolicyMode.DEFAULT, d1.mode, "settled mode DEFAULT")
    checkEquals(false, d1.shouldApply, "no apply when already at target")

    // 2. Whitelist track 96000/24 arrives during cooldown (t=2000ms) -> target DYNAMIC 96k/24, suppressed by cooldown
    val poweramp96 = ActiveTrackInfo(10282, "com.maxmpz.audioplayer", 96000, 24)
    val d2 = policy.evaluate(2000L, config, whitelist, listOf(poweramp96), sink48k24, outputIsBtA2dp = true)
    checkEquals(PolicyMode.DYNAMIC, d2.mode, "whitelisted mode DYNAMIC")
    checkEquals(96000, d2.targetRateHz, "whitelisted targetRateHz")
    checkEquals(24, d2.targetBits, "whitelisted targetBits")
    checkEquals(true, d2.activeWhitelisted, "activeWhitelisted flag")
    checkEquals(false, d2.shouldApply, "cooldown suppresses within 5000ms")

    // At t=5000ms cooldown elapsed -> shouldApply == true
    val d3 = policy.evaluate(5000L, config, whitelist, listOf(poweramp96), sink48k24, outputIsBtA2dp = true)
    checkEquals(PolicyMode.DYNAMIC, d3.mode, "DYNAMIC at t=5000")
    checkEquals(true, d3.shouldApply, "shouldApply after 5000ms cooldown")
    policy.markApplied(5000L)

    // 3. Track stops at t=6000ms -> HOLD 96000/24 until t=10000ms
    val sink96k24 = sink48k24.copy(currentRateHz = 96000, currentBits = 24)
    val d4 = policy.evaluate(6000L, config, whitelist, emptyList(), sink96k24, outputIsBtA2dp = true)
    checkEquals(PolicyMode.HOLD, d4.mode, "mode HOLD at stop")
    checkEquals(96000, d4.targetRateHz, "HOLD retains 96000")
    checkEquals(24, d4.targetBits, "HOLD retains 24")
    checkEquals(false, d4.shouldApply, "HOLD does not apply")

    // Still HOLD at 9999ms (elapsed = 3999ms < 4000ms)
    val d5 = policy.evaluate(9999L, config, whitelist, emptyList(), sink96k24, outputIsBtA2dp = true)
    checkEquals(PolicyMode.HOLD, d5.mode, "mode HOLD at 3999ms")
    checkEquals(96000, d5.targetRateHz, "HOLD at 3999ms retains 96000")

    // At 10000ms (elapsed = 4000ms >= 4000ms) -> transitions to DEFAULT 48000/24, cooldown (5000->10000) also elapsed!
    val d6 = policy.evaluate(10000L, config, whitelist, emptyList(), sink96k24, outputIsBtA2dp = true)
    checkEquals(PolicyMode.DEFAULT, d6.mode, "mode DEFAULT at 4000ms")
    checkEquals(48000, d6.targetRateHz, "DEFAULT returns to 48000")
    checkEquals(24, d6.targetBits, "DEFAULT returns to 24")
    checkEquals(true, d6.shouldApply, "shouldApply switches back to 48000/24")
    policy.markApplied(10000L)

    // Check cooldown suppression directly at 14999ms when whitelisted 88.2k (snapped to 96k) starts
    val poweramp88 = ActiveTrackInfo(10282, "com.apple.android.music", 88200, 24)
    val d7 = policy.evaluate(14999L, config, whitelist, listOf(poweramp88), sink48k24, outputIsBtA2dp = true)
    checkEquals(96000, d7.targetRateHz, "88200 snaps to 96000")
    checkEquals(false, d7.shouldApply, "suppressed at 14999ms (4999ms < 5000ms)")

    val d8 = policy.evaluate(15000L, config, whitelist, listOf(poweramp88), sink48k24, outputIsBtA2dp = true)
    checkEquals(true, d8.shouldApply, "allowed at 15000ms (5000ms >= 5000ms)")

    // Non-dynamic codec (SBC) & non-BT output never trigger apply
    val sbcSink = sink48k24.copy(codecType = CodecCaps.CODEC_SBC)
    val dSbc = policy.evaluate(25000L, config, whitelist, listOf(poweramp96), sbcSink, outputIsBtA2dp = true)
    checkEquals(false, dSbc.shouldApply, "SBC never applies")

    val dNoBt = policy.evaluate(25000L, config, whitelist, listOf(poweramp96), sink48k24, outputIsBtA2dp = false)
    checkEquals(false, dNoBt.shouldApply, "non-BT output never applies")
}

private fun testWhitelistStoreRoundTrip() {
    val rawWhitelist = """
        # Comment line
        com.maxmpz.audioplayer
        
        com.apple.android.music # inline comment
        com.aspiro.tidal
    """.trimIndent()
    val parsedWl = WhitelistStore.parseWhitelist(rawWhitelist)
    checkEquals(
        setOf("com.maxmpz.audioplayer", "com.apple.android.music", "com.aspiro.tidal"),
        parsedWl,
        "parseWhitelist"
    )
    val formattedWl = WhitelistStore.formatWhitelist(parsedWl)
    checkEquals(parsedWl, WhitelistStore.parseWhitelist(formattedWl), "whitelist round-trip")
    check("com.apple.android.music" in WhitelistStore.DEFAULT_PACKAGES, "default packages includes Apple Music")

    val cfg = PolicyConfig(
        enabled = false,
        defaultRateHz = 44100,
        defaultBits = 16,
        debounceMs = 6500L,
        applyCooldownMs = 3000L,
    )
    val formattedCfg = WhitelistStore.formatConfig(cfg)
    val parsedCfg = WhitelistStore.parseConfig(formattedCfg)
    checkEquals(cfg, parsedCfg, "config round-trip")
}

private fun testTrackFormatProbeRealDumps(dumpDir: String) {
    checkEquals(16, TrackFormatProbe.nativeAudioFormatToBits(0x1), "native 0x1 -> 16")
    checkEquals(24, TrackFormatProbe.nativeAudioFormatToBits(0x6), "native 0x6 -> 24")
    checkEquals(32, TrackFormatProbe.nativeAudioFormatToBits(0x3), "native 0x3 -> 32")
    checkEquals(32, TrackFormatProbe.nativeAudioFormatToBits(0x5), "native 0x5 -> 32")
    checkEquals(16, TrackFormatProbe.javaEncodingToBits(2), "java encoding 2 -> 16")
    checkEquals(24, TrackFormatProbe.javaEncodingToBits(21), "java encoding 21 -> 24")
    checkEquals(32, TrackFormatProbe.javaEncodingToBits(22), "java encoding 22 -> 32")

    val afFile = File(dumpDir, "audio_flinger.txt")
    check(afFile.exists(), "audio_flinger.txt exists")
    val afTracks = TrackFormatProbe.parseAudioFlingerDump(afFile.readText(Charsets.UTF_8))
    checkEquals(1, afTracks.size, "audio_flinger active track count")
    checkEquals(10282, afTracks[0].uid, "audio_flinger uid")
    checkEquals(1554, afTracks[0].pid, "audio_flinger pid")
    checkEquals(1729, afTracks[0].sessionId, "audio_flinger sessionId")
    checkEquals(48000, afTracks[0].sampleRateHz, "audio_flinger sampleRateHz")
    checkEquals(16, afTracks[0].bitsPerSample, "audio_flinger bitsPerSample")

    val audioFile = File(dumpDir, "audio.txt")
    check(audioFile.exists(), "audio.txt exists")
    val audioTracks = TrackFormatProbe.parseAudioDump(audioFile.readText(Charsets.UTF_8))
    checkEquals(1, audioTracks.size, "audio.txt started track count")
    checkEquals(10282, audioTracks[0].uid, "audio.txt uid")
    checkEquals(1554, audioTracks[0].pid, "audio.txt pid")
    checkEquals(1729, audioTracks[0].sessionId, "audio.txt sessionId")
    checkEquals(48000, audioTracks[0].sampleRateHz, "audio.txt sampleRateHz")

    // Idle line should be ignored
    val idle = TrackFormatProbe.parsePlaybackConfigString(
        "AudioPlaybackConfiguration piid:71 type:android.media.SoundPool u/pid:10222/3062 state:idle sessionId:0 FormatInfo{sampleRate=48000}"
    )
    checkEquals(null, idle, "idle player ignored")
}

fun main(args: Array<String>) {
    val dumpPath = args.firstOrNull()
        ?: "/home/emre/.gemini/antigravity/scratch/dynamic_bt_rom/docs/device_dumps/bt_manager.txt"
    val dumpDir = File(dumpPath).parent ?: "/home/emre/.gemini/antigravity/scratch/dynamic_bt_rom/docs/device_dumps"

    testCodecCapsBitmasks()
    println("PASS: testCodecCapsBitmasks")

    testSnappingAgainstSoundpeatsSpacePro()
    println("PASS: testSnappingAgainstSoundpeatsSpacePro")

    testRealDeviceDumpParsing(dumpPath)
    println("PASS: testRealDeviceDumpParsing")

    testRatePolicyTransitions()
    println("PASS: testRatePolicyTransitions")

    testWhitelistStoreRoundTrip()
    println("PASS: testWhitelistStoreRoundTrip")

    testTrackFormatProbeRealDumps(dumpDir)
    println("PASS: testTrackFormatProbeRealDumps")

    println("ALL PHASE 1 + PHASE 2 UNIT TESTS PASSED")
}
