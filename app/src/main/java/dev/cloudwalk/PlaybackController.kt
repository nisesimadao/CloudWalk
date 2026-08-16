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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Lightweight playback wrapper using only platform Android media APIs. */
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

    private var player: MediaPlayer? = null
    private var currentTrack: Track? = null
    private var resumeAfterFocusGain = false
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

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    player?.runCatching { setVolume(1f, 1f) }
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
                    player?.runCatching { setVolume(0.25f, 0.25f) }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
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
        if (currentTrack?.id == track.id && player != null) {
            resume()
            return
        }

        val generation = playGeneration.incrementAndGet()
        releasePlayer()
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
                        if (isCurrentRequest(generation, track)) prepare(track, cached.absolutePath)
                    }
                    return@execute
                }
                val stream = resolveStream(track)
                if (!isCurrentRequest(generation, track)) return@execute
                main.post {
                    if (isCurrentRequest(generation, track)) prepare(track, stream)
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

    fun canSessionCache(track: Track): Boolean {
        val endpoint = track.streamUrl ?: return false
        return endpoint.contains("progressive", ignoreCase = true)
    }

    fun keepForSession(track: Track, onProgress: (Int) -> Unit = {}, callback: (Boolean, String) -> Unit) {
        if (!canSessionCache(track)) {
            callback(false, appContext.getString(R.string.stream_not_cacheable))
            return
        }
        sessionCache.cachedFile(track)?.let {
            callback(true, appContext.getString(R.string.already_cached))
            return
        }
        io.execute {
            try {
                val stream = resolveStream(track)
                sessionCache.cache(track, stream, { progress -> main.post { onProgress(progress) } }) { ok, message ->
                    main.post { callback(ok, message) }
                }
            } catch (t: Throwable) {
                main.post { callback(false, t.message ?: appContext.getString(R.string.couldnt_cache_track)) }
            }
        }
    }

    fun isSessionCached(track: Track): Boolean = sessionCache.isCached(track)

    private fun resolveStream(track: Track): String = if (!track.streamUrl.isNullOrBlank()) {
        webApi.resolvePublicStream(track.streamUrl)
    } else {
        api.streamUrls(track.id).preferred() ?: throw IllegalStateException(appContext.getString(R.string.no_playable_stream))
    }

    private fun prepareLocal(track: Track, uri: Uri) {
        createPlayer(track) { setDataSource(appContext, uri) }
    }

    private fun prepare(track: Track, source: String) {
        createPlayer(track) { setDataSource(source) }
    }

    private inline fun createPlayer(track: Track, crossinline dataSource: MediaPlayer.() -> Unit) {
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            dataSource()
            setOnPreparedListener { prepared ->
                if (prepared !== player || currentTrack?.id != track.id || released) return@setOnPreparedListener
                listener?.onReady(track, prepared.duration)
                if (requestAudioFocus()) {
                    prepared.start()
                    listener?.onPlayingChanged(track, true)
                } else {
                    listener?.onPlayingChanged(track, false)
                }
            }
            setOnCompletionListener { completed ->
                if (completed !== player || currentTrack?.id != track.id || released) return@setOnCompletionListener
                abandonAudioFocus()
                listener?.onPlayingChanged(track, false)
                listener?.onCompleted(track)
            }
            setOnErrorListener { failed, what, extra ->
                if (failed !== player || currentTrack?.id != track.id || released) return@setOnErrorListener true
                abandonAudioFocus()
                listener?.onError(track, appContext.getString(R.string.media_player_error, what, extra))
                true
            }
            prepareAsync()
        }
        player = mediaPlayer
    }

    fun pause() {
        resumeAfterFocusGain = false
        pauseInternal(abandonFocus = true)
    }

    private fun pauseInternal(abandonFocus: Boolean) {
        val p = player ?: return
        if (runCatching { p.isPlaying }.getOrDefault(false)) {
            runCatching { p.pause() }
            currentTrack?.let { listener?.onPlayingChanged(it, false) }
        }
        if (abandonFocus) abandonAudioFocus()
    }

    fun resume() {
        resumeAfterFocusGain = false
        resumeInternal(requestFocus = true)
    }

    private fun resumeInternal(requestFocus: Boolean) {
        val p = player ?: return
        if (requestFocus && !requestAudioFocus()) return
        if (!runCatching { p.isPlaying }.getOrDefault(false)) {
            p.runCatching {
                setVolume(1f, 1f)
                start()
            }.onSuccess {
                currentTrack?.let { listener?.onPlayingChanged(it, true) }
            }
        }
    }

    fun toggle() {
        if (isPlaying()) pause() else resume()
    }

    fun seekTo(positionMs: Int) {
        player?.runCatching { seekTo(positionMs.coerceAtLeast(0)) }
    }

    fun currentPosition(): Int = player?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: 0
    fun duration(): Int = player?.let { runCatching { it.duration.coerceAtLeast(0) }.getOrDefault(0) } ?: 0
    fun isPlaying(): Boolean = player?.let { runCatching { it.isPlaying }.getOrDefault(false) } == true

    private fun requestAudioFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonAudioFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    fun release() {
        if (released) return
        released = true
        playGeneration.incrementAndGet()
        resumeAfterFocusGain = false
        abandonAudioFocus()
        releasePlayer()
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        io.shutdownNow()
    }

    private fun releasePlayer() {
        player?.runCatching {
            if (isPlaying) stop()
            reset()
            release()
        }
        player = null
    }
}
