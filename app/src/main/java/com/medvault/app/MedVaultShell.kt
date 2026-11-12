package com.medvault.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val ShellBackground = Color(0xFF0B1020)
private val ShellInk = Color(0xFFF3F6FC)
private val ShellMuted = Color(0xFF9EABC0)
private val ShellBlue = Color(0xFF78AFFF)
private val HomeBackground = Color(0xFF0B1020)
private val HomeInk = Color(0xFFF3F6FC)
private val HomeMuted = Color(0xFF9EABC0)
private val HomeBlue = Color(0xFF78AFFF)
private val HomePanel = Color(0xFF151E30)
private val HomePanelAlt = Color(0xFF1B2B40)
private val HomeBorder = Color(0xFF29384D)

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
                    scope.launch {
                        animate(pullPx, with(density) { 62.dp.toPx() }, animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f)) { value, _ -> pullPx = value }
                    }
                } else {
                    scope.launch {
                        animate(pullPx, 0f, animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f)) { value, _ -> pullPx = value }
                    }
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
    Box(Modifier.fillMaxSize().background(HomeBackground).nestedScroll(refreshConnection)) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("MedVault", color = HomeInk, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("Everything under one vault.", color = HomeMuted, fontSize = 12.sp)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.AccountCircle, "Profile and settings", tint = HomeMuted, modifier = Modifier.size(29.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                listOf(MedVaultEdition.M, MedVaultEdition.P, MedVaultEdition.U).forEach { edition ->
                    val selected = activeEdition == edition
                    Surface(
                        modifier = Modifier.clickable { onEditionChange(edition) },
                        color = if (selected) HomePanelAlt else HomePanel,
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) HomeBlue else HomeBorder)
                    ) {
                        Text(
                            "Edition " + edition.name,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            color = if (selected) HomeBlue else HomeMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("DAILY LEARNING", "Your MBBS learning feed")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = HomePanelAlt)
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HomeTag("TODAY'S CONCEPT", HomeBlue)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.AutoStories, null, tint = HomeBlue)
                        }
                        Text("A new MBBS concept, every day", color = HomeInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "High-yield topics selected around undergraduate medical subjects.",
                            color = HomeMuted, fontSize = 13.sp, lineHeight = 19.sp
                        )
                        Text("DAILY CONCEPTS WILL BE ADDED IN THE LEARNING PHASE", color = HomeBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeActionTile(
                        title = "Flashcards",
                        subtitle = "Quick recall",
                        icon = Icons.Default.Style,
                        onClick = onDailyDose,
                        modifier = Modifier.weight(1f)
                    )
                    HomeActionTile(
                        title = "Daily MCQ",
                        subtitle = "Test yourself",
                        icon = Icons.Default.Psychology,
                        onClick = onDailyDose,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("CLINICAL CORNER", "Think through a clinical case")
                HomeEditorialCard(
                    eyebrow = "CASE-BASED LEARNING",
                    title = "Clinical cases and diagnostic reasoning",
                    subtitle = "Patient presentations, important clues and clinical explanations.",
                    icon = Icons.Default.MonitorHeart,
                    onClick = null,
                    footnote = "COMING IN THE LEARNING PHASE"
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("EXAM RADAR", "Important dates and notices")
                HomeEditorialCard(
                    eyebrow = "ACADEMIC CALENDAR",
                    title = "Keep track of what is coming up",
                    subtitle = "University exams, internal assessments, entrance exams and counselling deadlines.",
                    icon = Icons.Default.EventNote,
                    onClick = onUpdates,
                    footnote = "OPEN UPDATES"
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("NEET PG UPDATES", "Entrance examination and counselling")
                HomeEditorialCard(
                    eyebrow = "OFFICIAL SOURCES",
                    title = "NBEMS · MCC · NMC",
                    subtitle = "Examination notifications, information bulletins and counselling announcements.",
                    icon = Icons.Default.Assignment,
                    onClick = onUpdates,
                    footnote = "VIEW MEDICAL UPDATES"
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("MBBS UPDATES", "Undergraduate medical education")
                HomeEditorialCard(
                    eyebrow = "ACADEMIC NOTICES",
                    title = "University and medical college updates",
                    subtitle = "Results, examination schedules, regulations and important academic announcements.",
                    icon = Icons.Default.School,
                    onClick = onUpdates,
                    footnote = "VIEW MEDICAL UPDATES"
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("TRENDING IN MEDICINE", "Developments worth following")
                HomeEditorialCard(
                    eyebrow = "RESEARCH · CLINICAL · TECHNOLOGY",
                    title = "What's changing in medicine?",
                    subtitle = "Emerging treatments, important studies and developments in medical technology.",
                    icon = Icons.Default.TrendingUp,
                    onClick = onUpdates,
                    footnote = "EXPLORE MEDICAL UPDATES"
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeSectionHeading("MEDICAL NEWS BULLETIN", "India first, with major global developments")
                HomeEditorialCard(
                    eyebrow = "HEALTHCARE NEWS",
                    title = "The latest in healthcare and research",
                    subtitle = "Public health, Indian healthcare policy, medical research and major international developments.",
                    icon = Icons.Default.Newspaper,
                    onClick = onUpdates,
                    footnote = "READ MEDICAL NEWS"
                )
            }
        }
    }
    if (pullPx > 1f || refreshRequested || showRefreshResult) {
        Canvas(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (pullPx - with(density) { 42.dp.toPx() }).toInt()) }
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
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HomePanel)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = HomeBlue, modifier = Modifier.size(23.dp))
            Text(title, color = HomeInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
    Card(
        modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth(),
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

@Composable
fun MedVaultBottomBar(
    selected: String,
    onHome: () -> Unit,
    onVideos: () -> Unit,
    onQBank: () -> Unit,
    onTests: () -> Unit,
    onOffline: () -> Unit
) {
    NavigationBar(containerColor = Color(0xFF111A2A), contentColor = HomeInk, tonalElevation = 0.dp) {
        val items = listOf(
            Triple("home", "Home", Icons.Default.Home),
            Triple("videos", "Lectures", Icons.Default.PlayCircle),
            Triple("qbank", "QBank", Icons.Default.MenuBook),
            Triple("tests", "Notes", Icons.Default.Assignment),
            Triple("offline", "Downloads", Icons.Default.Download)
        )
        val actions = listOf(onHome, onVideos, onQBank, onTests, onOffline)
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selected == item.first,
                onClick = actions[index],
                icon = { Icon(item.third, contentDescription = item.second) },
                label = { Text(item.second, fontSize = 10.sp) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = HomeBlue,
                    selectedTextColor = HomeBlue,
                    indicatorColor = HomePanelAlt,
                    unselectedIconColor = HomeMuted,
                    unselectedTextColor = HomeMuted
                )
            )
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
    Column(Modifier.fillMaxSize().background(HomeBackground)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ShellInk) }
            Text("Offline library", color = ShellInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Text("DOWNLOADED VIDEOS", color = ShellMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            items(downloadedLectures, key = { it.fileId }) { lecture ->
                Card(Modifier.fillMaxWidth().clickable { onPlayOffline(lecture) }, colors = CardDefaults.cardColors(containerColor = HomePanel)) {
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
                Card(Modifier.fillMaxWidth().clickable { onOpenPdf(file, file.nameWithoutExtension) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
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

    Column(Modifier.fillMaxSize().background(ShellBackground).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = ShellInk) }
            Text("Profile & Settings", color = ShellInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountCircle, null, tint = ShellBlue, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayName, color = ShellInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Medical student", color = ShellMuted, fontSize = 12.sp)
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
        Card(Modifier.fillMaxWidth().clickable(onClick = onConnectAccount), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBox, null, tint = ShellBlue, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Content account", color = ShellInk, fontWeight = FontWeight.SemiBold)
                    Text("Connect or manage your study content account", color = ShellMuted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = ShellMuted)
            }
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onStudySources), colors = CardDefaults.cardColors(containerColor = Color.White)) {
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
        Card(Modifier.fillMaxWidth().clickable { showAbout = !showAbout }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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

    Column(Modifier.fillMaxSize().background(ShellBackground)) {
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
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
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
