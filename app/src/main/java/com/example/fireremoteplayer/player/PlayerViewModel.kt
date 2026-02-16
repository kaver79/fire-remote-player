package com.example.fireremoteplayer.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.fireremoteplayer.remote.HttpRemoteServer
import com.example.fireremoteplayer.remote.PlayerStatus
import com.example.fireremoteplayer.remote.RemoteCommandHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

private const val REMOTE_PORT = 8080
private const val REMOTE_PIN = "2468"

data class UiState(
    val streamUrl: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastCommand: String = "Idle",
    val controlUrl: String = "",
    val controlPin: String = REMOTE_PIN
)

class PlayerViewModel(application: Application) : AndroidViewModel(application), RemoteCommandHandler {
    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _uiState = MutableStateFlow(
        UiState(
            controlUrl = "http://${resolveTabletIpAddress()}:$REMOTE_PORT",
            controlPin = REMOTE_PIN
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val positionTracker: Job
    private val remoteServer = HttpRemoteServer(
        port = REMOTE_PORT,
        handler = this,
        statusProvider = {
            val state = _uiState.value
            PlayerStatus(
                streamUrl = state.streamUrl,
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                lastCommand = state.lastCommand
            )
        },
        pinProvider = { REMOTE_PIN }
    )

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val duration = if (player.duration > 0) player.duration else 0L
                _uiState.value = _uiState.value.copy(durationMs = duration)
            }
        })

        positionTracker = viewModelScope.launch {
            while (true) {
                _uiState.value = _uiState.value.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                    isPlaying = player.isPlaying
                )
                delay(500)
            }
        }

        remoteServer.start()
    }

    fun loadStream(url: String, autoPlay: Boolean = true) {
        if (url.isBlank()) return
        val mediaItem = MediaItem.fromUri(Uri.parse(url.trim()))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = autoPlay
        _uiState.value = _uiState.value.copy(
            streamUrl = url.trim(),
            lastCommand = if (autoPlay) "Load + Play" else "Load"
        )
    }

    fun playPlayback() {
        player.play()
        _uiState.value = _uiState.value.copy(lastCommand = "Play")
    }

    fun pausePlayback() {
        player.pause()
        _uiState.value = _uiState.value.copy(lastCommand = "Pause")
    }

    fun stopPlayback() {
        player.stop()
        _uiState.value = _uiState.value.copy(lastCommand = "Stop")
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        _uiState.value = _uiState.value.copy(lastCommand = "Seek")
    }

    override fun load(url: String, autoPlay: Boolean) {
        runOnMain { loadStream(url, autoPlay) }
    }

    override fun play() {
        runOnMain { playPlayback() }
    }

    override fun pause() {
        runOnMain { pausePlayback() }
    }

    override fun stop() {
        runOnMain { stopPlayback() }
    }

    override fun seek(positionMs: Long) {
        runOnMain { seekTo(positionMs) }
    }

    private fun runOnMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            block()
        }
    }

    private fun resolveTabletIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                        address is Inet4Address &&
                        address.hostAddress?.startsWith("169.254") == false
                }
                ?.hostAddress ?: "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    override fun onCleared() {
        positionTracker.cancel()
        remoteServer.stop()
        player.release()
        super.onCleared()
    }
}
