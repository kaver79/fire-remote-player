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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun MainScreen(
    viewModel: PlayerViewModel,
    onFullscreenChanged: (Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var streamUrl by remember { mutableStateOf("") }
    var isFullscreen by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    val remoteFullscreenCommand by viewModel.fullscreenCommand.collectAsStateWithLifecycle()

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

    LaunchedEffect(remoteFullscreenCommand) {
        val requested = remoteFullscreenCommand ?: return@LaunchedEffect
        isFullscreen = requested
        onFullscreenChanged(requested)
        viewModel.consumeFullscreenCommand()
    }

    if (isFullscreen) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                PlayerSurface(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    useVlc = state.useVlc
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactButton(
                        onClick = { viewModel.pausePlayback() },
                        label = "Pause"
                    )
                    CompactButton(onClick = {
                        isFullscreen = false
                        onFullscreenChanged(false)
                    }, label = "Exit Fullscreen")
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
            Text("Control: ${state.controlUrl} | PIN: ${state.controlPin}")

            PlayerSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.25f),
                viewModel = viewModel,
                useVlc = state.useVlc
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactButton(onClick = {
                    showLoadDialog = true
                }, label = "Load & Play")
                CompactButton(onClick = {
                    openDocumentLauncher.launch(createLocalFileIntent())
                }, label = "Open File")
                CompactButton(onClick = { viewModel.pausePlayback() }, label = "Pause")
                CompactButton(onClick = { viewModel.stopPlayback() }, label = "Stop")
                CompactButton(onClick = { viewModel.adjustVolume(-0.1f) }, label = "Vol-")
                CompactButton(onClick = { viewModel.adjustVolume(0.1f) }, label = "Vol+")
                CompactButton(onClick = { viewModel.setVolumeLevel(0f) }, label = "Mute")
                CompactButton(onClick = {
                    isFullscreen = true
                    onFullscreenChanged(true)
                }, label = "Fullscreen")
                CompactButton(onClick = { viewModel.togglePrimeApp() }, label = "Prime Toggle")
                TinyButton(onClick = { viewModel.restartRemoteServerApp() }, label = "Restart Net")
            }

            Text(
                "Now: ${state.lastCommand} | Playing: ${state.isPlaying} | Position: ${state.positionMs / 1000}s | Volume: ${(state.volume * 100).toInt()}%"
            )
        }
    }

    if (showLoadDialog) {
        AlertDialog(
            onDismissRequest = { showLoadDialog = false },
            title = { Text("Load Stream") },
            text = {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    label = { Text("Stream URL") },
                    placeholder = { Text("https://example.com/live.m3u8") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.loadStream(streamUrl, autoPlay = true)
                    showLoadDialog = false
                }) {
                    Text("Load")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLoadDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CompactButton(
    onClick: () -> Unit,
    label: String
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        colors = ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TinyButton(
    onClick: () -> Unit,
    label: String
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        colors = ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun createLocalFileIntent(): Intent {
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
        // Best-effort default folder hint without probing provider URIs (which can throw
        // permission denials on Fire OS before user grants picker access).
        val initialUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Mults"
        )
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }

    return intent
}

@Composable
private fun PlayerSurface(
    modifier: Modifier,
    viewModel: PlayerViewModel,
    useVlc: Boolean
) {
    if (useVlc) {
        var layoutRef by remember { mutableStateOf<VLCVideoLayout?>(null) }
        AndroidView(
            modifier = modifier,
            factory = { context -> VLCVideoLayout(context) },
            update = { layout ->
                layoutRef = layout
                viewModel.bindVlcVideoLayout(layout)
            }
        )
        DisposableEffect(layoutRef) {
            onDispose {
                layoutRef?.let { viewModel.unbindVlcVideoLayout(it) }
            }
        }
    } else {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.player
                    useController = true
                }
            },
            update = { playerView ->
                playerView.player = viewModel.player
            }
        )
    }
}
