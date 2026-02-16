package com.example.fireremoteplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import com.example.fireremoteplayer.player.PlayerViewModel

@Composable
fun MainScreen(viewModel: PlayerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var streamUrl by remember { mutableStateOf("") }

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
                Button(onClick = { viewModel.pausePlayback() }) {
                    Text("Pause")
                }
                Button(onClick = { viewModel.stopPlayback() }) {
                    Text("Stop")
                }
            }

            Text(
                "Now: ${state.lastCommand} | Playing: ${state.isPlaying} | Position: ${state.positionMs / 1000}s"
            )
        }
    }
}
