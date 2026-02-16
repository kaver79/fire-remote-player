package com.example.fireremoteplayer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.example.fireremoteplayer.player.PlayerViewModel

@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    onFullscreenChanged: (Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var streamUrl by remember { mutableStateOf("") }
    var isFullscreen by remember { mutableStateOf(false) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not grant persistable permissions.
            }
            viewModel.loadLocalUri(uri, autoPlay = true)
        }
    }

    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
        onFullscreenChanged(false)
    }

    if (isFullscreen) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            player = viewModel.player
                            useController = true
                        }
                    }
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.pausePlayback() },
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Text("Pause")
                    }
                    Button(onClick = {
                        isFullscreen = false
                        onFullscreenChanged(false)
                    }) {
                        Text("Exit Fullscreen")
                    }
                }
            }
        }
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Fire Remote Player", style = MaterialTheme.typography.headlineMedium)
            Text("Open this on your phone browser: ${state.controlUrl}")
            Text("PIN: ${state.controlPin}")

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    PlayerView(context).apply {
                        player = viewModel.player
                        useController = true
                    }
                }
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = streamUrl,
                onValueChange = { streamUrl = it },
                label = { Text("Stream URL") },
                placeholder = { Text("https://example.com/live.m3u8") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.loadStream(streamUrl, autoPlay = true)
                }) {
                    Text("Load & Play")
                }
                Button(onClick = {
                    openDocumentLauncher.launch(createLocalFileIntent(context))
                }) {
                    Text("Open File")
                }
                Button(onClick = { viewModel.pausePlayback() }) {
                    Text("Pause")
                }
                Button(onClick = { viewModel.stopPlayback() }) {
                    Text("Stop")
                }
                Button(onClick = { viewModel.adjustVolume(-0.1f) }) {
                    Text("Vol-")
                }
                Button(onClick = { viewModel.adjustVolume(0.1f) }) {
                    Text("Vol+")
                }
                Button(onClick = { viewModel.setVolumeLevel(0f) }) {
                    Text("Mute")
                }
                Button(onClick = {
                    isFullscreen = true
                    onFullscreenChanged(true)
                }) {
                    Text("Fullscreen")
                }
            }

            Text(
                "Now: ${state.lastCommand} | Playing: ${state.isPlaying} | Position: ${state.positionMs / 1000}s | Volume: ${(state.volume * 100).toInt()}%"
            )
        }
    }
}

private fun createLocalFileIntent(context: android.content.Context): Intent {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("video/*", "application/x-mpegURL", "application/vnd.apple.mpegurl")
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val roots = listOf("primary", "home", "0123-4567", "0000-0000")
        val dirs = listOf("Mults", "Video", "Movies")

        val initialUri = roots
            .flatMap { root -> dirs.map { dir -> "$root:$dir" } }
            .map { docId ->
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    docId
                )
            }
            .firstOrNull { uri ->
                runCatching {
                    context.contentResolver.query(uri, arrayOf("document_id"), null, null, null)
                        ?.use { true } ?: false
                }.getOrDefault(false)
            }

        if (initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
    }

    return intent
}
