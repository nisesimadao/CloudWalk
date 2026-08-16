package dev.cloudwalk

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val permalinkUrl: String? = null,
    val streamUrl: String? = null,
    val localUri: String? = null
)