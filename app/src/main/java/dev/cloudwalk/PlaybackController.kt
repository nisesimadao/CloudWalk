package dev.cloudwalk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** MediaPlayer for local/direct files, Media3 for current SoundCloud HLS/Widevine streams. */
class PlaybackController(
    context: Context,
    private val api: SoundCloudApi,
    private val webApi: WebSoundCloudApi,
    private val sessionCache: SessionAudioCache
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var exoPlayer: ExoPlayer? = null
    @Volatile private var prepared = false
    private var currentTrack: Track? = null
    private var resumeAfterFocusGain = false
    @Volatile private var desiredPlaying = false
    private var released = false
    private val playGeneration = AtomicInteger()

    var listener: Listener? = null

    interface Listener {
        fun onBuffering(track: Track)
        fun onReady(track: Track, durationMs: Int)
        fun onPlayingChanged(track: Track, playing: Boolean)
        fun onCompleted(track: Track)
        fun onError(track: Track?, message: String)
    }

    private sealed interface PlaybackSource {
        data class Direct(val url: String) : PlaybackSource
        data class SoundCloudHls(val stream: ResolvedPublicStream) : PlaybackSource
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    mediaPlayer?.runCatching { setVolume(1f, 1f) }
                    exoPlayer?.volume = 1f
                    if (resumeAfterFocusGain) {
                        resumeAfterFocusGain = false
                        resumeInternal(requestFocus = false)
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeAfterFocusGain = isPlaying()
                    pauseInternal(abandonFocus = false)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    mediaPlayer?.runCatching { setVolume(0.25f, 0.25f) }
                    exoPlayer?.volume = 0.25f
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    desiredPlaying = false
                    resumeAfterFocusGain = false
                    pauseInternal(abandonFocus = false)
                    abandonAudioFocus()
                }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(focusListener)
        .setAcceptsDelayedFocusGain(false)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlaying()) pause()
        }
    }

    init {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(noisyReceiver, filter)
        }
    }

    fun play(track: Track) {
        if (currentTrack?.id == track.id && (mediaPlayer != null || exoPlayer != null)) {
            resume()
            return
        }

        val generation = playGeneration.incrementAndGet()
        desiredPlaying = true
        releasePlayers()
        currentTrack = track
        listener?.onBuffering(track)

        io.execute {
            if (!isCurrentRequest(generation, track)) return@execute
            try {
                if (!track.localUri.isNullOrBlank()) {
                    main.post {
                        if (isCurrentRequest(generation, track)) prepareLocal(track, Uri.parse(track.localUri))
                    }
                    return@execute
                }
                sessionCache.cachedFile(track)?.let { cached ->
                    main.post {
                        if (isCurrentRequest(generation, track)) prepareDirect(track, cached.absolutePath)
                    }
                    return@execute
                }
                val source = resolveSource(track)
                if (!isCurrentRequest(generation, track)) return@execute
                main.post {
                    if (!isCurrentRequest(generation, track)) return@post
                    when (source) {
                        is PlaybackSource.Direct -> prepareDirect(track, source.url)
                        is PlaybackSource.SoundCloudHls -> prepareSoundCloudHls(track, source.stream)
                    }
                }
            } catch (t: Throwable) {
                main.post {
                    if (isCurrentRequest(generation, track)) {
                        listener?.onError(track, t.message ?: appContext.getString(R.string.playback_failed))
                    }
                }
            }
        }
    }

    private fun isCurrentRequest(generation: Int, track: Track): Boolean =
        !released && generation == playGeneration.get() && currentTrack?.id == track.id

    fun canSessionCache(track: Track): Boolean =
        track.localUri.isNullOrBlank() && !track.streamUrl.isNullOrBlank()

    fun keepForSession(track: Track, onProgress: (Int) -> Unit = {}, callback: (Boolean, String) -> Unit) {
        if (!canSessionCache(track)) {
            callback(false, appContext.getString(R.string.stream_not_cacheable))
            return
        }
        if (sessionCache.isCached(track)) {
            callback(true, appContext.getString(R.string.already_cached))
            return
        }
        io.execute {
            try {
                when (val source = resolveSource(track)) {
                    is PlaybackSource.Direct -> sessionCache.cache(
                        track,
                        source.url,
                        { progress -> main.post { onProgress(progress) } }
                    ) { ok, message -> main.post { callback(ok, message) } }
                    is PlaybackSource.SoundCloudHls -> sessionCache.cacheHls(
                        track,
                        source.stream,
                        { progress -> main.post { onProgress(progress) } }
                    ) { ok, message -> main.post { callback(ok, message) } }
                }
            } catch (t: Throwable) {
                main.post { callback(false, t.message ?: appContext.getString(R.string.couldnt_cache_track)) }
            }
        }
    }

    fun isSessionCached(track: Track): Boolean = sessionCache.isCached(track)

    private fun resolveSource(track: Track): PlaybackSource {
        if (!track.streamUrl.isNullOrBlank()) {
            val resolved = webApi.resolvePublicStream(track.streamUrl, track.permalinkUrl)
            return if (resolved.protocol.contains("encrypted-hls", ignoreCase = true)) {
                PlaybackSource.SoundCloudHls(resolved)
            } else {
                PlaybackSource.Direct(resolved.url)
            }
        }
        val url = api.streamUrls(track.id).preferred()
            ?: throw IllegalStateException(appContext.getString(R.string.no_playable_stream))
        return PlaybackSource.Direct(url)
    }

    private fun prepareLocal(track: Track, uri: Uri) {
        createMediaPlayer(track) { setDataSource(appContext, uri) }
    }

    private fun prepareDirect(track: Track, source: String) {
        createMediaPlayer(track) { setDataSource(source) }
    }

    private inline fun createMediaPlayer(track: Track, crossinline dataSource: MediaPlayer.() -> Unit) {
        val player = MediaPlayer()
        mediaPlayer = player
        prepared = false
        runCatching {
            player.setAudioAttributes(audioAttributes)
            player.dataSource()
            player.setOnPreparedListener { ready ->
                if (ready !== mediaPlayer || currentTrack?.id != track.id || released) return@setOnPreparedListener
                prepared = true
                listener?.onReady(track, ready.duration)
                if (desiredPlaying && requestAudioFocus()) {
                    ready.start()
                    listener?.onPlayingChanged(track, true)
                } else {
                    listener?.onPlayingChanged(track, false)
                }
            }
            player.setOnCompletionListener { completed ->
                if (completed !== mediaPlayer || currentTrack?.id != track.id || released) return@setOnCompletionListener
                desiredPlaying = false
                abandonAudioFocus()
                listener?.onPlayingChanged(track, false)
                listener?.onCompleted(track)
            }
            player.setOnErrorListener { failed, what, extra ->
                if (failed !== mediaPlayer || currentTrack?.id != track.id || released) return@setOnErrorListener true
                prepared = false
                desiredPlaying = false
                abandonAudioFocus()
                listener?.onError(track, appContext.getString(R.string.media_player_error, what, extra))
                main.post { if (failed === mediaPlayer) releasePlayers() }
                true
            }
            player.prepareAsync()
        }.onFailure { error ->
            if (player === mediaPlayer) releasePlayers()
            if (!released && currentTrack?.id == track.id) {
                listener?.onError(track, error.message ?: appContext.getString(R.string.playback_failed))
            }
        }
    }

    private fun prepareSoundCloudHls(track: Track, stream: ResolvedPublicStream) {
        val licenseToken = stream.licenseAuthToken
        if (licenseToken.isNullOrBlank()) {
            listener?.onError(track, appContext.getString(R.string.playback_failed))
            return
        }
        val requestHeaders = mapOf("X-SC-Application-Id" to stream.applicationId)
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(sessionCache.playbackDataSourceFactory(requestHeaders))
        val exo = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        exoPlayer = exo
        prepared = false

        val encodedToken = URLEncoder.encode(licenseToken, StandardCharsets.UTF_8.name())
        val licenseUri = "https://license.media-streaming.soundcloud.cloud/playback/widevine?license_token=$encodedToken"
        val drm = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(licenseUri)
            .setLicenseRequestHeaders(requestHeaders)
            .build()
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setDrmConfiguration(drm)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (exo !== exoPlayer || currentTrack?.id != track.id || released) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> if (!prepared) listener?.onBuffering(track)
                    Player.STATE_READY -> {
                        val firstReady = !prepared
                        prepared = true
                        if (firstReady) {
                            val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: track.durationMs
                            listener?.onReady(track, duration.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                        }
                        if (desiredPlaying && !exo.isPlaying && requestAudioFocus()) exo.play()
                    }
                    Player.STATE_ENDED -> {
                        desiredPlaying = false
                        abandonAudioFocus()
                        listener?.onPlayingChanged(track, false)
                        listener?.onCompleted(track)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (exo !== exoPlayer || currentTrack?.id != track.id || released || !prepared) return
                listener?.onPlayingChanged(track, isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (exo !== exoPlayer || currentTrack?.id != track.id || released) return
                prepared = false
                desiredPlaying = false
                abandonAudioFocus()
                listener?.onError(track, error.cause?.message ?: error.message ?: appContext.getString(R.string.playback_failed))
            }
        })
        exo.setMediaItem(item)
        exo.prepare()
    }

    fun pause() {
        desiredPlaying = false
        resumeAfterFocusGain = false
        pauseInternal(abandonFocus = true)
    }

    private fun pauseInternal(abandonFocus: Boolean) {
        val media = mediaPlayer
        if (media != null && prepared && runCatching { media.isPlaying }.getOrDefault(false)) {
            runCatching { media.pause() }
            currentTrack?.let { listener?.onPlayingChanged(it, false) }
        }
        val exo = exoPlayer
        if (exo != null && prepared && exo.isPlaying) exo.pause()
        if (abandonFocus) abandonAudioFocus()
    }

    fun resume() {
        desiredPlaying = true
        resumeAfterFocusGain = false
        resumeInternal(requestFocus = true)
    }

    private fun resumeInternal(requestFocus: Boolean) {
        if (!prepared) return
        if (requestFocus && !requestAudioFocus()) return
        mediaPlayer?.let { media ->
            if (!runCatching { media.isPlaying }.getOrDefault(false)) {
                media.runCatching {
                    setVolume(1f, 1f)
                    start()
                }.onSuccess { currentTrack?.let { listener?.onPlayingChanged(it, true) } }
            }
        }
        exoPlayer?.let { exo ->
            exo.volume = 1f
            if (!exo.isPlaying) exo.play()
        }
    }

    fun toggle() {
        if (desiredPlaying) pause() else resume()
    }

    fun seekTo(positionMs: Int) {
        if (!prepared) return
        val safe = positionMs.coerceAtLeast(0)
        mediaPlayer?.runCatching { seekTo(safe) }
        exoPlayer?.seekTo(safe.toLong())
    }

    fun currentPosition(): Int {
        if (!prepared) return 0
        mediaPlayer?.let { return runCatching { it.currentPosition }.getOrDefault(0) }
        return exoPlayer?.currentPosition?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }

    fun duration(): Int {
        if (!prepared) return 0
        mediaPlayer?.let { return runCatching { it.duration.coerceAtLeast(0) }.getOrDefault(0) }
        val value = exoPlayer?.duration ?: return 0
        return if (value == C.TIME_UNSET || value <= 0L) 0 else value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun isPlaying(): Boolean {
        if (!prepared) return false
        return mediaPlayer?.let { runCatching { it.isPlaying }.getOrDefault(false) }
            ?: exoPlayer?.isPlaying
            ?: false
    }

    private fun requestAudioFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonAudioFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    fun release() {
        if (released) return
        released = true
        playGeneration.incrementAndGet()
        desiredPlaying = false
        resumeAfterFocusGain = false
        abandonAudioFocus()
        releasePlayers()
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        io.shutdownNow()
    }

    private fun releasePlayers() {
        val oldMedia = mediaPlayer
        mediaPlayer = null
        if (oldMedia != null) {
            if (runCatching { oldMedia.isPlaying }.getOrDefault(false)) runCatching { oldMedia.stop() }
            runCatching { oldMedia.reset() }
            runCatching { oldMedia.release() }
        }
        val oldExo = exoPlayer
        exoPlayer = null
        if (oldExo != null) runCatching { oldExo.release() }
        prepared = false
    }
}
