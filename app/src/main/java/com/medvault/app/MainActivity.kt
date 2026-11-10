
package com.medvault.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private val Background = Color(0xFF0B1020)
private val Panel = Color(0xFF151E30)
private val Accent = Color(0xFF78AFFF)

private const val DRIVE_SCOPE =
    "https://www.googleapis.com/auth/drive.readonly"

private const val AUTH_REQUEST = 7001
private const val FOLDER_MIME =
    "application/vnd.google-apps.folder"

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String
)

data class FolderLocation(
    val id: String,
    val name: String
)

class MainActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private var accessToken: String? = null

    private val connected = mutableStateOf(false)
    private val loading = mutableStateOf(false)
    private val status = mutableStateOf("Connect Google Drive to begin.")

    private val driveItems = mutableStateListOf<DriveItem>()
    private val folderStack = mutableStateListOf<FolderLocation>()

    private val currentFolderId = mutableStateOf("root")
    private val currentFolderName = mutableStateOf("My Drive")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Background,
                    surface = Panel,
                    primary = Accent
                )
            ) {
                MedVaultHome(
                    connected = connected.value,
                    loading = loading.value,
                    status = status.value,
                    items = driveItems.toList(),
                    folderName = currentFolderName.value,
                    canGoBack = folderStack.isNotEmpty(),
                    onConnect = { connectGoogleDrive() },
                    onBrowse = { browseDrive() },
                    onBack = { goBack() },
                    onFolderClick = { openFolder(it) },
                    onFileClick = { openFile(it) }
                )
            }
        }
    }

    private fun connectGoogleDrive() {
        status.value = "Requesting Google authorization..."

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(Scope(DRIVE_SCOPE))
            )
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->

                if (result.hasResolution()) {
                    try {
                        val pendingIntent = result.pendingIntent
                            ?: throw IllegalStateException(
                                "Authorization resolution unavailable"
                            )

                        startIntentSenderForResult(
                            pendingIntent.intentSender,
                            AUTH_REQUEST,
                            null,
                            0,
                            0,
                            0
                        )
                    } catch (e: Exception) {
                        status.value =
                            "Authorization error: ${e.message}"
                    }
                } else {
                    handleAuthorization(result.accessToken)
                }
            }
            .addOnFailureListener { error ->
                status.value =
                    "Google authorization failed: ${error.message}"
            }
    }

    @Deprecated("Uses Google's authorization resolution flow")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == AUTH_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    val result =
                        Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(data)

                    handleAuthorization(result.accessToken)
                } catch (e: Exception) {
                    status.value =
                        "Authorization failed: ${e.message}"
                }
            } else {
                status.value = "Google authorization cancelled."
            }
        }
    }

    private fun handleAuthorization(token: String?) {
        if (token.isNullOrBlank()) {
            status.value = "Google did not return an access token."
            return
        }

        accessToken = token
        connected.value = true
        status.value = "Google Drive connected."

        currentFolderId.value = "root"
        currentFolderName.value = "My Drive"
        folderStack.clear()

        loadDriveFolder("root")
    }

    private fun browseDrive() {
        if (accessToken == null) {
            connectGoogleDrive()
        } else {
            loadDriveFolder(currentFolderId.value)
        }
    }

    private fun loadDriveFolder(folderId: String) {
        val token = accessToken ?: return

        loading.value = true
        status.value = "Loading ${currentFolderName.value}..."

        executor.execute {
            try {
                val query = "'$folderId' in parents and trashed=false"

                val uri = Uri.parse(
                    "https://www.googleapis.com/drive/v3/files"
                ).buildUpon()
                    .appendQueryParameter("q", query)
                    .appendQueryParameter(
                        "fields",
                        "files(id,name,mimeType,size)"
                    )
                    .appendQueryParameter("pageSize", "100")
                    .appendQueryParameter("orderBy", "folder,name")
                    .build()

                val connection =
                    URL(uri.toString()).openConnection()
                        as HttpURLConnection

                connection.requestMethod = "GET"
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val responseCode = connection.responseCode

                if (responseCode != 200) {
                    val error = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: "HTTP $responseCode"

                    throw Exception(error)
                }

                val response = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()

                val json = JSONObject(response)
                val files = json.optJSONArray("files")

                val result = mutableListOf<DriveItem>()

                if (files != null) {
                    for (i in 0 until files.length()) {
                        val item = files.getJSONObject(i)

                        result.add(
                            DriveItem(
                                id = item.getString("id"),
                                name = item.getString("name"),
                                mimeType = item.getString("mimeType")
                            )
                        )
                    }
                }

                runOnUiThread {
                    driveItems.clear()
                    driveItems.addAll(result)
                    loading.value = false
                    status.value =
                        "${result.size} items found."
                }

            } catch (e: Exception) {
                runOnUiThread {
                    loading.value = false
                    status.value =
                        "Drive error: ${e.message}"
                }
            }
        }
    }

    private fun openFolder(folder: DriveItem) {
        folderStack.add(
            FolderLocation(
                currentFolderId.value,
                currentFolderName.value
            )
        )

        currentFolderId.value = folder.id
        currentFolderName.value = folder.name

        loadDriveFolder(folder.id)
    }

    private fun goBack() {
        if (folderStack.isNotEmpty()) {
            val previous = folderStack.removeAt(
                folderStack.lastIndex
            )

            currentFolderId.value = previous.id
            currentFolderName.value = previous.name

            loadDriveFolder(previous.id)
        }
    }

    private fun openFile(file: DriveItem) {
        Toast.makeText(
            this,
            "Selected: ${file.name}. Video playback is next.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

@Composable
fun MedVaultHome(
    connected: Boolean,
    loading: Boolean,
    status: String,
    items: List<DriveItem>,
    folderName: String,
    canGoBack: Boolean,
    onConnect: () -> Unit,
    onBrowse: () -> Unit,
    onBack: () -> Unit,
    onFolderClick: (DriveItem) -> Unit,
    onFileClick: (DriveItem) -> Unit
) {
    BackHandler(enabled = connected && canGoBack) {
        onBack()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "MEDVAULT",
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        "Your study library",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    Icons.Default.Cloud,
                    contentDescription = "Cloud",
                    tint = Accent
                )
            }

            Spacer(Modifier.height(20.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "YOUR NEET PG LIBRARY",
                        color = Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        if (connected) "Google Drive connected."
                        else "Pick up where you left off.",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        status,
                        color = Color(0xFF9EABC0),
                        fontSize = 13.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (connected) "Reconnect Google Drive"
                            else "Connect Google Drive"
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (connected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Accent
                            )
                        }
                    }

                    Text(
                        folderName,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = onBrowse,
                        enabled = !loading
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Accent
                        )
                    }
                }

                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        val isFolder =
                            item.mimeType == FOLDER_MIME

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isFolder) {
                                        onFolderClick(item)
                                    } else {
                                        onFileClick(item)
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = Panel
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isFolder)
                                        Icons.Default.Folder
                                    else
                                        Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = Accent
                                )

                                Text(
                                    item.name,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 14.dp),
                                    fontWeight = FontWeight.Medium
                                )

                                Icon(
                                    if (isFolder)
                                        Icons.Default.ChevronRight
                                    else
                                        Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = Color.Gray
                                )
                            }
                        }
                    }

                    if (!loading && items.isEmpty()) {
                        item {
                            Text(
                                "This folder is empty.",
                                color = Color.Gray,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    "YOUR LIBRARY",
                    color = Color(0xFF9EABC0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                Spacer(Modifier.height(12.dp))

                Feature(
                    Icons.Default.Folder,
                    "Browse subjects",
                    "Explore your Drive folders",
                    onBrowse
                )

                Spacer(Modifier.height(10.dp))

                Feature(
                    Icons.Default.PlayCircle,
                    "Continue watching",
                    "Video playback will be added next",
                    {}
                )

                Spacer(Modifier.height(10.dp))

                Feature(
                    Icons.Default.Search,
                    "Search lectures",
                    "Search will be added next",
                    {}
                )
            }
        }
    }
}

@Composable
fun Feature(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Panel
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Accent
            )

            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    subtitle,
                    color = Color(0xFF9EABC0),
                    fontSize = 12.sp
                )
            }
        }
    }
}
