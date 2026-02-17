package com.example.fireremoteplayer.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.example.fireremoteplayer.MainActivity
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

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
    val controlPin: String = REMOTE_PIN,
    val useVlc: Boolean = false
)

class PlayerViewModel(application: Application) : AndroidViewModel(application), RemoteCommandHandler {
    val player: ExoPlayer = ExoPlayer.Builder(
        application,
        DefaultRenderersFactory(application)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    ).build()

    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private var vlcLayout: VLCVideoLayout? = null

    private var currentLocalUri: Uri? = null
    private var currentLocalLabel: String = ""
    private var primeOpenedByApp: Boolean = false
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _uiState = MutableStateFlow(
        UiState(
            controlUrl = "http://${resolveTabletIpAddress()}:$REMOTE_PORT",
            controlPin = REMOTE_PIN
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _fullscreenCommand = MutableStateFlow<Boolean?>(null)
    val fullscreenCommand: StateFlow<Boolean?> = _fullscreenCommand.asStateFlow()

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
                if (_uiState.value.useVlc) return
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (_uiState.value.useVlc) return
                val duration = if (player.duration > 0) player.duration else 0L
                _uiState.value = _uiState.value.copy(durationMs = duration)
            }

            override fun onPlayerError(error: PlaybackException) {
                val decodingError = error.errorCodeName.contains("DECODING")
                val ioError = error.errorCodeName.contains("IO")
                val oomError = containsOutOfMemory(error)
                val details = error.cause?.message?.take(160)?.let { ": $it" }.orEmpty()

                if ((decodingError || oomError || ioError) &&
                    currentLocalUri != null &&
                    !_uiState.value.useVlc
                ) {
                    startVlcFallback(currentLocalUri!!, currentLocalLabel)
                    return
                }

                val hint = if (oomError) {
                    " (out of memory while opening local file)"
                } else if (decodingError) {
                    " (decoder/codec unsupported on this device)"
                } else {
                    ""
                }
                _uiState.value = _uiState.value.copy(
                    lastCommand = "Playback error: ${error.errorCodeName}$hint$details"
                )
            }
        })

        positionTracker = viewModelScope.launch {
            while (true) {
                val state = _uiState.value
                if (state.useVlc) {
                    val mp = vlcPlayer
                    val pos = mp?.time?.coerceAtLeast(0L) ?: 0L
                    val len = mp?.length?.takeIf { it > 0 } ?: 0L
                    val vol = currentSystemVolumeRatio()
                    _uiState.value = state.copy(
                        positionMs = pos,
                        durationMs = len,
                        isPlaying = mp?.isPlaying == true,
                        volume = vol
                    )
                } else {
                    _uiState.value = state.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                        isPlaying = player.isPlaying,
                        volume = player.volume
                    )
                }
                delay(500)
            }
        }

        runCatching {
            remoteServer.start()
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                lastCommand = "Remote server error: ${error.message ?: "port busy"}"
            )
        }
    }

    fun bindVlcVideoLayout(layout: VLCVideoLayout) {
        runOnMain {
            ensureVlcPlayer()
            if (vlcLayout !== layout) {
                vlcPlayer?.detachViews()
                vlcLayout = layout
                vlcPlayer?.attachViews(layout, null, false, false)
            }
        }
    }

    fun unbindVlcVideoLayout(layout: VLCVideoLayout) {
        runOnMain {
            if (vlcLayout === layout) {
                vlcPlayer?.detachViews()
                vlcLayout = null
            }
        }
    }

    fun loadStream(url: String, autoPlay: Boolean = true) {
        if (url.isBlank()) return
        currentLocalUri = null
        currentLocalLabel = ""
        switchToExo()

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
            lastCommand = commandLabel,
            useVlc = false
        )
    }

    fun loadLocalUri(uri: Uri, autoPlay: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main.immediate) {
                _uiState.value = _uiState.value.copy(lastCommand = "Preparing local file...")
            }
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

                val resolvedMimeType = when {
                    mimeType == "application/x-mpegURL" ||
                        mimeType == "application/vnd.apple.mpegurl" ||
                        extension == "m3u8" -> "application/x-mpegURL"
                    else -> null
                }

                val sourceLabel = displayName ?: uri.toString()
                val cachedUri = cacheLocalContentUri(
                    sourceUri = uri,
                    displayName = sourceLabel,
                    extension = extension
                )
                Triple(cachedUri, sourceLabel, resolvedMimeType)
            }.onSuccess { (playUri, sourceLabel, _) ->
                withContext(Dispatchers.Main.immediate) {
                    currentLocalUri = playUri
                    currentLocalLabel = sourceLabel
                    // Local files are routed through VLC to avoid ExoPlayer runtime-check failures
                    // on unsupported/unstable hardware decoder paths (e.g. Xvid/AC3 AVI).
                    startVlcFallback(playUri, sourceLabel)
                    if (!autoPlay) {
                        vlcPlayer?.pause()
                    }
                    _uiState.value = _uiState.value.copy(
                        lastCommand = "Load Local File (VLC)"
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

    private fun cacheLocalContentUri(
        sourceUri: Uri,
        displayName: String,
        extension: String
    ): Uri {
        val app = getApplication<Application>()
        val cacheRoot = File(app.cacheDir, "local_media").apply { mkdirs() }

        // Keep cache bounded to avoid storage pressure from repeated tests.
        cacheRoot.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.delete() }

        val safeName = buildString {
            displayName.forEach { ch ->
                append(
                    if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
                )
            }
        }.ifBlank {
            "local_${System.currentTimeMillis()}.$extension"
        }

        val targetFile = File(cacheRoot, safeName)
        app.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        } ?: error("Unable to read selected file")

        return Uri.fromFile(targetFile)
    }

    private fun ensureVlcPlayer() {
        if (libVlc == null) {
            libVlc = LibVLC(
                getApplication(),
                arrayListOf(
                    "--drop-late-frames",
                    "--skip-frames",
                    "--avcodec-fast"
                )
            )
        }
        if (vlcPlayer == null) {
            vlcPlayer = MediaPlayer(libVlc).also { mp ->
                mp.setEventListener { event ->
                    runOnMain {
                        when (event.type) {
                            MediaPlayer.Event.Playing -> {
                                _uiState.value = _uiState.value.copy(isPlaying = true)
                            }
                            MediaPlayer.Event.Paused,
                            MediaPlayer.Event.Stopped,
                            MediaPlayer.Event.EndReached -> {
                                _uiState.value = _uiState.value.copy(isPlaying = false)
                            }
                            MediaPlayer.Event.EncounteredError -> {
                                _uiState.value = _uiState.value.copy(
                                    lastCommand = "Playback error: VLC decode failed"
                                )
                            }
                        }
                    }
                }
                vlcLayout?.let { layout -> mp.attachViews(layout, null, false, false) }
            }
        }
    }

    private fun startVlcFallback(uri: Uri, sourceLabel: String) {
        runOnMain {
            ensureVlcPlayer()
            player.stop()

            val media = Media(libVlc, uri)
            media.setHWDecoderEnabled(true, false)
            vlcPlayer?.media = media
            media.release()

            vlcPlayer?.play()
            _uiState.value = _uiState.value.copy(
                streamUrl = sourceLabel,
                useVlc = true,
                isPlaying = true,
                lastCommand = "VLC fallback (software decode)"
            )
        }
    }

    private fun switchToExo() {
        vlcPlayer?.stop()
        _uiState.value = _uiState.value.copy(useVlc = false)
    }

    fun playPlayback() {
        if (_uiState.value.useVlc) {
            vlcPlayer?.play()
        } else {
            player.play()
        }
        _uiState.value = _uiState.value.copy(lastCommand = "Play")
    }

    fun pausePlayback() {
        if (_uiState.value.useVlc) {
            vlcPlayer?.pause()
        } else {
            player.pause()
        }
        _uiState.value = _uiState.value.copy(lastCommand = "Pause")
    }

    fun stopPlayback() {
        if (_uiState.value.useVlc) {
            vlcPlayer?.stop()
        } else {
            player.stop()
        }
        _uiState.value = _uiState.value.copy(lastCommand = "Stop", isPlaying = false)
    }

    fun seekTo(positionMs: Long) {
        if (_uiState.value.useVlc) {
            vlcPlayer?.time = positionMs.coerceAtLeast(0L)
        } else {
            player.seekTo(positionMs.coerceAtLeast(0L))
        }
        _uiState.value = _uiState.value.copy(lastCommand = "Seek")
    }

    fun setVolumeLevel(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        setSystemVolumeRatio(clamped)
        if (_uiState.value.useVlc) {
            vlcPlayer?.volume = (clamped * 100).toInt().coerceIn(0, 100)
        } else {
            player.volume = clamped
        }
        _uiState.value = _uiState.value.copy(
            volume = currentSystemVolumeRatio(),
            lastCommand = "Volume ${(currentSystemVolumeRatio() * 100).toInt()}%"
        )
    }

    fun adjustVolume(delta: Float) {
        val current = currentSystemVolumeRatio()
        setVolumeLevel(current + delta)
    }

    fun openPrimeVideoApp() {
        val app = getApplication<Application>()
        val launchIntent = resolvePrimeLaunchIntent()
        if (launchIntent == null) {
            _uiState.value = _uiState.value.copy(
                lastCommand = "Prime Video app not found"
            )
            return
        }

        runCatching {
            app.startActivity(launchIntent)
            primeOpenedByApp = true
            _uiState.value = _uiState.value.copy(
                lastCommand = "Opened Prime Video"
            )
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                lastCommand = "Prime open error: ${error.message ?: "unknown"}"
            )
        }
    }

    fun bringPlayerToForegroundApp() {
        val app = getApplication<Application>()
        runCatching {
            requestExclusiveAudioFocus()
            val intent = Intent(app, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            app.startActivity(intent)
            primeOpenedByApp = false
            _uiState.value = _uiState.value.copy(
                lastCommand = "Player moved to foreground"
            )
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                lastCommand = "Foreground error: ${error.message ?: "unknown"}"
            )
        }
    }

    private fun requestExclusiveAudioFocus() {
        val manager = audioManager ?: return
        val req = audioFocusRequest ?: AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        ).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        ).setOnAudioFocusChangeListener { }.build().also {
            audioFocusRequest = it
        }
        manager.requestAudioFocus(req)
    }

    private fun resolvePrimeLaunchIntent(): Intent? {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val knownPackages = listOf(
            "com.amazon.avod",
            "com.amazon.avod.thirdpartyclient",
            "com.amazon.amazonvideo.livingroom"
        )

        knownPackages.forEach { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                return intent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        // Some Fire OS builds expose Prime via explicit activity only.
        runCatching {
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    "com.amazon.avod",
                    "com.amazon.avod.client.activity.SplashScreenActivity"
                )
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()?.let { explicitIntent ->
            val canHandle = explicitIntent.resolveActivity(pm) != null
            if (canHandle) return explicitIntent
        }
        return null
    }

    fun restartRemoteServerApp() {
        runOnMain {
            runCatching {
                remoteServer.stop()
                remoteServer.start()
                _uiState.value = _uiState.value.copy(
                    controlUrl = "http://${resolveTabletIpAddress()}:$REMOTE_PORT",
                    lastCommand = "Remote server restarted"
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    lastCommand = "Remote restart error: ${error.message ?: "unknown"}"
                )
            }
        }
    }

    fun togglePrimeApp() {
        if (primeOpenedByApp) {
            bringPlayerToForegroundApp()
        } else {
            openPrimeVideoApp()
        }
    }

    fun consumeFullscreenCommand() {
        _fullscreenCommand.value = null
    }

    private fun applyFullscreen(enabled: Boolean) {
        _fullscreenCommand.value = enabled
        _uiState.value = _uiState.value.copy(
            lastCommand = if (enabled) "Fullscreen on" else "Fullscreen off"
        )
    }

    private fun containsOutOfMemory(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is OutOfMemoryError) return true
            val text = cause.message.orEmpty()
            if (text.contains("OutOfMemoryError", ignoreCase = true)) return true
            cause = cause.cause
        }
        return false
    }

    private fun currentSystemVolumeRatio(): Float {
        val manager = audioManager ?: return 1f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return current.toFloat() / max.toFloat()
    }

    private fun setSystemVolumeRatio(ratio: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (ratio.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
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

    override fun openPrimeVideo() {
        runOnMain { openPrimeVideoApp() }
    }

    override fun bringPlayerToForeground() {
        runOnMain { bringPlayerToForegroundApp() }
    }

    override fun togglePrimeVideo() {
        runOnMain { togglePrimeApp() }
    }

    override fun restartRemoteServer() {
        restartRemoteServerApp()
    }

    override fun setFullscreen(enabled: Boolean) {
        runOnMain { applyFullscreen(enabled) }
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

        vlcPlayer?.detachViews()
        vlcPlayer?.release()
        vlcPlayer = null
        libVlc?.release()
        libVlc = null
        audioFocusRequest?.let { req ->
            audioManager?.abandonAudioFocusRequest(req)
        }
        audioFocusRequest = null

        player.release()
        super.onCleared()
    }
}
