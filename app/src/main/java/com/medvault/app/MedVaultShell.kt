package com.medvault.app

import androidx.compose.foundation.background
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
                    Text("Lectures and revision in one place.", color = ShellMuted, fontSize = 13.sp)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("STUDY HUB", color = ShellMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudyHubCard("Daily Dose", "MCQs and high-yield facts", Icons.Default.FlashOn, onDailyDose, Modifier.weight(1f))
                        StudyHubCard("Medical Updates", "Exams and medical news", Icons.Default.Article, onUpdates, Modifier.weight(1f))
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
            Triple("tests", "Notes", Icons.Default.Assignment),
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
                                isCorrect -> Color(0xFFE5F6EC)
                                isWrongSelection -> Color(0xFFFFE9E7)
                                selected == optionIndex -> Color(0xFFE8F2FF)
                                else -> ShellBackground
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, when {
                                isCorrect -> Color(0xFF36A269)
                                isWrongSelection -> Color(0xFFD85B55)
                                selected == optionIndex -> ShellBlue
                                else -> Color(0xFFE7EAF0)
                            })
                        ) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${'A' + optionIndex}.", color = ShellMuted, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                Text(option, color = ShellInk, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                if (isCorrect) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF26965C))
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
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4FF)), shape = RoundedCornerShape(16.dp)) {
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
