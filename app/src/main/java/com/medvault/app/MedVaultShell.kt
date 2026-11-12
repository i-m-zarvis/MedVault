package com.medvault.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val ShellBackground = Color(0xFFF6F8FB)
private val ShellInk = Color(0xFF252A3A)
private val ShellMuted = Color(0xFF81889A)
private val ShellBlue = Color(0xFF168FE5)

@Composable
fun MedVaultHomeScreen(
    onVideos: () -> Unit,
    onQBank: () -> Unit,
    onTests: () -> Unit,
    onDailyDose: () -> Unit,
    onUpdates: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(ShellBackground)) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = ShellBlue, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MedicalServices, null, tint = Color.White, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("MedVault", color = ShellInk, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.AccountCircle, "Profile and settings", tint = ShellMuted, modifier = Modifier.size(32.dp))
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("YOUR MEDICAL WORKSPACE", color = ShellMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("What are we studying today?", color = ShellInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Lectures, question banks and exam practice in one place.", color = ShellMuted, fontSize = 13.sp)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("STUDY HUB", color = ShellMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudyHubCard("QBank", "Question collections", Icons.Default.MenuBook, onQBank, Modifier.weight(1f))
                        StudyHubCard("Tests", "Test papers", Icons.Default.Assignment, onTests, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudyHubCard("Daily Dose", "MCQs and high-yield facts", Icons.Default.Bolt, onDailyDose, Modifier.weight(1f))
                        StudyHubCard("Medical Updates", "Exams and medical news", Icons.Default.Newspaper, onUpdates, Modifier.weight(1f))
                    }
                }
            }
            item {
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onVideos),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF258FE8))
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.18f), modifier = Modifier.size(58.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(38.dp))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Video Library", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("Continue with your lectures", color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.White)
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("DAILY REVISION", color = ShellMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("A short question every time you open MedVault.", color = ShellInk, fontWeight = FontWeight.SemiBold)
                        Text("Daily Dose will contain randomized MCQs, explanations and clinical pearls.", color = ShellMuted, fontSize = 12.sp)
                        TextButton(onClick = onDailyDose, contentPadding = PaddingValues(0.dp)) { Text("Open Daily Dose") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyHubCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(11.dp), color = Color(0xFFE8F4FF), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = ShellBlue) }
            }
            Text(title, color = ShellInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(subtitle, color = ShellMuted, fontSize = 11.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        val items = listOf(
            Triple("home", "Home", Icons.Default.Home),
            Triple("videos", "Videos", Icons.Default.PlayCircle),
            Triple("qbank", "QBank", Icons.Default.MenuBook),
            Triple("tests", "Tests", Icons.Default.Assignment),
            Triple("offline", "Offline", Icons.Default.Download)
        )
        val actions = listOf(onHome, onVideos, onQBank, onTests, onOffline)
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selected == item.first,
                onClick = actions[index],
                icon = { Icon(item.third, contentDescription = item.second) },
                label = { Text(item.second, fontSize = 10.sp) },
                alwaysShowLabel = true
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
    Column(Modifier.fillMaxSize().background(ShellBackground)) {
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
                Card(Modifier.fillMaxWidth().clickable { onPlayOffline(lecture) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
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
