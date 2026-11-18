package com.emre.dynamicbt.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.emre.dynamicbt.bt.CodecCaps
import com.emre.dynamicbt.core.DynamicBtService
import com.emre.dynamicbt.core.DynamicBtServiceState
import com.emre.dynamicbt.core.ServiceStatus
import com.emre.dynamicbt.policy.PolicyMode
import com.emre.dynamicbt.policy.WhitelistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

private data class AppEntry(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val icon: ImageBitmap?,
)

private val appEntryCache = ConcurrentHashMap<String, AppEntry>()

private val KNOWN_DEFAULT_LABELS = mapOf(
    "com.maxmpz.audioplayer" to "Poweramp",
    "com.maxmpz.audioplayer.lite" to "Poweramp Unlocker / Lite",
    "com.aspiro.tidal" to "TIDAL",
    "com.apple.android.music" to "Apple Music",
    "com.frybits.symfonium" to "Symfonium",
    "com.extreamsd.usbaudioplayerpro" to "USB Audio Player PRO",
    "org.foobar2000.foobar2000" to "foobar2000",
)

class MainActivity : ComponentActivity() {
    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startDynamicBtService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        DynamicBtServiceState.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        startDynamicBtService()

        setContent {
            ArkBtTheme(darkTheme = true) {
                ArkBtScreen(
                    onToggleService = { shouldRun ->
                        if (shouldRun) {
                            startDynamicBtService()
                        } else {
                            stopService(Intent(this, DynamicBtService::class.java))
                        }
                    }
                )
            }
        }
        enableHighRefreshRate()
    }

    override fun onResume() {
        super.onResume()
        enableHighRefreshRate()
    }

    private fun enableHighRefreshRate() {
        try {
            val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            if (d != null) {
                val currentMode = d.mode
                val bestMode = d.supportedModes
                    .filter {
                        it.physicalWidth == currentMode.physicalWidth &&
                            it.physicalHeight == currentMode.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate }
                val lp = window.attributes
                if (bestMode != null) {
                    lp.preferredDisplayModeId = bestMode.modeId
                    lp.preferredRefreshRate = bestMode.refreshRate
                } else {
                    lp.preferredRefreshRate = 120f
                }
                window.attributes = lp
            }
            if (Build.VERSION.SDK_INT >= 35) {
                window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            }
        } catch (_: Throwable) {
        }
    }

    private fun startDynamicBtService() {
        val intent = Intent(this, DynamicBtService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            try {
                startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
private fun ArkBtScreen(
    onToggleService: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val c = LocalArkBtColors.current
    val status by DynamicBtServiceState.status.collectAsState()

    var showAllApps by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var debounceSlider by remember { mutableFloatStateOf(status.config.debounceMs.toFloat()) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    LaunchedEffect(status.config.debounceMs) {
        if (!isDraggingSlider) {
            debounceSlider = status.config.debounceMs.toFloat()
        }
    }

    LaunchedEffect(showAllApps) {
        apps = withContext(Dispatchers.IO) {
            discoverApps(context, showAllApps, status.whitelist)
        }
    }

    val filteredApps = remember(apps, status.whitelist, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        val base = if (q.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.lowercase(Locale.getDefault()).contains(q) ||
                    it.packageName.lowercase(Locale.getDefault()).contains(q)
            }
        }
        base.sortedWith(
            compareByDescending<AppEntry> { it.packageName in status.whitelist }
                .thenByDescending { it.installed }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
    }

    val density = LocalDensity.current
    val topInset = with(density) {
        WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(this).toDp()
    }
    val bottomInset = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.backdropBg)
    ) {
        // Ambient Crimson & Obsidian Expressive Glow Backdrop
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CrimsonPrimary.copy(alpha = 0.16f),
                        CrimsonDeep.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.86f, size.height * 0.08f),
                    radius = size.width * 0.85f,
                ),
                radius = size.width * 0.85f,
                center = Offset(size.width * 0.86f, size.height * 0.08f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CrimsonDeep.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.12f, size.height * 0.62f),
                    radius = size.width * 0.72f,
                ),
                radius = size.width * 0.72f,
                center = Offset(size.width * 0.12f, size.height * 0.62f),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topInset + 12.dp,
                bottom = bottomInset + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                ExpressiveHeader(
                    running = status.running,
                    onToggleService = { onToggleService(!status.running) },
                )
            }

            item(key = "hero") {
                ExpressiveHeroTelemetryCard(
                    status = status,
                    onToggleService = { onToggleService(!status.running) },
                )
            }

            item(key = "controls") {
                ExpressiveControlsCard(
                    status = status,
                    debounceSlider = debounceSlider,
                    onDebounceChange = {
                        isDraggingSlider = true
                        debounceSlider = it
                    },
                    onDebounceFinished = {
                        isDraggingSlider = false
                        val roundedMs = ((debounceSlider / 500f).roundToLong() * 500L)
                            .coerceIn(0L, 15000L)
                        DynamicBtServiceState.setDebounceMs(roundedMs)
                    },
                )
            }

            item(key = "whitelist_header") {
                ExpressiveWhitelistHeader(
                    whitelistCount = status.whitelist.size,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    showAllApps = showAllApps,
                    onShowAllChange = { showAllApps = it },
                )
            }

            items(
                items = filteredApps,
                key = { it.packageName },
                contentType = { "app_row" },
            ) { entry ->
                ExpressiveAppCard(
                    entry = entry,
                    whitelisted = entry.packageName in status.whitelist,
                    onToggle = { checked ->
                        val next = status.whitelist.toMutableSet()
                        if (checked) next.add(entry.packageName) else next.remove(entry.packageName)
                        DynamicBtServiceState.updateWhitelist(next)
                    },
                )
            }
        }
    }
}

@Composable
private fun ExpressiveHeader(
    running: Boolean,
    onToggleService: () -> Unit,
) {
    val c = LocalArkBtColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArkCrestLogo(
            modifier = Modifier.size(48.dp),
            animated = running,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Ark Bt",
                style = MaterialTheme.typography.displaySmall,
                color = c.textPrimary,
            )
            Text(
                text = "Xe.Ark Audio Engine",
                style = MaterialTheme.typography.labelMedium,
                color = c.crimsonSoft,
            )
        }

        Surface(
            onClick = onToggleService,
            shape = CircleShape,
            color = if (running) c.crimson.copy(alpha = 0.18f) else c.subCardBg,
            border = BorderStroke(
                width = 1.dp,
                color = if (running) c.crimson.copy(alpha = 0.55f) else c.border,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (running) c.emerald else c.crimson),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (running) "AKTİF" else "DURDU",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (running) c.textPrimary else c.crimsonSoft,
                )
            }
        }
    }
}

@Composable
private fun ExpressiveHeroTelemetryCard(
    status: ServiceStatus,
    onToggleService: () -> Unit,
) {
    val c = LocalArkBtColors.current

    val modeLabel: String
    val modeColor: Color
    when (status.mode) {
        PolicyMode.DYNAMIC -> {
            modeLabel = "DYNAMIC HI-RES"
            modeColor = c.crimson
        }
        PolicyMode.HOLD -> {
            modeLabel = "HOLD HYSTERESIS"
            modeColor = c.amber
        }
        PolicyMode.DEFAULT -> {
            modeLabel = "DEFAULT PROFİL"
            modeColor = c.crimsonSoft
        }
        PolicyMode.DISABLED -> {
            modeLabel = "DEVRE DIŞI"
            modeColor = c.textSecondary
        }
    }

    val displayRate = status.a2dp.sampleRateHz.takeIf { it > 0 } ?: status.targetRateHz
    val displayBits = status.a2dp.bitsPerSample.takeIf { it > 0 } ?: status.targetBits
    val codecLabel = if (status.a2dp.connected) status.a2dp.codecName else "BEKLEMEDE"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        border = BorderStroke(1.dp, c.border),
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(c.heroTop, c.heroBottom)))
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = modeColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, modeColor.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(modeColor),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = modeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = modeColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onToggleService,
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = c.subCardBg,
                        contentColor = c.textPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (status.running) "Durdur" else "Başlat",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Big Expressive Audio Readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${formatKhz(displayRate)} kHz",
                        fontSize = 36.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1.0).sp,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "$displayBits-bit",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.crimsonSoft,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (status.a2dp.connected) c.crimson else c.subCardBg,
                ) {
                    Text(
                        text = codecLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (status.a2dp.connected) Color.White else c.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Politika hedefi: ${formatKhz(status.targetRateHz)} kHz / ${status.targetBits}-bit",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )

            Spacer(Modifier.height(14.dp))

            // 2x2 Expressive Bento Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricTile(
                    title = "OYNATAN UYGULAMA",
                    value = status.activeApp ?: "Ses yok",
                    subtitle = if (status.activeWhitelisted) "[HI-RES WHITELIST]" else "Standart rota",
                    accent = status.activeWhitelisted,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    title = "TRACK FORMATI",
                    value = if (status.activeTrackRateHz != null) {
                        "${formatKhz(status.activeTrackRateHz)} kHz"
                    } else {
                        "-"
                    },
                    subtitle = if (status.activeTrackRateHz != null) {
                        "${status.activeTrackBits ?: "?"}-bit akış"
                    } else {
                        "Beklemede"
                    },
                    accent = status.activeTrackRateHz != null && status.activeTrackRateHz > 48000,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val btValue = when {
                    !status.a2dp.connected -> "Bağlı değil"
                    !status.a2dp.deviceName.isNullOrBlank() -> status.a2dp.deviceName
                    !status.a2dp.deviceAddress.isNullOrBlank() -> status.a2dp.deviceAddress
                    else -> "Bağlı"
                }
                MetricTile(
                    title = "BT CİHAZI",
                    value = btValue,
                    subtitle = if (status.outputBtA2dp) "A2DP çıkışı aktif" else "A2DP pasif",
                    accent = status.a2dp.connected && status.outputBtA2dp,
                    modifier = Modifier.weight(1f),
                )

                val usbValue = if (status.usb.connected) {
                    status.usb.deviceName ?: "USB DAC"
                } else {
                    "Bağlı değil"
                }
                val usbSub = if (status.usb.connected && status.usb.sampleRates.isNotEmpty()) {
                    status.usb.sampleRates.joinToString(", ") { "${formatKhz(it)}k" }
                } else {
                    "Kablolu çıkış yok"
                }
                MetricTile(
                    title = "USB-C DAC",
                    value = usbValue,
                    subtitle = usbSub,
                    accent = status.usb.connected,
                    modifier = Modifier.weight(1f),
                )
            }

            val selectableRates = CodecCaps.rateMaskToHzList(status.a2dp.selectableRateMask)
            if (status.a2dp.connected && selectableRates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = c.border.copy(alpha = 0.6f))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Kulaklık Destekli Örnekleme Hızları",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectableRates.forEach { hz ->
                        val active = hz == status.a2dp.sampleRateHz
                        Surface(
                            shape = CircleShape,
                            color = if (active) c.crimson.copy(alpha = 0.22f) else c.subCardBg,
                            border = BorderStroke(
                                1.dp,
                                if (active) c.crimson else c.border,
                            ),
                        ) {
                            Text(
                                text = "${formatKhz(hz)} kHz",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) c.textPrimary else c.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            if (!status.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = c.crimson.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, c.crimson.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "Tanı: ${status.lastError}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.crimsonSoft,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    subtitle: String,
    accent: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LocalArkBtColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = c.subCardBg,
        border = BorderStroke(
            width = 1.dp,
            color = if (accent) c.crimson.copy(alpha = 0.45f) else c.border.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 10.sp,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 14.sp,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 11.5.sp,
                color = if (accent) c.crimsonSoft else c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExpressiveControlsCard(
    status: ServiceStatus,
    debounceSlider: Float,
    onDebounceChange: (Float) -> Unit,
    onDebounceFinished: () -> Unit,
) {
    val c = LocalArkBtColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = c.cardBg,
        border = BorderStroke(1.dp, c.border),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dinamik Hi-Res Modu",
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 18.sp,
                        color = c.textPrimary,
                    )
                    Text(
                        text = "Whitelist uygulamalarında kaynaktan gelen kHz'e geç",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = status.enabled,
                    onCheckedChange = { DynamicBtServiceState.setEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = c.crimson,
                        uncheckedThumbColor = c.textSecondary,
                        uncheckedTrackColor = c.subCardBg,
                        uncheckedBorderColor = c.border,
                    ),
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = c.border.copy(alpha = 0.6f))
            Spacer(Modifier.height(14.dp))

            Text(
                text = "Varsayılan Örnekleme Hızı (Whitelist Dışı)",
                style = MaterialTheme.typography.labelLarge,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(44100, 48000, 88200, 96000).forEach { rate ->
                    ExpressivePillChip(
                        label = "${formatKhz(rate)} kHz",
                        selected = status.config.defaultRateHz == rate,
                        onClick = {
                            DynamicBtServiceState.updateConfig { it.copy(defaultRateHz = rate) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Bit Derinliği",
                style = MaterialTheme.typography.labelLarge,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(16, 24, 32).forEach { bits ->
                    ExpressivePillChip(
                        label = "$bits-bit",
                        selected = status.config.defaultBits == bits,
                        onClick = {
                            DynamicBtServiceState.updateConfig { it.copy(defaultBits = bits) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            val secs = (debounceSlider / 100f).roundToLong() / 10.0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Geçiş Gecikmesi (Hysteresis / Debounce)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                )
                Text(
                    text = "$secs sn",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.crimsonSoft,
                )
            }
            Slider(
                value = debounceSlider,
                onValueChange = onDebounceChange,
                onValueChangeFinished = onDebounceFinished,
                valueRange = 0f..15000f,
                steps = 29,
                colors = SliderDefaults.colors(
                    thumbColor = c.crimson,
                    activeTrackColor = c.crimson,
                    inactiveTrackColor = c.subCardBg,
                    activeTickColor = Color.White.copy(alpha = 0.5f),
                    inactiveTickColor = c.border,
                ),
            )
        }
    }
}

@Composable
private fun ExpressivePillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalArkBtColors.current
    val bg by animateColorAsState(
        targetValue = if (selected) c.crimson else c.subCardBg,
        animationSpec = tween(180),
        label = "chipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) c.crimsonSoft else c.border,
        animationSpec = tween(180),
        label = "chipBorder",
    )

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else c.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun ExpressiveWhitelistHeader(
    whitelistCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    showAllApps: Boolean,
    onShowAllChange: (Boolean) -> Unit,
) {
    val c = LocalArkBtColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = c.cardBg,
        border = BorderStroke(1.dp, c.border),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Hi-Res Whitelist",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 18.sp,
                    color = c.textPrimary,
                )
                Surface(
                    shape = CircleShape,
                    color = c.crimson.copy(alpha = 0.18f),
                ) {
                    Text(
                        text = "$whitelistCount uygulama",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.crimsonSoft,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Seçili uygulamalarda gerçek örnekleme hızı açılır; diğer uygulamalarda varsayılan profil uygulanır.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )

            Spacer(Modifier.height(12.dp))

            // Expressive Pill Search Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                color = c.subCardBg,
                border = BorderStroke(1.dp, c.border),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Uygulama veya paket adı ara...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary,
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = c.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(c.crimson),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowAllChange(!showAllApps) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tüm kullanıcı uygulamalarını göster",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                )
                Switch(
                    checked = showAllApps,
                    onCheckedChange = onShowAllChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = c.crimson,
                        uncheckedThumbColor = c.textSecondary,
                        uncheckedTrackColor = c.subCardBg,
                        uncheckedBorderColor = c.border,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ExpressiveAppCard(
    entry: AppEntry,
    whitelisted: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val c = LocalArkBtColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = c.cardBg,
        border = BorderStroke(
            width = 1.dp,
            color = if (whitelisted) c.crimson.copy(alpha = 0.55f) else c.border.copy(alpha = 0.65f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.icon != null) {
                Image(
                    bitmap = entry.icon,
                    contentDescription = entry.label,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.subCardBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.label.take(1).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.titleMedium,
                        color = c.crimsonSoft,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (entry.installed) entry.label else "${entry.label} (kurulu değil)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (whitelisted) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = c.crimson.copy(alpha = 0.22f),
                        ) {
                            Text(
                                text = "HI-RES",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = c.crimsonSoft,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 11.5.sp,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))
            Switch(
                checked = whitelisted,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = c.crimson,
                    uncheckedThumbColor = c.textSecondary,
                    uncheckedTrackColor = c.subCardBg,
                    uncheckedBorderColor = c.border,
                ),
            )
        }
    }
}

private fun formatKhz(rateHz: Int): String =
    if (rateHz % 1000 == 0) (rateHz / 1000).toString() else String.format(Locale.US, "%.1f", rateHz / 1000.0)

private fun discoverApps(
    context: Context,
    includeAllLauncherApps: Boolean,
    whitelisted: Set<String>,
): List<AppEntry> {
    val pm = context.packageManager
    val map = linkedMapOf<String, AppEntry>()

    fun addPackage(pkg: String, fallbackLabel: String? = null) {
        if (pkg.isBlank() || pkg == context.packageName) return
        if (map.containsKey(pkg)) return
        val cached = appEntryCache[pkg]
        if (cached != null) {
            map[pkg] = cached
            return
        }
        val appInfo = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Exception) {
            null
        }
        val label = appInfo?.loadLabel(pm)?.toString()
            ?: fallbackLabel
            ?: KNOWN_DEFAULT_LABELS[pkg]
            ?: pkg
        val iconBitmap = if (appInfo != null) {
            try {
                pm.getApplicationIcon(appInfo).toBitmap(96, 96).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val entry = AppEntry(
            packageName = pkg,
            label = label,
            installed = appInfo != null,
            icon = iconBitmap,
        )
        appEntryCache[pkg] = entry
        map[pkg] = entry
    }

    val audioViewIntent = Intent(Intent.ACTION_VIEW).apply { type = "audio/*" }
    pm.queryIntentActivities(audioViewIntent, 0).forEach {
        addPackage(it.activityInfo.packageName, it.activityInfo.loadLabel(pm).toString())
    }

    val musicIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
    pm.queryIntentActivities(musicIntent, 0).forEach {
        addPackage(it.activityInfo.packageName, it.activityInfo.loadLabel(pm).toString())
    }

    val mediaServiceIntent = Intent("android.media.browse.MediaBrowserService")
    pm.queryIntentServices(mediaServiceIntent, 0).forEach {
        addPackage(it.serviceInfo.packageName, it.serviceInfo.loadLabel(pm).toString())
    }

    if (includeAllLauncherApps) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcherIntent, 0).forEach {
            addPackage(it.activityInfo.packageName, it.activityInfo.loadLabel(pm).toString())
        }
    }

    WhitelistStore.DEFAULT_PACKAGES.forEach { addPackage(it) }
    whitelisted.forEach { addPackage(it) }

    return map.values.sortedWith(
        compareByDescending<AppEntry> { it.packageName in whitelisted }
            .thenByDescending { it.installed }
            .thenBy { it.label.lowercase(Locale.getDefault()) }
    )
}
