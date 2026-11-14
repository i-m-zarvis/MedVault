package com.medvault.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun LiquidBookmarkButton(contentId: String, title: String, contentType: String, sourceEdition: MedVaultEdition) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("medvault_bookmarks", Context.MODE_PRIVATE) }
    var saved by remember(contentId) { mutableStateOf(prefs.contains(contentId)) }
    Box(
        Modifier.size(36.dp)
            .background(Color.White.copy(alpha = if (saved) 0.17f else 0.075f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = if (saved) 0.24f else 0.12f), CircleShape)
            .medVaultClickable {
                saved = !saved
                if (saved) {
                    val parts = contentId.split(":")
                    val payload = JSONObject()
                        .put("type", contentType)
                        .put("title", title)
                        .put("edition", sourceEdition.name)
                        .put("chatId", parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                        .put("topicId", parts.getOrNull(2)?.toIntOrNull() ?: 0)
                        .put("messageId", parts.getOrNull(3)?.toLongOrNull() ?: 0L)
                    prefs.edit().putString(contentId, payload.toString()).apply()
                } else prefs.edit().remove(contentId).apply()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = if (saved) "Remove bookmark" else "Bookmark $contentType",
            tint = if (saved) Color(0xFF9BC4FF) else Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(18.dp)
        )
    }
}
