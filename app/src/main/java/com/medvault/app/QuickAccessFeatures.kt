package com.medvault.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val QuickBg = Color(0xFF080B12)
private val QuickInk = Color(0xFFF5F6FA)
private val QuickMuted = Color(0xFF9299AA)
private val QuickBlue = Color(0xFF78AFFF)

@Composable
fun MedVaultBookmarksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("medvault_bookmarks", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    val entries = remember(revision) {
        prefs.all.entries.mapNotNull { entry ->
            val value = entry.value as? String ?: return@mapNotNull null
            val parts = value.split("|", limit = 2)
            BookmarkEntry(entry.key, parts.getOrNull(1).orEmpty().ifBlank { entry.key }, parts.firstOrNull().orEmpty())
        }.sortedBy { it.title.lowercase() }
    }
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), QuickBg, Color(0xFF100B1B)))).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = QuickInk) }
            Column(Modifier.weight(1f)) {
                Text("YOUR LIBRARY", color = QuickBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Bookmarks", color = QuickInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
            Text("${entries.size}", color = QuickMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Default.Bookmark, null, tint = QuickBlue, modifier = Modifier.size(38.dp))
                Text("Nothing bookmarked yet", color = QuickInk, fontWeight = FontWeight.SemiBold)
                Text("Save a lecture or PDF and it will appear here.", color = QuickMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xD91B2940)), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bookmark, null, tint = QuickBlue)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.title, color = QuickInk, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(entry.type.uppercase().ifBlank { "SAVED ITEM" }, color = QuickMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                            }
                            IconButton(onClick = { prefs.edit().remove(entry.id).apply(); revision++ }) {
                                Icon(Icons.Default.DeleteOutline, "Remove bookmark", tint = QuickMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class BookmarkEntry(val id: String, val title: String, val type: String)

private data class PlannerTask(val id: Long, val title: String, val subject: String, val due: String, val done: Boolean)

@Composable
fun MedVaultStudyPlannerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("medvault_study_planner", Context.MODE_PRIVATE) }
    fun loadTasks(): List<PlannerTask> = runCatching {
        val array = JSONArray(prefs.getString("tasks", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PlannerTask(item.optLong("id"), item.optString("title"), item.optString("subject"), item.optString("due"), item.optBoolean("done"))
        }
    }.getOrDefault(emptyList())
    var tasks by remember { mutableStateOf(loadTasks()) }
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    fun persist(updated: List<PlannerTask>) {
        tasks = updated
        val array = JSONArray()
        updated.forEach { task ->
            array.put(JSONObject().put("id", task.id).put("title", task.title).put("subject", task.subject).put("due", task.due).put("done", task.done))
        }
        prefs.edit().putString("tasks", array.toString()).apply()
    }
    val completed = tasks.count { it.done }
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), QuickBg, Color(0xFF100B1B)))).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = QuickInk) }
            Column(Modifier.weight(1f)) {
                Text("STAY ON TRACK", color = QuickBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Study Planner", color = QuickInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = Color(0xD91B2940)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("$completed of ${tasks.size} tasks completed", color = QuickInk, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else completed.toFloat() / tasks.size }, modifier = Modifier.fillMaxWidth(), color = QuickBlue, trackColor = Color.White.copy(alpha = 0.10f))
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xB51A263B)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Add a study task", color = QuickInk, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Topic or task") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = subject, onValueChange = { subject = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Subject") })
                    OutlinedTextField(value = due, onValueChange = { due = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Due date") })
                }
                Button(onClick = {
                    val clean = title.trim()
                    if (clean.isNotEmpty()) {
                        persist(tasks + PlannerTask(System.currentTimeMillis(), clean, subject.trim(), due.trim(), false))
                        title = ""; subject = ""; due = ""
                    }
                }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add task")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (tasks.isEmpty()) {
            Text("Your study tasks will appear here.", color = QuickMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(tasks, key = { it.id }) { task ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xD91B2940)), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = task.done, onCheckedChange = { checked -> persist(tasks.map { if (it.id == task.id) it.copy(done = checked) else it }) })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(task.title, color = if (task.done) QuickMuted else QuickInk, fontWeight = FontWeight.SemiBold)
                                Text(listOf(task.subject, task.due).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No subject or due date" }, color = QuickMuted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { persist(tasks.filterNot { it.id == task.id }) }) { Icon(Icons.Default.DeleteOutline, "Delete task", tint = QuickMuted) }
                        }
                    }
                }
            }
        }
    }
}
