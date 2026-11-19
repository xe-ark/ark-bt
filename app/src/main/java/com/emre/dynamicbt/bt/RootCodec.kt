package com.emre.dynamicbt.bt

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One codec read/write result coming back from the root bridge.
 *
 * The Android Bluetooth stack only exposes codec read/write to privileged
 * callers, so a normal app cannot change the LDAC/LHDC sample rate. When the
 * device is rooted we run [CodecBridge] through `app_process` as uid 0, which
 * passes the framework permission checks.
 */
data class RootCodecSnapshot(
    val ok: Boolean,
    val applied: Boolean,
    /** True when the bridge saw the requested codec/rate/bits become active. */
    val confirmed: Boolean,
    val connected: Boolean,
    val codecType: Int,
    val codecName: String,
    val rateHz: Int,
    val bits: Int,
    val selectableRateMask: Int,
    val selectableBitsMask: Int,
    val bestCodecType: Int,
    val bestCodecName: String,
    val bestRateMask: Int,
    val bestBitsMask: Int,
    val deviceAddress: String?,
    val deviceName: String?,
    val error: String?,
)

object RootCodec {
    private const val TAG = "DynamicBtRoot"

    private const val ASSET_NAME = "codec-bridge.jar"
    private const val DEX_FILE_NAME = "codec-bridge.jar"
    private const val MAIN_CLASS = "com.emre.dynamicbt.root.CodecBridge"

    private const val SHELL_TIMEOUT_MS = 15_000L
    private const val READ_TTL_MS = 2_500L
    private const val STALE_TTL_MS = 8_000L
    private const val DENIED_RECHECK_MS = 90_000L
    private const val MAX_OUTPUT_CHARS = 64 * 1024

    @Volatile
    private var rootGranted = false

    @Volatile
    private var lastRootCheckMs = 0L

    @Volatile
    private var lastRootCheckResult = false

    @Volatile
    private var dexPath: String? = null

    @Volatile
    private var bridgeError: String? = null

    private var cachedSnapshot: RootCodecSnapshot? = null
    private var cachedAtMs = 0L

    fun lastError(): String? = bridgeError

    /**
     * Cheap, cached root probe. A denied result is only re-tested every
     * [DENIED_RECHECK_MS] so KernelSU is not asked for a prompt every second.
     */
    fun rootAvailable(context: Context, force: Boolean = false): Boolean {
        if (!force && rootGranted) return true
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRootCheckMs < DENIED_RECHECK_MS) {
            return lastRootCheckResult
        }
        lastRootCheckMs = now
        // A forced check is the user tapping "İzin iste", so give them time
        // to answer the KernelSU prompt before giving up.
        val out = runProcess(
            arrayOf("su", "-c", "id"),
            timeoutMs = if (force) 60_000L else 6_000L,
        )
        val granted = out != null && out.contains("uid=0")
        lastRootCheckResult = granted
        rootGranted = granted
        Log.i(TAG, "Root check: ${if (granted) "granted" else "unavailable"}")
        return granted
    }

    @Synchronized
    fun read(context: Context, bypassCache: Boolean = false): RootCodecSnapshot? {
        val now = SystemClock.elapsedRealtime()
        if (!bypassCache) {
            cachedSnapshot?.let { if (now - cachedAtMs <= READ_TTL_MS) return it }
        }
        val snapshot = invokeBridge(context, "read", 0, 0)
        if (snapshot == null) {
            val stale = cachedSnapshot
            return if (stale != null && now - cachedAtMs <= STALE_TTL_MS) stale else null
        }
        cachedSnapshot = snapshot
        cachedAtMs = now
        return snapshot
    }

    @Synchronized
    fun apply(context: Context, rateHz: Int, bits: Int): RootCodecSnapshot? {
        val snapshot = invokeBridge(context, "apply", rateHz, bits)
        if (snapshot != null) {
            cachedSnapshot = snapshot
            cachedAtMs = SystemClock.elapsedRealtime()
        }
        return snapshot
    }

    private fun invokeBridge(
        context: Context,
        action: String,
        rateHz: Int,
        bits: Int,
    ): RootCodecSnapshot? {
        if (!rootAvailable(context)) {
            bridgeError = "root_unavailable"
            return null
        }
        val dex = ensureDex(context)
        if (dex == null) {
            bridgeError = "bridge_asset_missing"
            return null
        }

        val command = buildString {
            append("CLASSPATH='")
            append(dex)
            append("' /system/bin/app_process /system/bin ")
            append(MAIN_CLASS)
            append(' ')
            append(action)
            if (action == "apply") {
                append(' ')
                append(rateHz)
                append(' ')
                append(bits)
            }
        }

        val startedAt = SystemClock.elapsedRealtime()
        val output = runProcess(arrayOf("su", "-c", command), SHELL_TIMEOUT_MS)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (output == null) {
            bridgeError = "bridge_timeout"
            Log.w(TAG, "Bridge $action timed out after ${elapsed}ms")
            return null
        }

        val jsonLine = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") && it.endsWith("}") }
        if (jsonLine == null) {
            bridgeError = "bridge_no_output"
            Log.w(TAG, "Bridge $action produced no JSON (${elapsed}ms): ${output.take(200)}")
            return null
        }

        val snapshot = try {
            parseSnapshot(jsonLine)
        } catch (t: Throwable) {
            bridgeError = "bridge_parse: ${t.message}"
            Log.w(TAG, "Bridge JSON parse failed: ${t.message} ($jsonLine)")
            return null
        }
        bridgeError = snapshot.error
        Log.i(
            TAG,
            "Bridge $action in ${elapsed}ms: ok=${snapshot.ok} applied=${snapshot.applied} " +
                "codec=${snapshot.codecName} ${snapshot.rateHz}Hz/${snapshot.bits}bit " +
                "error=${snapshot.error}",
        )
        return snapshot
    }

    private fun parseSnapshot(json: String): RootCodecSnapshot {
        val obj = JSONObject(json)
        return RootCodecSnapshot(
            ok = obj.optBoolean("ok", false),
            applied = obj.optBoolean("applied", false),
            confirmed = obj.optBoolean("confirmed", true),
            connected = obj.optBoolean("connected", false),
            codecType = obj.optInt("codecType", -1),
            codecName = obj.optString("codec", "").ifBlank {
                CodecCaps.codecName(obj.optInt("codecType", -1))
            },
            rateHz = obj.optInt("rate", 0),
            bits = obj.optInt("bits", 0),
            selectableRateMask = obj.optInt("selRateMask", 0),
            selectableBitsMask = obj.optInt("selBitsMask", 0),
            bestCodecType = obj.optInt("bestCodecType", obj.optInt("codecType", -1)),
            bestCodecName = obj.optString("bestCodec", "").ifBlank {
                CodecCaps.codecName(obj.optInt("codecType", -1))
            },
            bestRateMask = obj.optInt("bestRateMask", obj.optInt("selRateMask", 0)),
            bestBitsMask = obj.optInt("bestBitsMask", obj.optInt("selBitsMask", 0)),
            deviceAddress = obj.optString("device", "").takeIf { it.isNotBlank() },
            deviceName = obj.optString("deviceName", "").takeIf { it.isNotBlank() },
            error = obj.optString("error", "").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /**
     * Copies the bridge dex out of the APK into device-protected storage so it
     * can be read by root even before the user unlocks the phone.
     */
    private fun ensureDex(context: Context): String? {
        val existing = dexPath
        if (existing != null && File(existing).isFile) return existing

        return try {
            val storage = context.createDeviceProtectedStorageContext()
            val dir = storage.filesDir.apply { mkdirs() }
            val target = File(dir, DEX_FILE_NAME)
            // The jar must be refreshed when the APK is updated, otherwise an
            // old bridge (missing new JSON fields) keeps being executed.
            val stamp = File(dir, "$DEX_FILE_NAME.version")
            val version = try {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .lastUpdateTime
                    .toString()
            } catch (t: Throwable) {
                ""
            }
            val upToDate = target.isFile && target.length() > 0L && version.isNotEmpty() &&
                stamp.isFile && stamp.readText(Charsets.UTF_8).trim() == version
            if (!upToDate) {
                val tmp = File(dir, "$DEX_FILE_NAME.tmp")
                context.assets.open(ASSET_NAME).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.setReadable(true, false)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(tmp.readBytes())
                    tmp.delete()
                }
                if (version.isNotEmpty()) {
                    runCatching { stamp.writeText(version, Charsets.UTF_8) }
                }
            }
            target.setReadable(true, false)
            dexPath = target.absolutePath
            target.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "Could not extract $ASSET_NAME: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * Runs a command with a hard timeout, returning stdout+stderr.
     *
     * Output goes to a temp file rather than a pipe: `su` can sit on a
     * KernelSU permission prompt indefinitely, and a blocking pipe read would
     * never reach the timeout check.
     */
    private fun runProcess(cmd: Array<String>, timeoutMs: Long): String? {
        var process: Process? = null
        var outFile: File? = null
        return try {
            outFile = File.createTempFile("ark-bridge", ".out")
            process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .redirectOutput(outFile)
                .start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else {
                val text = outFile.readText(Charsets.UTF_8)
                if (text.length > MAX_OUTPUT_CHARS) text.substring(0, MAX_OUTPUT_CHARS) else text
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Command failed (${cmd.firstOrNull()}): ${t.message}")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
            }
            try {
                outFile?.delete()
            } catch (_: Throwable) {
            }
        }
    }
}
