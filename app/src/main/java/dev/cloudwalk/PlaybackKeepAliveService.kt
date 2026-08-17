package dev.cloudwalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

/**
 * Tiny platform-only foreground service that keeps an active MediaSession playback process alive.
 * The actual MediaPlayer remains in PlaybackController; this service only owns the foreground
 * lifecycle and notification controls.
 */
class PlaybackKeepAliveService : Service() {
    private var controller: MediaController? = null
    private var sessionToken: MediaSession.Token? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateForeground()
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateForeground()
        override fun onSessionDestroyed() { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.playback_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIOUS -> controller?.transportControls?.skipToPrevious()
            ACTION_PLAY_PAUSE -> {
                if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) controller?.transportControls?.pause()
                else controller?.transportControls?.play()
            }
            ACTION_NEXT -> controller?.transportControls?.skipToNext()
            ACTION_STOP -> {
                controller?.transportControls?.pause()
                stopSelf()
            }
            else -> {
                val token = readToken(intent)
                if (token != null && token != sessionToken) attachSession(token)
            }
        }
        if (controller != null) updateForeground()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readToken(intent: Intent?): MediaSession.Token? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_SESSION_TOKEN, MediaSession.Token::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_SESSION_TOKEN)
        }
    }

    private fun attachSession(token: MediaSession.Token) {
        controller?.unregisterCallback(controllerCallback)
        sessionToken = token
        controller = MediaController(this, token).also { it.registerCallback(controllerCallback) }
    }

    private fun updateForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val c = controller
        val metadata = c?.metadata
        val state = c?.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "CloudWalk"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: getString(R.string.music_playback)

        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actions = state?.actions ?: 0L
        val canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        val canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)

        val compact = ArrayList<Int>(3)
        if (canPrevious) {
            compact += compact.size
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_prev),
                    getString(R.string.previous),
                    servicePendingIntent(ACTION_PREVIOUS, 2)
                ).build()
            )
        }
        compact += compact.size
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                if (playing) getString(R.string.pause) else getString(R.string.play),
                servicePendingIntent(ACTION_PLAY_PAUSE, 3)
            ).build()
        )
        if (canNext) {
            compact += compact.size
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_next),
                    getString(R.string.next),
                    servicePendingIntent(ACTION_NEXT, 4)
                ).build()
            )
        }
        builder.setStyle(
            Notification.MediaStyle()
                .setMediaSession(sessionToken)
                .setShowActionsInCompactView(*compact.toIntArray())
        )
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackKeepAliveService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onDestroy() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        sessionToken = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_SESSION_TOKEN = "session_token"
        const val ACTION_PREVIOUS = "dev.cloudwalk.action.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "dev.cloudwalk.action.PLAY_PAUSE"
        const val ACTION_NEXT = "dev.cloudwalk.action.NEXT"
        const val ACTION_STOP = "dev.cloudwalk.action.STOP"
        private const val CHANNEL_ID = "cloudwalk_playback"
        private const val NOTIFICATION_ID = 42
    }
}
