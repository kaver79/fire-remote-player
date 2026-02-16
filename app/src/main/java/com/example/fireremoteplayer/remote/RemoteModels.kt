package com.example.fireremoteplayer.remote

import kotlinx.serialization.Serializable

@Serializable
data class LoadRequest(
    val url: String,
    val autoPlay: Boolean = true
)

@Serializable
data class SeekRequest(
    val positionMs: Long
)

@Serializable
data class VolumeRequest(
    val volume: Float
)

@Serializable
data class ApiResponse(
    val ok: Boolean,
    val message: String
)

@Serializable
data class PlayerStatus(
    val streamUrl: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val lastCommand: String,
    val volume: Float
)
