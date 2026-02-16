package com.example.fireremoteplayer.player

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
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
import kotlinx.coroutines.withContext
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
    val volume: Float = 1f,
    val controlUrl: String = "",
    val controlPin: String = REMOTE_PIN
)

class PlayerViewModel(application: Application) : AndroidViewModel(application), RemoteCommandHandler {
    val player: ExoPlayer = ExoPlayer.Builder(
        application,
        DefaultRenderersFactory(application).setEnableDecoderFallback(true)
    ).build()

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
                lastCommand = state.lastCommand,
                volume = state.volume
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

            override fun onPlayerError(error: PlaybackException) {
                _uiState.value = _uiState.value.copy(
                    lastCommand = "Playback error: ${error.errorCodeName}"
                )
            }
        })

        positionTracker = viewModelScope.launch {
            while (true) {
                _uiState.value = _uiState.value.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                    isPlaying = player.isPlaying,
                    volume = player.volume
                )
                delay(500)
            }
        }

        remoteServer.start()
    }

    fun loadStream(url: String, autoPlay: Boolean = true) {
        if (url.isBlank()) return
        val mediaItem = MediaItem.fromUri(Uri.parse(url.trim()))
        playMediaItem(
            mediaItem = mediaItem,
            sourceLabel = url.trim(),
            commandLabel = if (autoPlay) "Load + Play" else "Load",
            autoPlay = autoPlay
        )
    }

    private fun playMediaItem(
        mediaItem: MediaItem,
        sourceLabel: String,
        commandLabel: String,
        autoPlay: Boolean
    ) {
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = autoPlay
        _uiState.value = _uiState.value.copy(
            streamUrl = sourceLabel,
            lastCommand = commandLabel
        )
    }

    fun loadLocalUri(uri: Uri, autoPlay: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val resolver = app.contentResolver

                val displayName = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
                }

                val mimeType = resolver.getType(uri)
                val extension = displayName
                    ?.substringAfterLast('.', missingDelimiterValue = "")
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?: when (mimeType) {
                        "video/mp4" -> "mp4"
                        "video/x-matroska" -> "mkv"
                        "video/x-msvideo" -> "avi"
                        "application/x-mpegURL", "application/vnd.apple.mpegurl" -> "m3u8"
                        else -> "bin"
                    }

                val resolvedMimeType = mimeType ?: when (extension) {
                    "mp4", "m4v", "mk4" -> "video/mp4"
                    "mkv" -> "video/x-matroska"
                    "avi" -> "video/x-msvideo"
                    "m3u8" -> "application/x-mpegURL"
                    else -> null
                }

                val sourceLabel = displayName ?: uri.toString()
                Triple(uri, sourceLabel, resolvedMimeType)
            }.onSuccess { (playUri, sourceLabel, mimeType) ->
                withContext(Dispatchers.Main.immediate) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(playUri)
                        .apply {
                            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                        }
                        .build()

                    playMediaItem(
                        mediaItem = mediaItem,
                        sourceLabel = sourceLabel,
                        commandLabel = "Load Local File",
                        autoPlay = autoPlay
                    )
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main.immediate) {
                    _uiState.value = _uiState.value.copy(
                        lastCommand = "Local file error: ${error.message ?: "unknown"}"
                    )
                }
            }
        }
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

    fun setVolumeLevel(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
        _uiState.value = _uiState.value.copy(
            volume = player.volume,
            lastCommand = "Volume ${(player.volume * 100).toInt()}%"
        )
    }

    fun adjustVolume(delta: Float) {
        setVolumeLevel(player.volume + delta)
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

    override fun setVolume(volume: Float) {
        runOnMain { setVolumeLevel(volume) }
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
