package com.medvault.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ContentCategory(val id: String, val title: String)

private val contentCategories = listOf(
    ContentCategory("all", "All"),
    ContentCategory("exam-radar", "Exam Radar"),
    ContentCategory("neet-pg", "NEET PG"),
    ContentCategory("mbbs-updates", "MBBS"),
    ContentCategory("trending-medicine", "Trending"),
    ContentCategory("medical-news", "Medical News")
)

@Composable
fun MedicalUpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { ContentFeedDatabase.get(context).contentDao() }
    val cachedItems by dao.observeAll().collectAsState(initial = emptyList())
    var selectedCategory by remember { mutableStateOf("all") }

    val visibleItems = remember(cachedItems, selectedCategory) {
        if (selectedCategory == "all") cachedItems
        else cachedItems.filter { it.category == selectedCategory }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B1020))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color(0xFFF3F6FC))
            }
            Column(Modifier.weight(1f)) {
                Text("Medical Updates", color = Color(0xFFF3F6FC), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("India-focused medical and education feed", color = Color(0xFF9EABC0), fontSize = 11.sp)
            }
            IconButton(onClick = { ContentFeedScheduler.refresh(context) }) {
                Icon(Icons.Default.Refresh, "Refresh feeds", tint = Color(0xFF78AFFF))
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            contentCategories.forEach { category ->
                FilterChip(
                    selected = selectedCategory == category.id,
                    onClick = { selectedCategory = category.id },
                    label = { Text(category.title) }
                )
            }
        }

        if (visibleItems.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        if (cachedItems.isEmpty()) "No published updates cached yet." else "No items in this section yet.",
                        color = Color(0xFFF3F6FC),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "MedVault will show verified source metadata here when the content feed publishes it. Pull refresh to check for updates.",
                        color = Color(0xFF9EABC0),
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = { ContentFeedScheduler.refresh(context) }) { Text("Refresh now") }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(visibleItems, key = { "${it.category}:${it.itemId}" }) { item ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                            }
                        },
                        shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151E30))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.sourceName.uppercase(),
                                    color = Color(0xFF78AFFF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Default.OpenInNew, null, tint = Color(0xFF9EABC0), modifier = Modifier.size(15.dp))
                            }
                            Text(item.title, color = Color(0xFFF3F6FC), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            item.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = Color(0xFFB4C0D2), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.tier.uppercase(), color = Color(0xFF9EABC0), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(item.publishedAt ?: "Date not supplied", color = Color(0xFF9EABC0), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Source metadata only · Open the publisher for the original notice or article",
            color = Color(0xFF75839A),
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
