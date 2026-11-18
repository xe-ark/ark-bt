package com.emre.dynamicbt.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.SystemClock
import android.util.Log
import com.emre.dynamicbt.policy.ActiveTrackInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ProbedTrack(
    val uid: Int,
    val pid: Int,
    val sessionId: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
)

object TrackFormatProbe {
    private const val TAG = "DynamicBtProbe"

    private val STARTED_STATE_REGEX = Regex("""\bstate:started\b""")
    private val UID_PID_REGEX = Regex("""u/pid:(\d+)/(\d+)""")
    private val SESSION_ID_REGEX = Regex("""sessionId:(\d+)""")
    private val FORMAT_INFO_RATE_REGEX = Regex("""FormatInfo\{[^}]*sampleRate=(\d+)""")

    private val AF_TRACK_ROW_REGEX = Regex(
        """^\s*(?:[A-Za-z0-9_]+\s+)?(\d+)\s+yes\s+(\d+)/\s*(\d+)\s+(\d+)\s+(\d+)\s+[A-Za-z]+\s+0x[0-9A-Fa-f]+\s+(?:0x)?([0-9A-Fa-f]+)\s+[0-9A-Fa-f]+\s+(\d+)"""
    )
    private val AF_TRACKS_HEADER_REGEX = Regex("""^\d+\s+Tracks of which\b""")

    private val uidPackageCache = ConcurrentHashMap<Int, String>()

    fun nativeAudioFormatToBits(format: Int): Int = when (format) {
        0x1, 0x20000001 -> 16
        0x2 -> 8
        0x3, 0x5, 0x20000004 -> 32
        0x4, 0x6, 0x20000002 -> 24
        else -> 0
    }

    fun javaEncodingToBits(encoding: Int): Int = when (encoding) {
        2 -> 16       // AudioFormat.ENCODING_PCM_16BIT
        3 -> 8        // AudioFormat.ENCODING_PCM_8BIT
        4, 22 -> 32   // ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT
        21 -> 24      // ENCODING_PCM_24BIT_PACKED
        else -> 0
    }

    fun parsePlaybackConfigString(text: String): ProbedTrack? {
        if (!STARTED_STATE_REGEX.containsMatchIn(text)) return null
        val uidPidMatch = UID_PID_REGEX.find(text) ?: return null
        val uid = uidPidMatch.groupValues[1].toIntOrNull() ?: return null
        val pid = uidPidMatch.groupValues[2].toIntOrNull() ?: 0
        val sessionId = SESSION_ID_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val sampleRateHz = FORMAT_INFO_RATE_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return ProbedTrack(
            uid = uid,
            pid = pid,
            sessionId = sessionId,
            sampleRateHz = sampleRateHz,
            bitsPerSample = 0,
        )
    }

    fun parseAudioDump(dump: String): List<ProbedTrack> {
        if (dump.isBlank()) return emptyList()

        val hasPlayersSection = dump.lineSequence().any { it.trim() == "players:" }
        var inPlayersSection = !hasPlayersSection
        var seenEntryInPlayers = false
        val parsed = mutableListOf<ProbedTrack>()

        for (rawLine in dump.lineSequence()) {
            val trimmed = rawLine.trim()
            if (hasPlayersSection) {
                if (!inPlayersSection) {
                    if (trimmed == "players:") {
                        inPlayersSection = true
                    }
                    continue
                } else {
                    if (trimmed.startsWith("ducked players") ||
                        (seenEntryInPlayers && trimmed.isEmpty())
                    ) {
                        break
                    }
                }
            }
            if (trimmed.startsWith("AudioPlaybackConfiguration")) {
                seenEntryInPlayers = true
                parsePlaybackConfigString(trimmed)?.let { parsed.add(it) }
            }
        }

        val appTracks = parsed.filter { it.uid >= 10000 }
        return appTracks.ifEmpty { parsed }
    }

    fun parseAudioFlingerDump(dump: String): List<ProbedTrack> {
        if (dump.isBlank()) return emptyList()

        var inLiveOutputThread = false
        var inTracksTable = false
        val parsed = mutableListOf<ProbedTrack>()

        for (rawLine in dump.lineSequence()) {
            // Retired threads start with '-' or '--'
            if (rawLine.startsWith("-")) {
                inLiveOutputThread = false
                inTracksTable = false
                continue
            }

            if (rawLine.startsWith("Output thread ")) {
                inLiveOutputThread = true
                inTracksTable = false
                continue
            }

            if (rawLine.startsWith("Capture thread") ||
                rawLine.startsWith("Input thread") ||
                rawLine.startsWith("AudioFlinger")
            ) {
                inLiveOutputThread = false
                inTracksTable = false
                continue
            }

            if (!inLiveOutputThread) continue

            val trimmed = rawLine.trim()
            if (AF_TRACKS_HEADER_REGEX.containsMatchIn(trimmed)) {
                inTracksTable = true
                continue
            }

            if (!inTracksTable) continue

            if (trimmed.isEmpty() ||
                trimmed.startsWith("WakeLock") ||
                trimmed.startsWith("Effect") ||
                trimmed.startsWith("Local log")
            ) {
                inTracksTable = false
                continue
            }

            if (trimmed.startsWith("Type ") || trimmed.startsWith("Type\t")) {
                continue
            }

            val m = AF_TRACK_ROW_REGEX.find(rawLine) ?: continue
            val pid = m.groupValues[2].toIntOrNull() ?: 0
            val uid = m.groupValues[3].toIntOrNull() ?: continue
            val sessionId = m.groupValues[4].toIntOrNull() ?: 0
            val formatInt = m.groupValues[6].toLongOrNull(16)?.toInt() ?: 0
            val sampleRateHz = m.groupValues[7].toIntOrNull() ?: 0
            val bits = nativeAudioFormatToBits(formatInt)

            parsed.add(
                ProbedTrack(
                    uid = uid,
                    pid = pid,
                    sessionId = sessionId,
                    sampleRateHz = sampleRateHz,
                    bitsPerSample = bits,
                )
            )
        }

        val appTracks = parsed.filter { it.uid >= 10000 }
        return appTracks.ifEmpty { parsed }
    }

    fun probeActiveTracks(
        context: Context,
        audioManager: AudioManager,
        configs: List<AudioPlaybackConfiguration>,
        allowRootFallback: Boolean = true,
    ): List<ActiveTrackInfo> {
        val startedFromApi = configs.mapNotNull { extractFromConfig(it) }

        val needsEnrichment = startedFromApi.any { it.sampleRateHz <= 0 || it.bitsPerSample <= 0 }
        val flingerTracks = if (needsEnrichment || (startedFromApi.isEmpty() && isMusicLikelyPlaying(audioManager))) {
            val afDump = readDumpsys("media.audio_flinger", allowRootFallback)
            parseAudioFlingerDump(afDump)
        } else {
            emptyList()
        }

        val baseTracks = when {
            startedFromApi.isNotEmpty() -> startedFromApi
            flingerTracks.isNotEmpty() -> flingerTracks
            else -> {
                val audioDump = readDumpsys("audio", allowRootFallback)
                parseAudioDump(audioDump)
            }
        }

        val merged = baseTracks.map { track ->
            val match = findMatchingFlingerTrack(track, flingerTracks)
            val rate = if (track.sampleRateHz > 0) track.sampleRateHz else (match?.sampleRateHz ?: 0)
            val bits = if (track.bitsPerSample > 0) track.bitsPerSample else (match?.bitsPerSample ?: 0)
            val pkg = resolvePackageName(context, track.uid, track.pid)
            ActiveTrackInfo(
                uid = track.uid,
                packageName = pkg,
                sampleRateHz = rate,
                bitsPerSample = bits,
            )
        }

        val appTracks = merged.filter { it.uid >= 10000 && it.packageName.isNotBlank() }
        return appTracks.ifEmpty { merged }
    }

    private fun isMusicLikelyPlaying(audioManager: AudioManager): Boolean = try {
        audioManager.isMusicActive
    } catch (_: Exception) {
        false
    }

    private fun extractFromConfig(config: AudioPlaybackConfiguration): ProbedTrack? {
        val text = config.toString()
        val parsed = parsePlaybackConfigString(text)

        val playerState = invokeIntMethod(config, "getPlayerState")
        val isStarted = playerState == 2 || parsed != null
        if (!isStarted) return null

        val uid = invokeIntMethod(config, "getClientUid")?.takeIf { it >= 0 }
            ?: parsed?.uid
            ?: return null
        val pid = invokeIntMethod(config, "getClientPid")?.takeIf { it > 0 }
            ?: parsed?.pid
            ?: 0
        val sessionId = invokeIntMethod(config, "getSessionId")?.takeIf { it > 0 }
            ?: parsed?.sessionId
            ?: 0

        var rate = parsed?.sampleRateHz ?: 0
        var bits = 0
        val formatObj = invokeObjectMethod(config, "getFormat")
            ?: invokeObjectMethod(config, "getAudioFormat")
        if (formatObj is AudioFormat) {
            if (formatObj.sampleRate > 0) {
                rate = formatObj.sampleRate
            }
            val encBits = javaEncodingToBits(formatObj.encoding)
            if (encBits > 0) {
                bits = encBits
            }
        }

        return ProbedTrack(
            uid = uid,
            pid = pid,
            sessionId = sessionId,
            sampleRateHz = rate,
            bitsPerSample = bits,
        )
    }

    private fun findMatchingFlingerTrack(
        track: ProbedTrack,
        flingerTracks: List<ProbedTrack>,
    ): ProbedTrack? {
        if (flingerTracks.isEmpty()) return null
        if (track.sessionId > 0) {
            flingerTracks.firstOrNull { it.sessionId == track.sessionId && it.uid == track.uid }
                ?.let { return it }
        }
        if (track.pid > 0) {
            flingerTracks.firstOrNull { it.uid == track.uid && it.pid == track.pid }
                ?.let { return it }
        }
        return flingerTracks.firstOrNull { it.uid == track.uid }
    }

    private fun resolvePackageName(context: Context, uid: Int, pid: Int): String {
        uidPackageCache[uid]?.let { return it }

        val fromPm = try {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (!fromPm.isNullOrBlank()) {
            uidPackageCache[uid] = fromPm
            return fromPm
        }

        if (pid > 0) {
            val fromProc = readProcCmdline(pid)
            if (fromProc.isNotBlank()) {
                uidPackageCache[uid] = fromProc
                return fromProc
            }
        }
        return ""
    }

    private fun readProcCmdline(pid: Int): String {
        return try {
            val bytes = File("/proc/$pid/cmdline").readBytes()
            val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
            val name = String(bytes, 0, end, Charsets.UTF_8).trim()
            if ('.' in name) name.substringBefore(':') else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun invokeIntMethod(target: Any, name: String): Int? = try {
        val m = target.javaClass.getMethod(name)
        m.isAccessible = true
        (m.invoke(target) as? Number)?.toInt()
    } catch (_: Exception) {
        null
    }

    private fun invokeObjectMethod(target: Any, name: String): Any? = try {
        val m = target.javaClass.getMethod(name)
        m.isAccessible = true
        m.invoke(target)
    } catch (_: Exception) {
        null
    }

    // --- dumpsys plumbing -------------------------------------------------

    private data class CachedDump(val atMs: Long, val text: String)

    private const val DUMP_TTL_MS = 2_500L
    private const val DUMP_TIMEOUT_SECONDS = 8L
    private const val DUMP_MAX_CHARS = 1_500_000

    private val dumpCache = ConcurrentHashMap<String, CachedDump>()

    /**
     * Reads a dumpsys service.
     *
     * An unprivileged app calling `dumpsys` gets a "Permission Denial" body
     * while the process still exits with code 0, which used to be treated as a
     * valid dump and silently disabled every downstream codec check. We now
     * detect that marker and retry through root. Results are cached briefly
     * because spawning `dumpsys` (or `su`) every second is what caused the
     * scroll stutter.
     */
    internal fun readDumpsys(
        service: String,
        allowRootFallback: Boolean,
        bypassCache: Boolean = false,
    ): String {
        val now = SystemClock.elapsedRealtime()
        if (!bypassCache) {
            dumpCache[service]?.let {
                if (now - it.atMs <= DUMP_TTL_MS) return it.text
            }
        }

        val direct = execCommand(arrayOf(DUMPSYS_BIN, service))
        val text = when {
            direct.isBlank() -> {
                if (allowRootFallback) rootDumpsys(service) else ""
            }
            isPermissionDenied(direct) -> {
                if (allowRootFallback) rootDumpsys(service) else ""
            }
            else -> direct
        }

        dumpCache[service] = CachedDump(now, text)
        return text
    }

    /** Invalidates the cache so the next read reflects a just-applied codec. */
    fun invalidateDumpCache(service: String? = null) {
        if (service == null) dumpCache.clear() else dumpCache.remove(service)
    }

    private fun rootDumpsys(service: String): String {
        val rooted = execCommand(arrayOf("su", "-c", "$DUMPSYS_BIN $service"))
        return if (isPermissionDenied(rooted)) "" else rooted
    }

    private fun isPermissionDenied(text: String): Boolean {
        if (text.isBlank()) return false
        val head = text.take(4_096)
        return head.contains("Permission Denial") ||
            head.contains("Permission denial") ||
            head.contains("Security exception") ||
            head.contains("does not have android.permission.DUMP") ||
            (head.contains("android.permission.DUMP") && head.contains("required"))
    }

    /**
     * Runs a command with a hard timeout.
     *
     * Output is redirected to a temp file instead of a pipe: reading a pipe
     * blocks until the child closes it, which would make the timeout useless
     * for a `su` call sitting on a permission prompt.
     */
    private fun execCommand(cmd: Array<String>): String {
        var proc: Process? = null
        var outFile: File? = null
        return try {
            outFile = File.createTempFile("ark-dumpsys", ".txt")
            proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .redirectOutput(outFile)
                .start()
            if (!proc.waitFor(DUMP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                Log.d(TAG, "Command timed out: ${cmd.joinToString(" ")}")
                ""
            } else if (proc.exitValue() != 0 && outFile.length() == 0L) {
                ""
            } else {
                val text = outFile.readText(Charsets.UTF_8)
                if (text.length > DUMP_MAX_CHARS) text.substring(0, DUMP_MAX_CHARS) else text
            }
        } catch (e: Exception) {
            Log.d(TAG, "Command failed (${cmd.joinToString(" ")}): ${e.message}")
            ""
        } finally {
            try {
                proc?.destroy()
            } catch (_: Exception) {
            }
            try {
                outFile?.delete()
            } catch (_: Exception) {
            }
        }
    }

    private const val DUMPSYS_BIN = "/system/bin/dumpsys"
}
