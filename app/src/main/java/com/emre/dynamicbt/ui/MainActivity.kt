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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

@Immutable
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
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
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
            ArkBtTheme(darkTheme = isSystemInDarkTheme()) {
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

    /**
     * Some HyperOS/MTK builds reset the window frame rate when the activity
     * regains focus (dialogs, notifications, app switcher), which shows up as
     * forced 60 Hz scroll jank. Re-apply on every focus gain.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableHighRefreshRate()
        }
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

/**
 * Subscribes to a single field of the service status.
 *
 * The service pushes a new [ServiceStatus] every second. Collecting the whole
 * status at the top of the screen meant the entire app list was recomposed
 * ~1x/second, which is what made 120 Hz scrolling stutter. Each part of the
 * UI now observes only the slice it renders, with [distinctUntilChanged].
 */
@Composable
private fun <T> selectStatus(initial: T, selector: (ServiceStatus) -> T): T {
    val flow = remember { DynamicBtServiceState.status.map(selector).distinctUntilChanged() }
    return flow.collectAsState(initial).value
}

@Composable
private fun ArkBtScreen(
    onToggleService: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val c = LocalArkColors.current

    val running = selectStatus(false) { it.running }
    val whitelist = selectStatus(emptySet<String>()) { it.whitelist }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showAllApps by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    val statusListState = rememberLazyListState()
    val appsListState = rememberLazyListState()

    LaunchedEffect(showAllApps, whitelist) {
        apps = withContext(Dispatchers.IO) {
            discoverApps(context, showAllApps, whitelist)
        }
    }

    // Live panel refresh rate, sampled after the activity applied its mode.
    val view = LocalView.current
    var refreshRate by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        delay(400)
        refreshRate = try {
            view.display?.refreshRate ?: 0f
        } catch (_: Throwable) {
            0f
        }
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
        AmbientBackdrop()

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = topInset + 8.dp),
            ) {
                ArkHeader(running = running)
                Spacer(Modifier.height(12.dp))
                ArkTabBar(
                    selectedTab = selectedTab,
                    whitelistCount = whitelist.size,
                    onSelect = { selectedTab = it },
                )
                Spacer(Modifier.height(10.dp))
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (
                        slideInHorizontally(
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                        ) { width -> direction * (width / 7) } +
                            fadeIn(tween(240))
                        ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                        ) { width -> -direction * (width / 9) } +
                            fadeOut(tween(160))
                        )
                },
                modifier = Modifier.weight(1f),
                label = "tabContent",
            ) { tab ->
                if (tab == 0) {
                    StatusTab(
                        modifier = Modifier.fillMaxSize(),
                        listState = statusListState,
                        bottomInset = bottomInset,
                        refreshRate = refreshRate,
                        onToggleService = onToggleService,
                    )
                } else {
                    AppsTab(
                        modifier = Modifier.fillMaxSize(),
                        listState = appsListState,
                        bottomInset = bottomInset,
                        whitelist = whitelist,
                        apps = apps,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        showAllApps = showAllApps,
                        onShowAllChange = { showAllApps = it },
                        onToggleApp = { pkg, checked ->
                            val next = whitelist.toMutableSet()
                            if (checked) next.add(pkg) else next.remove(pkg)
                            DynamicBtServiceState.updateWhitelist(next)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Static crimson ambience: one warm top wash plus two soft glows. Drawn in a
 * Canvas that reads no animated state, so it is recorded once and never
 * repainted while scrolling.
 */
@Composable
private fun AmbientBackdrop() {
    val c = LocalArkColors.current
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    c.accent.copy(alpha = if (c.isDark) 0.11f else 0.05f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height * 0.45f,
            ),
            size = Size(size.width, size.height * 0.55f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CrimsonPrimary.copy(alpha = if (c.isDark) 0.17f else 0.08f),
                    CrimsonDeep.copy(alpha = if (c.isDark) 0.07f else 0.03f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.9f, size.height * 0.02f),
                radius = size.width * 0.8f,
            ),
            radius = size.width * 0.8f,
            center = Offset(size.width * 0.9f, size.height * 0.02f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CrimsonDeep.copy(alpha = if (c.isDark) 0.13f else 0.05f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.05f, size.height * 0.62f),
                radius = size.width * 0.65f,
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width * 0.05f, size.height * 0.62f),
        )
    }
}

// ---------------------------------------------------------------------------
// Header + tabs
// ---------------------------------------------------------------------------

@Composable
private fun ArkHeader(running: Boolean) {
    val c = LocalArkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArkCrestLogo(
            modifier = Modifier.size(46.dp),
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
                color = c.accentSoft,
            )
        }

        StatusPill(running = running)
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val c = LocalArkColors.current
    val dotColor by animateColorAsState(
        targetValue = if (running) c.success else c.textTertiary,
        animationSpec = tween(220),
        label = "statusDot",
    )
    val containerColor by animateColorAsState(
        targetValue = if (running) c.success.copy(alpha = 0.10f) else c.raisedBg,
        animationSpec = tween(260),
        label = "statusContainer",
    )
    val pulse by rememberInfiniteTransition(label = "liveDot").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDotPulse",
    )

    Surface(
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (running) c.success.copy(alpha = 0.35f) else c.border,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = if (running) pulse else 0.6f)),
            )
            Spacer(Modifier.width(7.dp))
            AnimatedContent(
                targetState = running,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                },
                label = "statusLabel",
            ) { isRunning ->
                Text(
                    text = if (isRunning) "Aktif" else "Durduruldu",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isRunning) c.textPrimary else c.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ArkTabBar(
    selectedTab: Int,
    whitelistCount: Int,
    onSelect: (Int) -> Unit,
) {
    val c = LocalArkColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = c.raisedBg.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, c.border),
    ) {
        BoxWithConstraints(modifier = Modifier.padding(4.dp)) {
            val gap = 4.dp
            val tabWidth = (maxWidth - gap) / 2
            val indicatorOffset by animateDpAsState(
                targetValue = if (selectedTab == 0) 0.dp else tabWidth + gap,
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                label = "tabIndicator",
            )

            // Sliding selection pill — drawn before the labels so it sits below.
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.accent),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                ArkTab(
                    text = "Durum",
                    selected = selectedTab == 0,
                    onClick = { onSelect(0) },
                    modifier = Modifier.weight(1f),
                )
                ArkTab(
                    text = "Uygulamalar",
                    badge = whitelistCount,
                    selected = selectedTab == 1,
                    onClick = { onSelect(1) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ArkTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int? = null,
) {
    val c = LocalArkColors.current
    val fg by animateColorAsState(
        targetValue = if (selected) c.onAccent else c.textSecondary,
        animationSpec = tween(220),
        label = "tabFg",
    )
    val badgeBg by animateColorAsState(
        targetValue = if (selected) {
            c.onAccent.copy(alpha = 0.20f)
        } else {
            c.accent.copy(alpha = 0.16f)
        },
        animationSpec = tween(220),
        label = "tabBadgeBg",
    )

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
            )
            if (badge != null && badge > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(badgeBg)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) c.onAccent else c.accentSoft,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 1: status
// ---------------------------------------------------------------------------

@Composable
private fun StatusTab(
    modifier: Modifier,
    listState: LazyListState,
    bottomInset: Dp,
    refreshRate: Float,
    onToggleService: (Boolean) -> Unit,
) {
    val status by DynamicBtServiceState.status.collectAsState()

    var debounceSlider by remember { mutableFloatStateOf(status.config.debounceMs.toFloat()) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    LaunchedEffect(status.config.debounceMs) {
        if (!isDraggingSlider) {
            debounceSlider = status.config.debounceMs.toFloat()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 2.dp,
            bottom = bottomInset + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") {
            StaggeredAppear(index = 0) {
                TelemetryCard(
                    status = status,
                    onToggleService = { onToggleService(!status.running) },
                )
            }
        }

        item(key = "controls") {
            StaggeredAppear(index = 1) {
                PolicyCard(
                    status = status,
                    debounceSlider = debounceSlider,
                    onDebounceChange = {
                        isDraggingSlider = true
                        debounceSlider = it
                    },
                    onDebounceFinished = {
                        isDraggingSlider = false
                        val roundedMs = ((debounceSlider / 500f).roundToLong() * 500L)
                            .coerceIn(0L, 10000L)
                        DynamicBtServiceState.setDebounceMs(roundedMs)
                    },
                )
            }
        }

        item(key = "footer") {
            StaggeredAppear(index = 2) {
                FooterLine(
                    text = buildString {
                        append("Xe.Ark Audio Engine")
                        if (refreshRate > 0f) {
                            append(" · ")
                            append(refreshRate.roundToLong())
                            append(" Hz")
                        }
                        append(" · ${status.whitelist.size} uygulama whitelist'te")
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 2: applications
// ---------------------------------------------------------------------------

@Composable
private fun AppsTab(
    modifier: Modifier,
    listState: LazyListState,
    bottomInset: Dp,
    whitelist: Set<String>,
    apps: List<AppEntry>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    showAllApps: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
) {
    val filteredApps = remember(apps, whitelist, searchQuery) {
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
            compareByDescending<AppEntry> { it.packageName in whitelist }
                .thenByDescending { it.installed }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 2.dp,
            bottom = bottomInset + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "whitelist_header") {
            WhitelistCard(
                whitelistCount = whitelist.size,
                searchQuery = searchQuery,
                onSearchChange = onSearchChange,
                showAllApps = showAllApps,
                onShowAllChange = onShowAllChange,
            )
        }

        if (filteredApps.isEmpty()) {
            item(key = "empty") {
                EmptyState(searchQuery = searchQuery)
            }
        } else {
            items(
                items = filteredApps,
                key = { it.packageName },
                contentType = { "app_row" },
            ) { entry ->
                AppRow(
                    entry = entry,
                    whitelisted = entry.packageName in whitelist,
                    onToggle = { onToggleApp(entry.packageName, it) },
                    // Animate rows when the whitelist sort order changes.
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "footer") {
            FooterLine(
                text = "Whitelist dışındaki uygulamalar varsayılan profili kullanır",
            )
        }
    }
}

/**
 * Fades + lifts content into place once, staggered by [index]. Runs only on
 * the first composition of a list item, so scrolling never re-triggers it.
 */
@Composable
private fun StaggeredAppear(
    index: Int,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(60L * index)
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "staggerAlpha",
    )
    val lift by animateDpAsState(
        targetValue = if (visible) 0.dp else 18.dp,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "staggerLift",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .offset(y = lift),
    ) {
        content()
    }
}

@Composable
private fun FooterLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalArkColors.current.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Telemetry
// ---------------------------------------------------------------------------

@Composable
private fun TelemetryCard(
    status: ServiceStatus,
    onToggleService: () -> Unit,
) {
    val c = LocalArkColors.current

    val modeLabel: String
    val modeColor: Color
    when (status.mode) {
        PolicyMode.DYNAMIC -> {
            modeLabel = "DİNAMİK Hİ-RES"
            modeColor = c.accent
        }

        PolicyMode.HOLD -> {
            modeLabel = "HYSTERESIS"
            modeColor = c.warning
        }

        PolicyMode.DEFAULT -> {
            modeLabel = "VARSAYILAN"
            modeColor = c.textSecondary
        }

        PolicyMode.DISABLED -> {
            modeLabel = "DEVRE DIŞI"
            modeColor = c.textTertiary
        }
    }

    val displayRate = status.a2dp.sampleRateHz.takeIf { it > 0 } ?: status.targetRateHz
    val displayBits = status.a2dp.bitsPerSample.takeIf { it > 0 } ?: status.targetBits
    val codecLabel = if (status.a2dp.connected) status.a2dp.codecName.uppercase(Locale.getDefault()) else "—"

    // Count the big readout up/down instead of hard-swapping the number.
    val animatedRate by animateIntAsState(
        targetValue = displayRate,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "heroRate",
    )
    val animatedBits by animateIntAsState(
        targetValue = displayBits,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "heroBits",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = c.surfaceBg,
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
                ModePill(label = modeLabel, color = modeColor)

                FilledTonalButton(
                    onClick = onToggleService,
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (status.running) c.raisedBg else c.accent,
                        contentColor = if (status.running) c.textPrimary else c.onAccent,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (status.running) "Durdur" else "Başlat",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Hero readout — tabular digits keep the number from shifting.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = formatKhz(animatedRate),
                    style = ArkNumericStyle.copy(
                        fontSize = 42.sp,
                        lineHeight = 44.sp,
                        color = c.textPrimary,
                    ),
                )
                Text(
                    text = "kHz",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
                )
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = codecLabel to status.a2dp.connected,
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 }) togetherWith
                            (fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 3 })
                    },
                    label = "codecBadge",
                ) { (label, connected) ->
                    CodecBadge(label = label, connected = connected)
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = "$animatedBits-bit  ·  hedef ${formatKhz(status.targetRateHz)} kHz / ${status.targetBits}-bit",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )

            val upgradeHint = status.a2dp.connected &&
                status.a2dp.hiresSelectable &&
                status.a2dp.bestCodecName.isNotBlank() &&
                !status.a2dp.codecName.equals(status.a2dp.bestCodecName, ignoreCase = true)
            AnimatedVisibility(
                visible = upgradeHint,
                enter = fadeIn(tween(240)) + expandVertically(tween(240)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
            ) {
                Text(
                    text = "${status.a2dp.bestCodecName} açıkken " +
                        "${formatKhz(status.targetRateHz)} kHz / ${status.targetBits}-bit yazılabilir",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.accent.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = c.border)
            Spacer(Modifier.height(4.dp))

            val btValue = when {
                !status.a2dp.connected -> "Bağlı değil"
                else -> status.a2dp.deviceName?.takeIf { it.isNotBlank() }
                    ?: status.a2dp.deviceAddress?.takeIf { it.isNotBlank() }
                    ?: "Bağlı"
            }
            val usbValue = if (status.usb.connected) {
                status.usb.deviceName ?: "USB DAC"
            } else {
                "Bağlı değil"
            }
            val usbDetail = if (status.usb.connected && status.usb.sampleRates.isNotEmpty()) {
                status.usb.sampleRates.joinToString(" · ") { "${formatKhz(it)} kHz" }
            } else {
                "Kablolu çıkış yok"
            }

            TelemetryRow(
                label = "Oynatan uygulama",
                value = status.activeApp ?: "Ses yok",
                detail = when {
                    status.activeApp == null -> "Beklemede"
                    status.activeWhitelisted -> "Hi-Res whitelist"
                    else -> "Standart rota"
                },
                accent = status.activeWhitelisted,
                badge = if (status.activeWhitelisted) "HI-RES" else null,
                showDivider = true,
            )
            TelemetryRow(
                label = "Parça formatı",
                value = status.activeTrackRateHz?.let { "${formatKhz(it)} kHz" } ?: "—",
                detail = when {
                    status.activeTrackRateHz == null -> "Beklemede"
                    (status.activeTrackBits ?: 0) > 0 &&
                        status.a2dp.bitsPerSample > (status.activeTrackBits ?: 0) ->
                        "${status.activeTrackBits}-bit akış · bağlantı " +
                            "${status.a2dp.bitsPerSample}-bit"
                    else -> "${status.activeTrackBits ?: "?"}-bit akış"
                },
                accent = (status.activeTrackRateHz ?: 0) > 48000,
                showDivider = true,
            )
            TelemetryRow(
                label = "Bluetooth çıkışı",
                value = btValue,
                detail = if (status.outputBtA2dp) "A2DP çıkışı aktif" else "A2DP pasif",
                accent = status.a2dp.connected && status.outputBtA2dp,
                showDivider = true,
            )
            TelemetryRow(
                label = "USB-C DAC",
                value = usbValue,
                detail = usbDetail,
                accent = status.usb.connected,
                showDivider = false,
            )

            RootStatusRow(
                rootAvailable = status.rootAvailable,
                writeSource = status.codecWriteSource,
                lastApplySuccess = status.lastApplySuccess,
            )

            val selectableRates = CodecCaps.rateMaskToHzList(status.a2dp.selectableRateMask)
            if (status.a2dp.connected && selectableRates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = c.border)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Kulaklığın desteklediği hızlar",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textTertiary,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectableRates.forEach { hz ->
                        val active = hz == status.a2dp.sampleRateHz
                        SmallChip(
                            text = "${formatKhz(hz)} kHz",
                            active = active,
                            accent = c.accent,
                        )
                    }
                }
            }

            val diagnostic = status.lastError?.takeIf { it.isNotBlank() }
            // Keep the last message while collapsing so the banner does not
            // turn blank halfway through its exit animation.
            var lastDiagnostic by remember { mutableStateOf("") }
            LaunchedEffect(diagnostic) {
                if (diagnostic != null) lastDiagnostic = diagnostic
            }
            AnimatedVisibility(
                visible = diagnostic != null,
                enter = fadeIn(tween(240)) + expandVertically(tween(280)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(220)),
            ) {
                Box(modifier = Modifier.padding(top = 12.dp)) {
                    DiagnosticBanner(message = lastDiagnostic)
                }
            }
        }
    }
}

@Composable
private fun ModePill(label: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.32f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun CodecBadge(label: String, connected: Boolean) {
    val c = LocalArkColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (connected) c.accentContainer else c.raisedBg,
        border = BorderStroke(
            width = 1.dp,
            color = if (connected) c.accent.copy(alpha = 0.45f) else c.border,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (connected) c.accentSoft else c.textTertiary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TelemetryRow(
    label: String,
    value: String,
    detail: String,
    accent: Boolean,
    badge: String? = null,
    showDivider: Boolean,
) {
    val c = LocalArkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    HiResBadge(text = badge)
                }
            }
            Spacer(Modifier.height(1.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (accent) c.accentSoft else c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showDivider) {
        HorizontalDivider(color = c.border.copy(alpha = 0.7f))
    }
}

@Composable
private fun RootStatusRow(
    rootAvailable: Boolean,
    writeSource: String,
    lastApplySuccess: Boolean?,
) {
    val c = LocalArkColors.current
    val accent = if (rootAvailable) c.success else c.warning

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "KODEK YAZMA",
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (rootAvailable) "Root köprüsü hazır" else "Root izni gerekli",
                style = MaterialTheme.typography.titleMedium,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = when {
                    writeSource == "root" && lastApplySuccess == true ->
                        "Son yazma root üzerinden doğrulandı"
                    writeSource == "root" -> "Root üzerinden yazma denendi"
                    writeSource == "api" -> "Gizli API ile yazma denendi"
                    !rootAvailable -> "Hz / bit derinliği yazmak için root erişimi verin"
                    else -> "Kodek değişimi bekleniyor"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (rootAvailable) c.textSecondary else accent,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (rootAvailable) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(c.success.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "ROOT",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.success,
                )
            }
        } else {
            Surface(
                onClick = { DynamicBtServiceState.requestRootAccess() },
                shape = CircleShape,
                color = c.accent.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, c.accent.copy(alpha = 0.4f)),
            ) {
                Text(
                    text = "İzin iste",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.accentSoft,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun HiResBadge(text: String) {
    val c = LocalArkColors.current
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = c.accent.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, c.accent.copy(alpha = 0.30f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = c.accentSoft,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SmallChip(text: String, active: Boolean, accent: Color) {
    val c = LocalArkColors.current
    Surface(
        shape = CircleShape,
        color = if (active) accent.copy(alpha = 0.18f) else c.raisedBg,
        border = BorderStroke(1.dp, if (active) accent.copy(alpha = 0.55f) else c.border),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) c.textPrimary else c.textSecondary,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DiagnosticBanner(message: String) {
    val c = LocalArkColors.current
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = c.danger.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, c.danger.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tanı",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.danger,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "Daralt" else "Ayrıntı",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.danger.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = c.danger.copy(alpha = 0.92f),
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Policy / profile controls
// ---------------------------------------------------------------------------

@Composable
private fun PolicyCard(
    status: ServiceStatus,
    debounceSlider: Float,
    onDebounceChange: (Float) -> Unit,
    onDebounceFinished: () -> Unit,
) {
    val c = LocalArkColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = c.surfaceBg,
        border = BorderStroke(1.dp, c.border),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dinamik Hi-Res",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Whitelist uygulamalarında kaynağın hızına geçer",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = status.enabled,
                    onCheckedChange = { DynamicBtServiceState.setEnabled(it) },
                    colors = arkSwitchColors(),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = c.border)
            Spacer(Modifier.height(16.dp))

            SectionLabel("Varsayılan hız · whitelist dışı")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(44100, 48000, 88200, 96000).forEach { rate ->
                    SegmentButton(
                        text = "${formatKhz(rate)} kHz",
                        selected = status.config.defaultRateHz == rate,
                        onClick = {
                            DynamicBtServiceState.updateConfig { it.copy(defaultRateHz = rate) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("Bit derinliği · alt sınır")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(16, 24, 32).forEach { bits ->
                    SegmentButton(
                        text = "$bits-bit",
                        selected = status.config.defaultBits == bits,
                        onClick = {
                            DynamicBtServiceState.updateConfig { it.copy(defaultBits = bits) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Bağlantı bu değerin altına inmez; parça daha yüksekse parçayı izler",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
            )

            Spacer(Modifier.height(18.dp))
            val secs = debounceSlider / 1000f
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Anahtarlama gecikmesi",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Hızlı parça geçişlerinde gereksiz codec değişimini engeller",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = CircleShape,
                    color = c.accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, c.accent.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f sn", secs),
                        style = MaterialTheme.typography.labelLarge,
                        color = c.accentSoft,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Slider(
                value = debounceSlider,
                onValueChange = onDebounceChange,
                onValueChangeFinished = onDebounceFinished,
                valueRange = 0f..10000f,
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = c.accent,
                    activeTrackColor = c.accent,
                    inactiveTrackColor = c.sunkenBg,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = LocalArkColors.current.textTertiary,
    )
}

@Composable
private fun SegmentButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalArkColors.current
    val bg by animateColorAsState(
        targetValue = if (selected) c.accent else c.raisedBg,
        animationSpec = tween(180),
        label = "segmentBg",
    )
    val border by animateColorAsState(
        targetValue = if (selected) c.accent else c.border,
        animationSpec = tween(180),
        label = "segmentBorder",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) c.onAccent else c.textSecondary,
        animationSpec = tween(180),
        label = "segmentText",
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun arkSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = LocalArkColors.current.accent,
    uncheckedThumbColor = LocalArkColors.current.textTertiary,
    uncheckedTrackColor = LocalArkColors.current.sunkenBg,
    uncheckedBorderColor = LocalArkColors.current.border,
)

// ---------------------------------------------------------------------------
// Whitelist
// ---------------------------------------------------------------------------

@Composable
private fun WhitelistCard(
    whitelistCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    showAllApps: Boolean,
    onShowAllChange: (Boolean) -> Unit,
) {
    val c = LocalArkColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = c.surfaceBg,
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
                    color = c.textPrimary,
                )
                Surface(
                    shape = CircleShape,
                    color = c.accentContainer,
                    border = BorderStroke(1.dp, c.accent.copy(alpha = 0.30f)),
                ) {
                    Text(
                        text = "$whitelistCount seçili",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.accentSoft,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Seçili uygulamalarda gerçek örnekleme hızı açılır; diğerleri varsayılan profili kullanır.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )

            Spacer(Modifier.height(14.dp))
            SearchField(
                query = searchQuery,
                onQueryChange = onSearchChange,
            )

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onShowAllChange(!showAllApps) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tüm kullanıcı uygulamalarını göster",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
                Switch(
                    checked = showAllApps,
                    onCheckedChange = onShowAllChange,
                    colors = arkSwitchColors(),
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val c = LocalArkColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = c.sunkenBg,
        border = BorderStroke(1.dp, c.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchGlyph(
                color = c.textTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Uygulama veya paket adı ara",
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textTertiary,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = c.textPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "✕",
                    fontSize = 14.sp,
                    color = c.textTertiary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onQueryChange("") }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.11f
        drawCircle(
            color = color,
            radius = size.minDimension * 0.30f,
            center = Offset(size.width * 0.42f, size.height * 0.42f),
            style = Stroke(width = strokeWidth),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.63f, size.height * 0.63f),
            end = Offset(size.width * 0.88f, size.height * 0.88f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun EmptyState(searchQuery: String) {
    val c = LocalArkColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (searchQuery.isBlank()) "Gösterilecek uygulama yok" else "Eşleşen uygulama yok",
            style = MaterialTheme.typography.titleMedium,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (searchQuery.isBlank()) {
                "Tüm uygulamaları görmek için yukarıdaki anahtarı açın."
            } else {
                "Farklı bir ad veya paket adı deneyin."
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
    }
}

@Composable
private fun AppRow(
    entry: AppEntry,
    whitelisted: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalArkColors.current
    val containerColor by animateColorAsState(
        targetValue = if (whitelisted) c.accentContainer.copy(alpha = 0.55f) else c.surfaceBg,
        animationSpec = tween(220),
        label = "appRowBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (whitelisted) c.accent.copy(alpha = 0.45f) else c.border,
        animationSpec = tween(220),
        label = "appRowBorder",
    )

    Surface(
        onClick = { onToggle(!whitelisted) },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.icon != null) {
                Image(
                    bitmap = entry.icon,
                    contentDescription = entry.label,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.raisedBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.label.take(1).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.titleMedium,
                        color = c.accentSoft,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (entry.installed) c.textPrimary else c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AnimatedVisibility(
                        visible = whitelisted,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(120)),
                    ) {
                        Row {
                            Spacer(Modifier.width(6.dp))
                            HiResBadge(text = "HI-RES")
                        }
                    }
                }
                Spacer(Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!entry.installed) {
                        Spacer(Modifier.width(6.dp))
                        Tag(text = "KURULU DEĞİL")
                    }
                }
            }

            Spacer(Modifier.width(8.dp))
            Switch(
                checked = whitelisted,
                onCheckedChange = onToggle,
                colors = arkSwitchColors(),
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    val c = LocalArkColors.current
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = c.raisedBg,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = c.textTertiary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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
