package com.emre.dynamicbt.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.emre.dynamicbt.audio.PlaybackMonitor
import com.emre.dynamicbt.audio.TrackFormatProbe
import com.emre.dynamicbt.bt.A2dpController
import com.emre.dynamicbt.bt.A2dpStateSnapshot
import com.emre.dynamicbt.bt.RootCodec
import com.emre.dynamicbt.bt.UsbAudioState
import com.emre.dynamicbt.bt.UsbController
import com.emre.dynamicbt.policy.PolicyConfig
import com.emre.dynamicbt.policy.PolicyMode
import com.emre.dynamicbt.policy.RatePolicy
import com.emre.dynamicbt.policy.WhitelistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

data class ServiceStatus(
    val running: Boolean = false,
    val enabled: Boolean = true,
    val mode: PolicyMode = PolicyMode.DEFAULT,
    val activeApp: String? = null,
    val activeWhitelisted: Boolean = false,
    val activeTrackRateHz: Int? = null,
    val activeTrackBits: Int? = null,
    val targetRateHz: Int = 48000,
    val targetBits: Int = 24,
    val outputBtA2dp: Boolean = false,
    val a2dp: A2dpStateSnapshot = A2dpStateSnapshot(),
    val usb: UsbAudioState = UsbAudioState(),
    val config: PolicyConfig = PolicyConfig(),
    val whitelist: Set<String> = emptySet(),
    val lastApplyMs: Long = 0L,
    val lastApplySuccess: Boolean? = null,
    val lastError: String? = null,
    val rootAvailable: Boolean = false,
    val codecWriteSource: String = "none",
    val updatedAtMs: Long = 0L,
)

object DynamicBtServiceState {
    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeService: DynamicBtService? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            val cfg = WhitelistStore.loadConfig(context)
            val wl = WhitelistStore.loadWhitelist(context)
            _status.value = _status.value.copy(
                enabled = cfg.enabled,
                config = cfg,
                whitelist = wl,
            )
        }
    }

    internal fun attachService(service: DynamicBtService) {
        init(service)
        activeService = service
    }

    internal fun detachService(service: DynamicBtService) {
        if (activeService === service) {
            activeService = null
        }
        _status.value = _status.value.copy(running = false)
    }

    internal fun updateStatus(newStatus: ServiceStatus) {
        _status.value = newStatus
    }

    fun reloadConfig() {
        val ctx = appContext ?: return
        val cfg = WhitelistStore.loadConfig(ctx)
        val wl = WhitelistStore.loadWhitelist(ctx)
        _status.value = _status.value.copy(
            enabled = cfg.enabled,
            config = cfg,
            whitelist = wl,
        )
        activeService?.onStoreChanged()
    }

    fun setEnabled(enabled: Boolean) {
        updateConfig { it.copy(enabled = enabled) }
    }

    fun setDebounceMs(debounceMs: Long) {
        updateConfig { it.copy(debounceMs = debounceMs.coerceAtLeast(0L)) }
    }

    fun updateConfig(transform: (PolicyConfig) -> PolicyConfig) {
        val ctx = appContext ?: return
        val updated = transform(WhitelistStore.loadConfig(ctx))
        WhitelistStore.saveConfig(ctx, updated)
        _status.value = _status.value.copy(
            enabled = updated.enabled,
            config = updated,
        )
        activeService?.onStoreChanged()
    }

    /**
     * Kicks off the KernelSU/Magisk permission prompt. `su` blocks until the
     * user answers, so this must never run on the main thread.
     */
    fun requestRootAccess() {
        val ctx = appContext ?: return
        val service = activeService
        Thread({
            val granted = RootCodec.rootAvailable(ctx, force = true)
            if (granted) {
                TrackFormatProbe.invalidateDumpCache()
                service?.onStoreChanged()
            }
        }, "ArkRootRequest").apply { isDaemon = true }.start()
    }

    fun updateWhitelist(packages: Set<String>) {
        val ctx = appContext ?: return
        WhitelistStore.saveWhitelist(ctx, packages)
        val loaded = WhitelistStore.loadWhitelist(ctx)
        _status.value = _status.value.copy(whitelist = loaded)
        activeService?.onStoreChanged()
    }
}

class DynamicBtService : Service() {
    companion object {
        private const val TAG = "DynamicBtService"
        private const val TICK_INTERVAL_MS = 1200L
        private const val STATUS_FILE_NAME = "status.json"
        private const val CHANNEL_ID = "dynamic_bt_bg"
        private const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val triggerChannel = Channel<Unit>(Channel.CONFLATED)

    private lateinit var playbackMonitor: PlaybackMonitor
    private lateinit var a2dpController: A2dpController
    private lateinit var usbController: UsbController
    private lateinit var ratePolicy: RatePolicy

    @Volatile
    private var currentConfig: PolicyConfig = PolicyConfig()

    @Volatile
    private var currentWhitelist: Set<String> = emptySet()

    private var hasFailedApply = false
    private var lastFailedApplyMs = 0L
    private var lastApplyWallMs = 0L
    private var lastApplySuccess: Boolean? = null
    private var lastWriteSource = "none"
    private var lastWrittenSignature: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DynamicBtServiceState.attachService(this)
        promoteToForeground()

        currentConfig = WhitelistStore.loadConfig(this)
        currentWhitelist = WhitelistStore.loadWhitelist(this)
        ratePolicy = RatePolicy(
            initialRateHz = currentConfig.defaultRateHz,
            initialBits = currentConfig.defaultBits,
        )

        playbackMonitor = PlaybackMonitor(this) { requestEvaluation() }
        a2dpController = A2dpController(this) { requestEvaluation() }
        usbController = UsbController(this)

        a2dpController.start()
        playbackMonitor.start()

        scope.launch {
            runLoop()
        }
        Log.i(TAG, "DynamicBtService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestEvaluation()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "DynamicBtService stopping")
        playbackMonitor.stop()
        a2dpController.stop()
        scope.cancel()
        triggerChannel.close()
        DynamicBtServiceState.detachService(this)
        super.onDestroy()
    }

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Dynamic BT Service",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("Ark Bt")
            .setContentText("Xe.Ark ses örnekleme izleniyor")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Foreground promotion failed (${t.javaClass.simpleName}): ${t.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (t2: Throwable) {
                Log.w(TAG, "Fallback startForeground failed: ${t2.message}")
            }
        }
    }

    internal fun onStoreChanged() {
        currentConfig = WhitelistStore.loadConfig(this)
        currentWhitelist = WhitelistStore.loadWhitelist(this)
        requestEvaluation()
    }

    private fun requestEvaluation() {
        triggerChannel.trySend(Unit)
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                evaluateOnce()
            } catch (t: Throwable) {
                Log.e(TAG, "Evaluation cycle error", t)
            }
            withTimeoutOrNull(TICK_INTERVAL_MS) {
                triggerChannel.receive()
            }
        }
    }

    private fun evaluateOnce() {
        val cfg = currentConfig
        val wl = currentWhitelist
        val nowMonoMs = SystemClock.elapsedRealtime()

        // Root is the only path that can read or write the A2DP codec config
        // (BLUETOOTH_PRIVILEGED is not grantable to third-party apps).
        val rootReady = RootCodec.rootAvailable(this)
        val rootSnapshot = if (rootReady) RootCodec.read(this) else null

        val tracks = playbackMonitor.queryActiveTracks(allowRootFallback = rootReady)
        val outBtA2dp = playbackMonitor.isBluetoothA2dpOutputActive()
        val a2dp = when {
            rootSnapshot == null -> a2dpController.readState(allowRootFallback = rootReady)
            // Root answered but no sink is active: trust it, instead of
            // falling back to an app-level read that would just fail.
            !rootSnapshot.connected -> a2dpController.snapshotFromRoot(rootSnapshot)
            rootSnapshot.ok -> a2dpController.snapshotFromRoot(rootSnapshot)
            else -> a2dpController.readState(allowRootFallback = rootReady)
        }
        val usb = usbController.readState()

        val decision = ratePolicy.evaluate(
            nowMs = nowMonoMs,
            config = cfg,
            whitelist = wl,
            tracks = tracks,
            sink = a2dp.toSinkCapabilities(),
            outputIsBtA2dp = outBtA2dp,
        )

        var postApplyA2dp: A2dpStateSnapshot? = null

        val failCooldownReady = !hasFailedApply ||
            (nowMonoMs - lastFailedApplyMs >= cfg.applyCooldownMs)
        if (decision.shouldApply && failCooldownReady) {
            val rootResult = if (rootReady) {
                RootCodec.apply(this, decision.targetRateHz, decision.targetBits)
            } else {
                null
            }

            val ok = when {
                rootResult != null -> {
                    lastWriteSource = "root"
                    if (rootResult.ok && rootResult.applied) {
                        postApplyA2dp = a2dpController.snapshotFromRoot(rootResult)
                        true
                    } else {
                        a2dpController.noteError(
                            rootResult.error ?: "Köprü kodek yazımını doğrulayamadı",
                        )
                        false
                    }
                }

                else -> {
                    lastWriteSource = "api"
                    a2dpController.applyCodecConfig(decision.targetRateHz, decision.targetBits)
                }
            }

            lastApplyWallMs = System.currentTimeMillis()
            lastApplySuccess = ok
            if (ok) {
                hasFailedApply = false
                ratePolicy.markApplied(nowMonoMs)
                TrackFormatProbe.invalidateDumpCache("bluetooth_manager")
            } else {
                hasFailedApply = true
                lastFailedApplyMs = nowMonoMs
            }
        }

        val updatedA2dp = postApplyA2dp ?: if (decision.shouldApply) {
            a2dpController.readState(allowRootFallback = rootReady)
        } else {
            a2dp
        }

        val status = ServiceStatus(
            running = true,
            enabled = cfg.enabled,
            mode = decision.mode,
            activeApp = decision.activeTrack?.packageName?.takeIf { it.isNotBlank() },
            activeWhitelisted = decision.activeWhitelisted,
            activeTrackRateHz = decision.activeTrack?.sampleRateHz?.takeIf { it > 0 },
            activeTrackBits = decision.activeTrack?.bitsPerSample?.takeIf { it > 0 },
            targetRateHz = decision.targetRateHz,
            targetBits = decision.targetBits,
            outputBtA2dp = outBtA2dp,
            a2dp = updatedA2dp,
            usb = usb,
            config = cfg,
            whitelist = wl,
            lastApplyMs = lastApplyWallMs,
            lastApplySuccess = lastApplySuccess,
            lastError = updatedA2dp.lastError
                ?: RootCodec.lastError()?.takeIf { it != "root_unavailable" },
            rootAvailable = rootReady,
            codecWriteSource = lastWriteSource,
            updatedAtMs = System.currentTimeMillis(),
        )
        publishStatusIfChanged(status)
    }

    private fun publishStatusIfChanged(status: ServiceStatus) {
        val signature = listOf(
            status.running,
            status.enabled,
            status.mode,
            status.activeApp,
            status.activeWhitelisted,
            status.activeTrackRateHz,
            status.activeTrackBits,
            status.targetRateHz,
            status.targetBits,
            status.outputBtA2dp,
            status.a2dp,
            status.usb,
            status.config,
            status.whitelist,
            status.lastApplyMs,
            status.lastApplySuccess,
            status.lastError,
            status.rootAvailable,
            status.codecWriteSource,
        ).joinToString("|")

        if (signature == lastWrittenSignature) return
        lastWrittenSignature = signature
        DynamicBtServiceState.updateStatus(status)

        val json = JSONObject().apply {
            put("running", status.running)
            put("enabled", status.enabled)
            put("mode", status.mode.name.lowercase())
            put("whitelist_count", status.whitelist.size)
            put("active_app", status.activeApp ?: JSONObject.NULL)
            put("active_whitelisted", status.activeWhitelisted)
            put("active_rate", status.activeTrackRateHz ?: JSONObject.NULL)
            put("active_bits", status.activeTrackBits ?: JSONObject.NULL)
            put("target_rate", status.targetRateHz)
            put("target_bits", status.targetBits)
            put("output_bt", status.outputBtA2dp)
            put("bt_connected", status.a2dp.connected)
            put("bt_device", status.a2dp.deviceName ?: status.a2dp.deviceAddress ?: JSONObject.NULL)
            put("bt_address", status.a2dp.deviceAddress ?: JSONObject.NULL)
            put("bt_codec", status.a2dp.codecName)
            put("bt_codec_type", status.a2dp.codecType)
            put("bt_rate", status.a2dp.sampleRateHz)
            put("bt_bits", status.a2dp.bitsPerSample)
            put("usb_connected", status.usb.connected)
            put("usb_device", status.usb.deviceName ?: JSONObject.NULL)
            put("last_apply_ms", status.lastApplyMs)
            put("last_apply_success", status.lastApplySuccess ?: JSONObject.NULL)
            put("last_error", status.lastError ?: JSONObject.NULL)
            put("root_available", status.rootAvailable)
            put("codec_write_source", status.codecWriteSource)
            put("now_ms", status.updatedAtMs)
        }.toString()

        try {
            val dir = createDeviceProtectedStorageContext().filesDir.apply { mkdirs() }
            val target = File(dir, STATUS_FILE_NAME)
            val tmp = File(dir, "$STATUS_FILE_NAME.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed writing status.json: ${e.message}")
        }
    }
}
