package com.medvault.app

import android.content.Intent
import android.net.Uri
import android.util.Xml
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

private data class MedicalNewsItem(
    val title: String,
    val url: String,
    val summary: String,
    val published: String,
    val source: String
)

private object MedicalUpdatesRepository {
    private val feeds = listOf(
        "Medical Dialogues" to "https://medicaldialogues.in/feed/",
        "WHO" to "https://www.who.int/rss-feeds/news-english.xml",
        "PubMed / NLM" to "https://www.nlm.nih.gov/rss/auto/PubMedNews.rss"
    )

    suspend fun fetch(): List<MedicalNewsItem> = withContext(Dispatchers.IO) {
        feeds.flatMap { (source, address) ->
            runCatching { parseFeed(source, address) }.getOrDefault(emptyList())
        }.distinctBy { it.url }.sortedByDescending { it.published }.take(60)
    }

    private fun parseFeed(source: String, address: String): List<MedicalNewsItem> {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "MedVault/1.0 (medical education reader)")
        try {
            connection.inputStream.use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                val items = mutableListOf<MedicalNewsItem>()
                var event = parser.eventType
                var insideItem = false
                var tag = ""
                var title = ""
                var link = ""
                var description = ""
                var date = ""
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            tag = parser.name.lowercase()
                            if (tag == "item" || tag == "entry") {
                                insideItem = true
                                title = ""; link = ""; description = ""; date = ""
                            } else if (insideItem && tag == "link" && parser.getAttributeValue(null, "href") != null) {
                                link = parser.getAttributeValue(null, "href").orEmpty()
                            }
                        }
                        XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                            if (insideItem) {
                                val value = parser.text.orEmpty().trim()
                                when (tag) {
                                    "title" -> title += value
                                    "link" -> link += value
                                    "description", "summary", "content:encoded" -> description += value
                                    "pubdate", "published", "updated", "dc:date" -> date += value
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            val endTag = parser.name.lowercase()
                            if (insideItem && (endTag == "item" || endTag == "entry")) {
                                if (title.isNotBlank() && link.startsWith("http")) {
                                    items += MedicalNewsItem(
                                        title = title.cleanMarkup(),
                                        url = link.trim(),
                                        summary = description.cleanMarkup().take(260),
                                        published = date.trim(),
                                        source = source
                                    )
                                }
                                insideItem = false
                            }
                            tag = ""
                        }
                    }
                    event = parser.next()
                }
                return items
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun String.cleanMarkup(): String =
        replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()
}

@Composable
fun MedicalUpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var news by remember { mutableStateOf<List<MedicalNewsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        error = null
        val result = runCatching { MedicalUpdatesRepository.fetch() }
        result.onSuccess { news = it }
            .onFailure { error = it.message ?: "Could not load medical updates." }
        loading = false
        if (news.isEmpty() && error == null) error = "No feed items were returned. Check your connection or refresh."
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(Color(0xFFF6F8FB))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color(0xFF252A3A)) }
            Column(Modifier.weight(1f)) {
                Text("Medical Updates", color = Color(0xFF252A3A), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Medical news and research headlines", color = Color(0xFF81889A), fontSize = 11.sp)
            }
            IconButton(enabled = !loading, onClick = { scope.launch { refresh() } }) {
                Icon(Icons.Default.Refresh, "Refresh", tint = Color(0xFF168FE5))
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("OFFICIAL EXAM PORTALS", color = Color(0xFF81889A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("NBEMS" to "https://natboard.edu.in/", "NMC" to "https://nmc.org.in/whats-new", "MCC" to "https://mcc.nic.in/").forEach { (name, url) ->
                    AssistChip(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, label = { Text(name) })
                }
            }
        }
        if (loading && news.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            error?.let { Text(it, color = Color(0xFFB54747), fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(news, key = { it.url }) { item ->
                    Card(
                        Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) },
                        shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.source.uppercase(), color = Color(0xFF168FE5), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.OpenInNew, null, tint = Color(0xFF81889A), modifier = Modifier.size(15.dp))
                            }
                            Text(item.title, color = Color(0xFF252A3A), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            if (item.summary.isNotBlank()) Text(item.summary, color = Color(0xFF687084), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (item.published.isNotBlank()) Text(item.published, color = Color(0xFF9299A8), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (loading && news.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
