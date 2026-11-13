package com.medvault.app

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// MedVault's iOS-inspired dark surface system: quiet graphite layers and a cool periwinkle accent.
private val ShellBackground = Color(0xFF080B12)
private val ShellInk = Color(0xFFF5F6FA)
private val ShellMuted = Color(0xFF9299AA)
private val ShellBlue = Color(0xFF78AFFF)
private val HomeBackground = Color(0xFF080B12)
private val HomeInk = Color(0xFFF5F6FA)
private val HomeMuted = Color(0xFF9299AA)
private val HomeBlue = Color(0xFF78AFFF)
private val HomePanel = Color(0xFF171D30)
private val HomePanelAlt = Color(0xFF202944)
private val HomeBorder = Color(0xFF343D54)

private val LiquidViolet = Color(0xFFB18CFF)
private val LiquidTeal = Color(0xFF62D7C8)

/**
 * Central Liquid design tokens. These are intentionally semantic rather than
 * screen-specific so every surface and interaction shares the same material language.
 */
internal object LiquidPalette {
    val Background = Color(0xFF080B14)
    val Surface = Color(0xCC111A2B)
    val SurfaceRaised = Color(0xD91B2940)
    val Primary = Color(0xFF86B8FF)
    val Violet = Color(0xFFB69AFF)
    val Teal = Color(0xFF6ADBCB)
    val Amber = Color(0xFFF2C66D)
    val Ink = Color(0xFFF4F6FC)
    val Muted = Color(0xFFA0AABD)
    val Hairline = Color.White.copy(alpha = 0.20f)
}

/**
 * Motion presets for interruptible, velocity-continuous Compose spring animations.
 * Use low/no bounce for navigation and content; reserve slight under-damping for touch.
 */
internal object LiquidMotion {
    // Navigation springs are deliberately overdamped: fast, controlled, no theatrical bounce.
    val Navigation = spring<Float>(dampingRatio = 0.96f, stiffness = 520f)
    val NavigationOffset = spring<IntOffset>(dampingRatio = 0.96f, stiffness = 520f)
    val Selection = spring<Float>(dampingRatio = 0.90f, stiffness = 560f)
    val Touch = spring<Float>(dampingRatio = 0.82f, stiffness = 760f)
    val Content = spring<Float>(dampingRatio = 1.0f, stiffness = 420f)
    val FadeIn = tween<Float>(durationMillis = 150)
    val FadeOut = tween<Float>(durationMillis = 110)
}

/**
 * Shared tactile response for high-value interactive surfaces.
 * The animated transform is applied in the draw phase to avoid per-frame layout work.
 */
@Composable
private fun Modifier.liquidPressFeedback(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.985f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = LiquidMotion.Touch,
        label = "liquid_surface_press"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Lightweight glass approximation for API 26+: translucent layered tint,
 * a restrained cool highlight and a hairline edge. This is not backdrop blur;
 * API 31+ blur must be implemented at the parent/backdrop layer, not on this child.
 */
internal fun Modifier.liquidGlassSurface(
    accent: Color = LiquidPalette.Primary,
    radius: androidx.compose.ui.unit.Dp = 28.dp
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(
                    Color(0xD9233552),
                    Color(0xC9142034),
                    accent.copy(alpha = 0.075f)
                )
            )
        )
        .border(
            androidx.compose.foundation.BorderStroke(1.dp, LiquidPalette.Hairline),
            shape
        )
        .drawWithCache {
            val corner = CornerRadius(radius.toPx())
            val sheen = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.15f),
                    accent.copy(alpha = 0.055f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.14f, size.height * 0.02f),
                radius = size.maxDimension * 1.05f
            )
            val edge = Color.White.copy(alpha = 0.12f)
            onDrawWithContent {
                drawRoundRect(brush = sheen, cornerRadius = corner)
                drawContent()
                // A restrained specular rim, strongest along the upper edge.
                drawLine(
                    color = edge,
                    start = Offset(radius.toPx(), 0.8.dp.toPx()),
                    end = Offset(size.width - radius.toPx(), 0.8.dp.toPx()),
                    strokeWidth = 0.8.dp.toPx()
                )
            }
        }
}

/** A low-cost glass-like surface: layered colour, a restrained highlight and a fine edge. */
internal fun Modifier.liquidPanel(
    accent: Color = HomeBlue,
    radius: androidx.compose.ui.unit.Dp = 24.dp
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    Color(0xE6243048),
                    Color(0xD9162032),
                    accent.copy(alpha = 0.10f)
                )
            )
        )
        .border(
            androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.105f)),
            shape
        )
}

@Composable
fun MedVaultHomeScreen(
    onVideos: () -> Unit,
    onQBank: () -> Unit,
    onTests: () -> Unit,
    onDailyDose: () -> Unit,
    onUpdates: () -> Unit,
    onSettings: () -> Unit,
    activeEdition: MedVaultEdition,
    onEditionChange: (MedVaultEdition) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val contentItems by ContentFeedDatabase.get(context).contentDao().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var pullPx by remember { mutableFloatStateOf(0f) }
    var refreshRequested by remember { mutableStateOf(false) }
    var showRefreshResult by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thresholdPx = with(density) { 92.dp.toPx() }
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData(ContentFeedScheduler.manualWorkName()).observeAsState()
    val activeWork = workInfos.orEmpty().firstOrNull { !it.state.isFinished }
    val refreshConnection = remember(listState, thresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (atTop && available.y > 0f && !refreshRequested) {
                    pullPx = (pullPx + available.y * 0.58f).coerceAtMost(with(density) { 150.dp.toPx() })
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullPx >= thresholdPx && !refreshRequested) {
                    refreshRequested = true
                    ContentFeedScheduler.refresh(context)
                }
                // The content surface must spring home as soon as the finger is released.
                // Keep the refresh indicator independently pinned while background sync runs.
                scope.launch {
                    animate(pullPx, 0f, animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f)) { value, _ -> pullPx = value }
                }
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(workInfos, refreshRequested) {
        if (refreshRequested && workInfos.orEmpty().any { it.state.isFinished }) {
            showRefreshResult = true
            kotlinx.coroutines.delay(650)
            showRefreshResult = false
            refreshRequested = false
            animate(pullPx, 0f, animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f)) { value, _ -> pullPx = value }
        }
    }
    val spinning = refreshRequested && activeWork != null
    val spinTransition = rememberInfiniteTransition(label = "vault-refresh")
    val rotation by spinTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)
        ), label = "vault-rotation"
    )
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).nestedScroll(refreshConnection)) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = pullPx * 0.82f
                scaleY = 1f + (pullPx / with(density) { 1800.dp.toPx() }).coerceAtMost(0.075f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            },
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("MEDVAULT · LIQUID", color = HomeBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
                        Text("Your study space.", color = HomeInk, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                    }
                    val profileInteraction = remember { MutableInteractionSource() }
                    Surface(
                        onClick = onSettings,
                        interactionSource = profileInteraction,
                        modifier = Modifier.liquidPressFeedback(profileInteraction, 0.96f),
                        color = HomePanel,
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HomeBorder)
                    ) {
                        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PersonOutline, "Profile and settings", tint = HomeInk, modifier = Modifier.size(23.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("YOUR LIBRARY", color = HomeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
                            Text("Manage", color = HomeBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        listOf(MedVaultEdition.M, MedVaultEdition.P, MedVaultEdition.U).forEach { edition ->
                            val selected = activeEdition == edition
                            val editionInteraction = remember { MutableInteractionSource() }
                            Surface(
                                modifier = Modifier.weight(1f)
                                    .liquidPanel(if (selected) HomeBlue else HomeMuted, 18.dp)
                                    .liquidPressFeedback(editionInteraction)
                                    .then(if (selected) Modifier.border(1.dp, HomeBlue.copy(alpha = 0.62f), RoundedCornerShape(18.dp)) else Modifier)
                                    .clickable(interactionSource = editionInteraction, indication = null) { onEditionChange(edition) },
                                color = Color.Transparent,
                                shape = RoundedCornerShape(18.dp),
                                border = null
                            ) {
                                Column(
                                    Modifier.padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text("EDITION", color = if (selected) HomeBlue else HomeMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                    Text(edition.name, color = if (selected) HomeInk else HomeMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("PICK UP WHERE YOU LEFT OFF", color = HomeMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                        Text("A little learning, daily.", color = HomeInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(Icons.Default.AutoStories, null, tint = HomeBlue.copy(alpha = 0.9f), modifier = Modifier.size(21.dp))
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().liquidPanel(HomeBlue, 26.dp)
                        .border(1.dp, HomeBlue.copy(alpha = 0.18f), RoundedCornerShape(26.dp)),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(26.dp),
                    border = null
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = HomeBlue.copy(alpha = 0.13f), shape = RoundedCornerShape(50)) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoAwesome, null, tint = HomeBlue, modifier = Modifier.size(12.dp))
                                    Text("TODAY'S CONCEPT", color = HomeBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("12 MIN READ", color = HomeMuted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                        }
                        Text("ACE inhibitors\nand bradykinin", color = HomeInk, fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 33.sp, letterSpacing = (-0.5).sp)
                        Text(
                            "ACE breaks down bradykinin. ACE inhibition increases bradykinin levels, contributing to the characteristic dry cough and, rarely, angioedema.",
                            color = HomeMuted, fontSize = 14.sp, lineHeight = 22.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PHARMACOLOGY", color = HomeBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.weight(1f))
                            val studyNowInteraction = remember { MutableInteractionSource() }
                            Surface(
                                color = HomeBlue,
                                shape = RoundedCornerShape(50),
                                onClick = onDailyDose,
                                interactionSource = studyNowInteraction,
                                modifier = Modifier.liquidPressFeedback(studyNowInteraction, 0.96f)
                            ) {
                                Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Study now", color = Color(0xFF111522), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Icon(Icons.Default.ArrowForward, null, tint = Color(0xFF111522), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeActionTile("Flashcards", "Quick recall", Icons.Default.Style, onDailyDose, Modifier.weight(1f))
                    HomeActionTile("Daily MCQ", "Test yourself", Icons.Default.Psychology, onDailyDose, Modifier.weight(1f))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("CLINICAL CORNER", "Think through a clinical case")
                var revealCase by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .liquidPanel(Color(0xFFF4C34F), 22.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HomeTag("CASE OF THE DAY", Color(0xFFF4C34F))
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.MonitorHeart, null, tint = Color(0xFFF4C34F))
                        }
                        Text("Sudden severe chest pain", color = HomeInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("A 58-year-old man develops abrupt, tearing chest pain radiating to the back. His right-arm blood pressure is higher than the left.", color = HomeMuted, fontSize = 13.sp, lineHeight = 19.sp)
                        if (revealCase) {
                            Text("Key consideration: acute aortic syndrome, including aortic dissection. This is an emergency; the vignette is educational and not a diagnostic rule.", color = HomeInk, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                        TextButton(onClick = { revealCase = !revealCase }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (revealCase) "Hide clinical clue" else "Reveal the key consideration")
                        }
                    }
                }
            }
        }
        item {
            HomeLiveFeedSection("EXAM RADAR", "Official examination notices and deadlines", contentItems.filter { it.category == "exam-radar" }.take(3), "No verified exam notices have been published to the feed yet.", onUpdates) { url -> runCatching { uriHandler.openUri(url) } }
        }
        item {
            HomeLiveFeedSection("NEET PG UPDATES", "NBEMS, MCC and postgraduate entrance notices", contentItems.filter { it.category == "neet-pg" }.take(3), "No verified NEET PG updates are currently published.", onUpdates) { url -> runCatching { uriHandler.openUri(url) } }
        }
        item {
            HomeLiveFeedSection("MBBS UPDATES", "Undergraduate medical education and university notices", contentItems.filter { it.category == "mbbs-updates" }.take(3), "No verified MBBS notices are currently published.", onUpdates) { url -> runCatching { uriHandler.openUri(url) } }
        }
        item {
            HomeLiveFeedSection("TRENDING IN MEDICINE", "Recent scientific and clinical developments", contentItems.filter { it.category == "trending-medicine" }.take(3), "Research updates will appear here when the collector publishes them.", onUpdates) { url -> runCatching { uriHandler.openUri(url) } }
        }
        item {
            HomeLiveFeedSection("MEDICAL NEWS BULLETIN", "Indian healthcare, public health and research", contentItems.filter { it.category == "medical-news" }.take(3), "No medical news articles have been published to the feed yet.", onUpdates) { url -> runCatching { uriHandler.openUri(url) } }
        }
    }
    if (pullPx > 1f || refreshRequested || showRefreshResult) {
        Canvas(
            Modifier.align(Alignment.TopCenter)
                .offset {
                    val indicatorY = if (refreshRequested) with(density) { 12.dp.toPx() }
                        else pullPx - with(density) { 42.dp.toPx() }
                    IntOffset(0, indicatorY.toInt())
                }
                .size(38.dp)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.39f
            val tint = if (showRefreshResult) Color(0xFF65D6A3) else HomeBlue
            drawArc(tint.copy(alpha = 0.9f), startAngle = -90f + if (spinning) rotation else 0f,
                sweepAngle = if (spinning) 270f else 300f, useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            rotate(if (spinning) rotation else 0f, center) {
                drawRoundRect(tint, topLeft = Offset(center.x - radius * 0.58f, center.y - radius * 0.58f),
                    size = androidx.compose.ui.geometry.Size(radius * 1.16f, radius * 1.16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.22f),
                    style = Stroke(width = 2.dp.toPx()))
                drawCircle(tint, radius = radius * 0.18f, center = center)
                drawLine(tint, Offset(center.x, center.y - radius * 0.18f),
                    Offset(center.x, center.y + radius * 0.18f), strokeWidth = 1.6.dp.toPx())
            }
            if (showRefreshResult) {
                drawCircle(tint, radius = 2.2.dp.toPx(), center = Offset(center.x + radius * 0.82f, center.y - radius * 0.82f))
            }
        }
    }
    }
}

@Composable
private fun HomeLiveFeedSection(
    title: String,
    subtitle: String,
    items: List<ContentItemEntity>,
    emptyMessage: String,
    onOpen: () -> Unit,
    onArticle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = HomeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.15.sp)
                Text(subtitle, color = HomeInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("VIEW ALL") }
        }
        if (items.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().liquidPanel(LiquidTeal, 18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RssFeed, null, tint = HomeMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(emptyMessage, color = HomeMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        } else {
            items.forEach { article ->
                Card(
                    modifier = Modifier.fillMaxWidth().liquidPanel(LiquidTeal, 18.dp)
                        .clickable { onArticle(article.url) },
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(article.title, color = HomeInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(article.sourceName.takeIf { it.isNotBlank() }, article.publishedAt?.take(10)).joinToString(" · "), color = HomeMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = HomeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.15.sp)
        Text(subtitle, color = HomeInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HomeTag(text: String, tint: Color) {
    Surface(color = tint.copy(alpha = 0.13f), shape = RoundedCornerShape(6.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun HomeActionTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .liquidPanel(if (title == "Flashcards") LiquidViolet else LiquidTeal, 22.dp)
            .liquidPressFeedback(interactionSource),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 142.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Surface(
                color = (if (title == "Flashcards") LiquidViolet else LiquidTeal).copy(alpha = 0.13f),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, (if (title == "Flashcards") LiquidViolet else LiquidTeal).copy(alpha = 0.30f))
            ) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (title == "Flashcards") LiquidViolet else LiquidTeal, modifier = Modifier.size(23.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(title, color = HomeInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = HomeMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HomeEditorialCard(
    eyebrow: String,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?,
    footnote: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = if (onClick != null) Modifier.fillMaxWidth()
            .liquidPressFeedback(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            else Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HomePanel)
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
            Surface(color = HomePanelAlt, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = HomeBlue, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(eyebrow, color = HomeBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp)
                Text(title, color = HomeInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                Text(subtitle, color = HomeMuted, fontSize = 11.sp, lineHeight = 16.sp)
                Text(footnote, color = if (onClick != null) HomeBlue else HomeMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
            }
            if (onClick != null) {
                Icon(Icons.Default.ChevronRight, null, tint = HomeMuted, modifier = Modifier.padding(start = 4.dp).size(19.dp))
            }
        }
    }
}

/**
 * Uses Android's cross-window blur to sample the real app content behind the dock.
 * If the system disables cross-window blur, the existing glass surface remains
 * readable and functional without blur.
 */
@Composable
fun MedVaultBottomBarHost(
    selected: String,
    onHome: () -> Unit,
    onVideos: () -> Unit,
    onQBank: () -> Unit,
    onOffline: () -> Unit,
    onProfile: () -> Unit
) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                dialogWindow?.let { window ->
                    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    // The dock is a small overlay, not a full-screen touch layer.
                    // Let all gestures outside its bounds reach the screen underneath.
                    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    val blurEnabled = context
                        .getSystemService(WindowManager::class.java)
                        ?.isCrossWindowBlurEnabled == true
                    if (blurEnabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        window.setBackgroundBlurRadius(40)
                        window.setDimAmount(0.10f)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        window.setBackgroundBlurRadius(0)
                        window.setDimAmount(0f)
                    }
                    window.attributes = window.attributes.apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        width = WindowManager.LayoutParams.MATCH_PARENT
                        // Constrain the Android window itself; WRAP_CONTENT can expand the
                        // non-fitting Dialog to the full activity decor and intercept all touches.
                        height = (90f * context.resources.displayMetrics.density).toInt()
                        dimAmount = if (blurEnabled) 0.10f else 0f
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(90.dp).padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
                MedVaultBottomBar(selected, onHome, onVideos, onQBank, onOffline, onProfile)
            }
        }
    } else {
        MedVaultBottomBar(selected, onHome, onVideos, onQBank, onOffline, onProfile)
    }
}

@Composable
fun MedVaultBottomBar(
    selected: String,
    onHome: () -> Unit,
    onVideos: () -> Unit,
    onQBank: () -> Unit,
    onOffline: () -> Unit,
    onProfile: () -> Unit
) {
    val items = listOf(
        Triple("home", "Home", Icons.Default.Home),
        Triple("videos", "Lectures", Icons.Default.PlayCircle),
        Triple("qbank", "QBank", Icons.Default.MenuBook),
        Triple("offline", "Offline", Icons.Default.Download),
        Triple("profile", "Profile", Icons.Default.Person)
    )
    val actions = listOf(onHome, onVideos, onQBank, onOffline, onProfile)
    val accents = listOf(HomeBlue, LiquidViolet, LiquidTeal, Color(0xFFF4C34F), HomeBlue)

    // Lightweight outlined dock inspired by the reference: no heavy selected capsule.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().liquidGlassSurface(LiquidPalette.Primary, 34.dp),
            color = Color.Transparent,
            shape = RoundedCornerShape(34.dp),
            border = null,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = selected == item.first
                    val accent = accents[index]
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val pressScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.94f else 1f,
                        animationSpec = LiquidMotion.Touch,
                        label = "dockPress"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = LiquidMotion.Navigation,
                        label = "dockSelection"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            }
                            .clip(RoundedCornerShape(22.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = actions[index]
                            )
                            .padding(top = 5.dp, bottom = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            item.third,
                            contentDescription = item.second,
                            tint = if (isSelected) accent else HomeMuted.copy(alpha = 0.82f),
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.second,
                            color = if (isSelected) accent else HomeMuted,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .size(4.dp)
                                .background(if (isSelected) accent else Color.Transparent, RoundedCornerShape(50))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MedVaultOfflineScreen(
    downloadedLectures: List<DownloadedLecture>,
    onOpenPdf: (File, String) -> Unit,
    onPlayOffline: (DownloadedLecture) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pdfs = remember { TelegramPdfStore.downloaded(context) }
    val videoBytes = downloadedLectures.sumOf { File(it.localPath).length() }
    val pdfBytes = pdfs.sumOf { it.length() }
    val totalBytes = videoBytes + pdfBytes
    val totalDeviceBytes = android.os.StatFs(context.filesDir.path).totalBytes.coerceAtLeast(1L)
    val storageFraction = (totalBytes.toFloat() / totalDeviceBytes.toFloat()).coerceIn(0f, 1f)
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
        else -> "${(bytes / 1024L).coerceAtLeast(0L)} KB"
    }
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B))))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ShellInk) }
            Column {
                Text("OFFLINE VAULT", color = Color(0xFFF4C34F), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Downloads", color = ShellInk, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).liquidPanel(Color(0xFFB18CFF), 26.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(26.dp),
            border = null
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloaded storage", color = ShellMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(sizeLabel(totalBytes), color = Color(0xFFF4C34F), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth(storageFraction).fillMaxHeight().background(Color(0xFFB477F5)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("● Videos  ${sizeLabel(videoBytes)}", color = Color(0xFFB477F5), fontSize = 11.sp)
                    Text("● PDFs  ${sizeLabel(pdfBytes)}", color = Color(0xFFFF5470), fontSize = 11.sp)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Text("DOWNLOADED VIDEOS", color = ShellMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            items(downloadedLectures, key = { it.fileId }) { lecture ->
                Card(Modifier.fillMaxWidth().clickable { onPlayOffline(lecture) }, colors = CardDefaults.cardColors(containerColor = Color(0xD91A263B)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayCircle, null, tint = ShellBlue)
                        Spacer(Modifier.width(10.dp))
                        Text(lecture.name, Modifier.weight(1f), color = ShellInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ChevronRight, null, tint = ShellMuted)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)); Text("DOWNLOADED PDFs", color = ShellMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            items(pdfs, key = { it.absolutePath }) { file ->
                Card(Modifier.fillMaxWidth().clickable { onOpenPdf(file, file.nameWithoutExtension) }, colors = CardDefaults.cardColors(containerColor = HomePanel)) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFE45E5E))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.nameWithoutExtension, color = ShellInk, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${(file.length() / 1024).coerceAtLeast(1)} KB · Available offline", color = ShellMuted, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = ShellMuted)
                    }
                }
            }
            if (downloadedLectures.isEmpty() && pdfs.isEmpty()) {
                item { Text("Nothing downloaded yet.", color = ShellMuted, modifier = Modifier.padding(vertical = 20.dp)) }
            }
        }
    }
}


@Composable
fun MedVaultSettingsScreen(onBack: () -> Unit, onStudySources: () -> Unit, onConnectAccount: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("medvault_profile", android.content.Context.MODE_PRIVATE) }
    var displayName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getString("display_name", "Aftab Khan") ?: "Aftab Khan") }
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAbout by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ShellInk) }
            Column {
                Text("ACCOUNT", color = ShellBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Profile & Settings", color = ShellInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xE6202D46)), shape = RoundedCornerShape(30.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.13f))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountCircle, null, tint = ShellBlue, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayName, color = ShellInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Medical student", color = ShellBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = { editing = !editing }) { Text(if (editing) "Close" else "Edit") }
                }
                if (editing) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name") }
                    )
                    Button(onClick = {
                        prefs.edit().putString("display_name", displayName.trim().ifBlank { "Medical student" }).apply()
                        editing = false
                    }) { Text("Save profile") }
                }
            }
        }
        Card(Modifier.fillMaxWidth().liquidPanel(ShellBlue, 22.dp).clickable(onClick = onConnectAccount), colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(22.dp), border = null) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBox, null, tint = ShellBlue, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Content account", color = ShellInk, fontWeight = FontWeight.SemiBold)
                    Text("Connect or manage your study content account", color = ShellMuted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = ShellMuted)
            }
        }
        Card(Modifier.fillMaxWidth().liquidPanel(ShellBlue, 22.dp).clickable(onClick = onStudySources), colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(22.dp), border = null) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, null, tint = ShellBlue, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Library sources", color = ShellInk, fontWeight = FontWeight.SemiBold)
                    Text("Configure video editions and Notes source group", color = ShellMuted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = ShellMuted)
            }
        }
        Card(Modifier.fillMaxWidth().liquidPanel(LiquidViolet, 22.dp).clickable { showAbout = !showAbout }, colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(22.dp), border = null) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = ShellBlue, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("About MedVault", color = ShellInk, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(if (showAbout) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = ShellMuted)
                }
                if (showAbout) {
                    Text("MedVault is a personal medical learning library for lectures, question banks, tests and revision.", color = ShellMuted, fontSize = 12.sp)
                    Text("Version 1.0", color = ShellMuted, fontSize = 11.sp)
                }
            }
        }
    }
}


private data class MedVaultMcq(
    val id: Int,
    val subject: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String
)

private val starterMcqs = listOf(
    MedVaultMcq(1, "Pharmacology", "A patient develops a persistent dry cough after starting enalapril. The most likely mediator is:", listOf("Angiotensin II", "Bradykinin", "Aldosterone", "Renin"), 1, "ACE degrades bradykinin. ACE inhibition increases bradykinin, which can cause cough."),
    MedVaultMcq(2, "Pathology", "Which marker combination is characteristic of classical Hodgkin lymphoma Reed–Sternberg cells?", listOf("CD3 and CD5", "CD15 and CD30", "CD10 and CD20", "CD34 and CD117"), 1, "Classical Reed–Sternberg cells typically express CD15 and CD30."),
    MedVaultMcq(3, "Microbiology", "India ink preparation is classically used to demonstrate the capsule of:", listOf("Candida albicans", "Cryptococcus neoformans", "Aspergillus fumigatus", "Histoplasma capsulatum"), 1, "India ink is a negative stain that demonstrates the prominent polysaccharide capsule of Cryptococcus."),
    MedVaultMcq(4, "Physiology", "The pacemaker potential of the SA node is primarily associated with:", listOf("Fast voltage-gated sodium current", "Funny current and calcium currents", "Chloride influx", "Potassium influx"), 1, "The funny current (If) and calcium currents contribute to spontaneous phase 4 depolarization."),
    MedVaultMcq(5, "Biochemistry", "The rate-limiting enzyme of cholesterol synthesis is:", listOf("HMG-CoA reductase", "HMG-CoA synthase", "Lecithin cholesterol acyltransferase", "Acetyl-CoA carboxylase"), 0, "HMG-CoA reductase converts HMG-CoA to mevalonate and is inhibited by statins."),
    MedVaultMcq(6, "Medicine", "A wide pulse pressure is classically associated with:", listOf("Aortic stenosis", "Aortic regurgitation", "Cardiac tamponade", "Mitral stenosis"), 1, "Aortic regurgitation increases systolic pressure and lowers diastolic pressure, widening pulse pressure."),
    MedVaultMcq(7, "Surgery", "The most common cause of small bowel obstruction in adults in developed settings is:", listOf("Adhesions", "Intussusception", "Volvulus", "Gallstone ileus"), 0, "Postoperative adhesions are the leading cause of small bowel obstruction in adults."),
    MedVaultMcq(8, "Obstetrics", "The most common site of ectopic pregnancy is:", listOf("Interstitial part", "Ampulla of fallopian tube", "Isthmus", "Ovary"), 1, "Most tubal ectopic pregnancies occur in the ampullary portion."),
    MedVaultMcq(9, "Pediatrics", "The most common cause of nephrotic syndrome in children is:", listOf("Focal segmental glomerulosclerosis", "Minimal change disease", "Membranous nephropathy", "IgA nephropathy"), 1, "Minimal change disease is the most common cause of nephrotic syndrome in children."),
    MedVaultMcq(10, "PSM", "The best measure of association in a cohort study is:", listOf("Odds ratio", "Relative risk", " attributable risk percent", "Prevalence ratio"), 1, "A cohort study directly measures incidence in exposed and unexposed groups, allowing calculation of relative risk.")
)

private val highYieldFacts = listOf(
    "Rifampicin can cause orange-red discoloration of urine, sweat and tears.",
    "The most common site of ectopic pregnancy is the ampulla of the fallopian tube.",
    "Classical Hodgkin lymphoma cells typically express CD15 and CD30.",
    "Cryptococcus neoformans has a polysaccharide capsule demonstrable by India ink.",
    "HMG-CoA reductase is the rate-limiting enzyme in cholesterol synthesis.",
    "ACE inhibitors may cause cough through increased bradykinin.",
    "Minimal change disease is the most common cause of nephrotic syndrome in children.",
    "Aortic regurgitation classically produces a wide pulse pressure."
)

@Composable
fun MedVaultQuestionScreen(onBack: () -> Unit, dailyMode: Boolean) {
    val todaySeed = remember { java.time.LocalDate.now().toEpochDay().toInt() }
    var index by remember { mutableIntStateOf(Math.floorMod(todaySeed, starterMcqs.size)) }
    var selected by remember { mutableIntStateOf(-1) }
    var answered by remember { mutableStateOf(false) }
    var factIndex by remember { mutableIntStateOf(Math.floorMod(todaySeed, highYieldFacts.size)) }
    var completed by remember { mutableIntStateOf(0) }
    val question = starterMcqs[index]

    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B))))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ShellInk) }
            Column(Modifier.weight(1f)) {
                Text(if (dailyMode) "Daily Dose" else "QBank Practice", color = ShellInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(if (dailyMode) "One question. One useful fact." else "Original exam-style practice questions", color = ShellMuted, fontSize = 11.sp)
            }
            Text("${completed} solved", color = ShellMuted, fontSize = 11.sp)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = HomePanel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(question.subject.uppercase(), color = ShellBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("MCQ ${index + 1}/${starterMcqs.size}", color = ShellMuted, fontSize = 11.sp)
                    }
                    Text(question.question, color = ShellInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    question.options.forEachIndexed { optionIndex, option ->
                        val isCorrect = answered && optionIndex == question.answerIndex
                        val isWrongSelection = answered && selected == optionIndex && optionIndex != question.answerIndex
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !answered) { selected = optionIndex },
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                isCorrect -> Color(0xFF173B31)
                                isWrongSelection -> Color(0xFF422A31)
                                selected == optionIndex -> HomePanelAlt
                                else -> ShellBackground
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, when {
                                isCorrect -> Color(0xFF36A269)
                                isWrongSelection -> Color(0xFFFF8F87)
                                selected == optionIndex -> ShellBlue
                                else -> HomeBorder
                            })
                        ) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${'A' + optionIndex}.", color = ShellMuted, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                Text(option, color = ShellInk, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                if (isCorrect) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF6ED6A0))
                                if (isWrongSelection) Icon(Icons.Default.Cancel, null, tint = Color(0xFFD85B55))
                            }
                        }
                    }
                    if (answered) {
                        Text(if (selected == question.answerIndex) "Correct" else "Incorrect · Correct answer: ${question.options[question.answerIndex]}", color = if (selected == question.answerIndex) Color(0xFF26965C) else Color(0xFFD85B55), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(question.explanation, color = ShellMuted, fontSize = 13.sp)
                    } else {
                        Button(onClick = { if (selected >= 0) { answered = true; completed++ } }, enabled = selected >= 0, modifier = Modifier.fillMaxWidth()) { Text("Check answer") }
                    }
                    if (answered) {
                        OutlinedButton(onClick = {
                            index = (index + 1) % starterMcqs.size
                            selected = -1
                            answered = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Next question") }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = HomePanelAlt), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("HIGH-YIELD FACT", color = ShellBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(highYieldFacts[factIndex], color = ShellInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { factIndex = (factIndex + 1) % highYieldFacts.size }, contentPadding = PaddingValues(0.dp)) { Text("Another fact") }
                }
            }
            Text("These are original practice questions, not claimed previous-year questions.", color = ShellMuted, fontSize = 10.sp)
        }
    }
}
