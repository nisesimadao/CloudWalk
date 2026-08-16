package dev.cloudwalk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.MediaMetadata
import android.media.MediaDescription
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.text.TextUtils
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.concurrent.Executors
import java.util.ArrayDeque

class MainActivity : Activity(), PlaybackController.Listener {

    private val homeTracks = ArrayList<Track>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var api: SoundCloudApi
    private lateinit var webApi: WebSoundCloudApi
    private lateinit var playback: PlaybackController
    private lateinit var sessionCache: SessionAudioCache
    private lateinit var cacheSettings: CacheSettings
    private lateinit var artwork: ArtworkCache
    private lateinit var authBroker: AuthBroker
    private lateinit var authSession: AuthSession
    private lateinit var collections: LocalCollections
    private lateinit var mediaSession: MediaSession
    private val authRedirectUri = "cloudwalk://auth/callback"

    private lateinit var flowView: CoverFlowView
    private lateinit var listView: ListView
    private lateinit var contentHost: FrameLayout
    private lateinit var homeEmptyView: TextView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var miniTitleView: TextView
    private lateinit var miniArtistView: TextView
    private lateinit var miniArtwork: ImageView
    private lateinit var playButton: ImageButton
    private lateinit var progress: SeekBar
    private lateinit var trackInfoPanel: View
    private lateinit var playerStripView: View

    private var selectedTrack: Track? = null
    private var playing = false
    private var showingFlow = true
    private var lowPowerMode = false
    private var progressTicker: Runnable? = null
    private var playbackServiceStop: Runnable? = null
    private var sleepTimerRunnable: Runnable? = null
    private var sleepAtTrackEnd = false
    private var overlay: View? = null
    private val overlayStack = ArrayDeque<View>()
    private var lastBackHandledAt = 0L
    private val overlayBasePadding = java.util.WeakHashMap<View, IntArray>()
    private var deferArtworkLoads = false
    private var nowPlayingScreen: View? = null
    private var nowPlayingArtwork: ImageView? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingArtist: TextView? = null
    private var nowPlayingPlay: ImageButton? = null
    private var nowPlayingSeek: SeekBar? = null
    private var nowPlayingElapsed: TextView? = null
    private var nowPlayingDuration: TextView? = null
    private var nowPlayingPosition: TextView? = null
    private var nowPlayingPrev: ImageButton? = null
    private var nowPlayingNext: ImageButton? = null
    private var nowPlayingLike: ImageButton? = null
    private var nowPlayingShuffle: ImageButton? = null
    private var nowPlayingRepeat: ImageButton? = null
    private var shuffleEnabled = false
    private enum class RepeatMode { OFF, ALL, ONE }
    private var repeatMode = RepeatMode.OFF
    private val shuffledTrackIds = ArrayList<String>()

    private val REQUEST_LOCAL_AUDIO = 4201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authBroker = AuthBroker(getString(R.string.auth_broker_url))
        authSession = AuthSession(this, authBroker)
        api = SoundCloudApi(
            accessTokenProvider = { authSession.accessToken() },
            refreshAccessToken = { authSession.forceRefresh() }
        )
        webApi = WebSoundCloudApi(this)
        collections = LocalCollections(this)
        val recentHome = collections.recent()
        if (recentHome.isNotEmpty()) {
            homeTracks.clear()
            homeTracks.addAll(recentHome.take(30))
        }
        cacheSettings = CacheSettings(this)
        sessionCache = SessionAudioCache(this, cacheSettings)
        playback = PlaybackController(this, api, webApi, sessionCache).also { it.listener = this }
        artwork = ArtworkCache(this)
        setupMediaSession()
        setContentView(buildUi())
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBackAction() }
        }
        homeTracks.firstOrNull()?.let { showTrack(it) }
        handleAuthIntent(intent)
        handleSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
        handleSharedText(intent)
    }

    private fun handleBackAction() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackHandledAt < 150L) return
        lastBackHandledAt = now
        if (overlay != null) closeOverlay() else finish()
    }



    private fun isOnline(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isSoundCloudUrl(text: String): Boolean =
        text.startsWith("https://soundcloud.com/", ignoreCase = true) ||
            text.startsWith("http://soundcloud.com/", ignoreCase = true) ||
            text.startsWith("https://www.soundcloud.com/", ignoreCase = true) ||
            text.startsWith("https://on.soundcloud.com/", ignoreCase = true)

    private fun handleSharedText(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND || incoming.type != "text/plain") return
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        incoming.removeExtra(Intent.EXTRA_TEXT)
        val url = Regex("""https?://(?:(?:www\.)?soundcloud\.com|on\.soundcloud\.com)/[^\s]+""", RegexOption.IGNORE_CASE)
            .find(text)?.value?.trimEnd('.', ',', ')', ']', '}') ?: return
        if (!isOnline()) { toast(getString(R.string.offline)); return }
        io.execute {
            val result = runCatching { webApi.resolveTrackUrl(url) }
            main.post {
                result.onSuccess { track ->
                    if (track != null) {
                        updateHomeCollection(listOf(track), 0)
                        play(track)
                        showNowPlaying(track)
                    } else toast(getString(R.string.not_public_track))
                }.onFailure { toast(getString(R.string.couldnt_open_link)) }
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "CloudWalk").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    val current = selectedTrack
                    if (playback.duration() > 0) playback.resume() else current?.let { play(it) }
                }
                override fun onPause() { playback.pause() }
                override fun onSkipToNext() { playRelative(1) }
                override fun onSkipToPrevious() { playRelative(-1) }
                override fun onSkipToQueueItem(id: Long) {
                    val queue = currentQueueOrder()
                    val track = queue.getOrNull(id.toInt()) ?: return
                    val homeIndex = homeTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                    flowView.setSelected(homeIndex, false)
                    play(track)
                }
                override fun onSeekTo(pos: Long) {
                    playback.seekTo(pos.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    syncMediaSessionAfterSeek()
                }
            })
            isActive = true
        }
        updateMediaSession()
        updateMediaSessionQueue()
    }

    private fun currentQueueOrder(): List<Track> =
        if (shuffleEnabled && shuffledTrackIds.isNotEmpty()) shuffledTrackIds.mapNotNull { id -> homeTracks.firstOrNull { it.id == id } } else homeTracks

    private fun updateMediaSessionQueue() {
        if (!::mediaSession.isInitialized) return
        val queue = currentQueueOrder()
        mediaSession.setQueueTitle(if (shuffleEnabled) getString(R.string.queue_title_shuffled) else getString(R.string.queue_title))
        mediaSession.setQueue(queue.mapIndexed { index, track ->
            MediaSession.QueueItem(
                MediaDescription.Builder()
                    .setMediaId(track.id)
                    .setTitle(track.title)
                    .setSubtitle(track.artist)
                    .setMediaUri(track.permalinkUrl?.let(android.net.Uri::parse))
                    .build(),
                index.toLong()
            )
        })
    }

    private fun updateMediaSession() {
        if (!::mediaSession.isInitialized) return
        val track = selectedTrack
        if (track != null) {
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .apply {
                        track.album?.takeIf { it.isNotBlank() }?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
                        track.artworkUrl?.takeIf { it.isNotBlank() }?.let {
                            putString(MediaMetadata.METADATA_KEY_ART_URI, it)
                            putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it)
                        }
                        artwork.peek(track.artworkUrl, dp(180))?.let { bitmap ->
                            putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
                            putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                        }
                    }
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, (playback.duration().takeIf { it > 0 }?.toLong() ?: track.durationMs))
                    .build()
            )
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    playback.currentPosition().toLong(),
                    if (playing) 1f else 0f
                )
                .build()
        )
    }

    private fun syncMediaSessionAfterSeek() {
        main.postDelayed({ updateMediaSession() }, 120L)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 12, 12))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = systemBarInsets(insets)
                view.setPadding(0, bars[0], 0, bars[1])
                insets
            }
        }

        root.addView(buildToolbar(), LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = getString(R.string.your_sound)
            textSize = 12f
            setTextColor(Color.rgb(174, 174, 174))
            typeface = Typeface.create("sans", Typeface.BOLD)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }, LinearLayout.LayoutParams(-1, dp(38)))

        contentHost = FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))

        flowView = CoverFlowView(this).apply {
            artworkCache = this@MainActivity.artwork
            tracks = this@MainActivity.homeTracks
            onSelectionChanged = { _, track -> showTrack(track) }
            onTrackClick = { track -> play(track) }
            contentDescription = getString(R.string.cover_browser)
        }
        contentHost.addView(flowView, FrameLayout.LayoutParams(-1, -1))

        listView = ListView(this).apply {
            divider = ColorDrawable(Color.rgb(38, 38, 38))
            dividerHeight = 1
            selector = selectableBackground()
            isVerticalScrollBarEnabled = false
            adapter = TrackAdapter(homeTracks)
            installArtworkScrollPolicy(this)
            installSwipeToCache(this, homeTracks)
            setOnItemClickListener { _, _, position, _ ->
                flowView.setSelected(position, false)
                play(homeTracks[position])
            }
            setOnItemLongClickListener { _, _, position, _ ->
                showTrackMenu(homeTracks[position], position)
                true
            }
            visibility = View.GONE
        }
        contentHost.addView(listView, FrameLayout.LayoutParams(-1, -1))
        homeEmptyView = TextView(this).apply {
            text = getString(R.string.home_empty)
            textSize = 14f
            setTextColor(Color.rgb(150, 150, 150))
            gravity = Gravity.CENTER
            setPadding(dp(36), 0, dp(36), 0)
            background = selectableBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { showSearch() }
            visibility = if (homeTracks.isEmpty()) View.VISIBLE else View.GONE
        }
        contentHost.addView(homeEmptyView, FrameLayout.LayoutParams(-1, -1))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(4), dp(20), dp(2))
        }
        titleView = textLine(20f, Color.WHITE, true, Gravity.CENTER)
        artistView = textLine(13f, Color.rgb(170, 170, 170), false, Gravity.CENTER)
        info.addView(titleView, LinearLayout.LayoutParams(-1, dp(30)))
        info.addView(artistView, LinearLayout.LayoutParams(-1, dp(24)))
        trackInfoPanel = info
        trackInfoPanel.visibility = if (homeTracks.isEmpty()) View.GONE else View.VISIBLE
        root.addView(info, LinearLayout.LayoutParams(-1, dp(60)))

        playerStripView = buildPlayerStrip().apply { visibility = if (homeTracks.isEmpty()) View.GONE else View.VISIBLE }
        root.addView(playerStripView, LinearLayout.LayoutParams(-1, dp(76)))
        root.addView(buildBottomNav(), LinearLayout.LayoutParams(-1, dp(64)))
        return root
    }

    private fun buildToolbar(): Toolbar = Toolbar(this).apply {
        setBackgroundColor(Color.rgb(12, 12, 12))
        title = "CloudWalk"
        setTitleTextColor(Color.WHITE)
        setTitleTextAppearance(this@MainActivity, android.R.style.TextAppearance_Material_Title)
        elevation = dp(2).toFloat()
        setContentInsetsRelative(dp(20), dp(8))
        addView(ImageButton(this@MainActivity).apply {
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(Color.WHITE)
            background = selectableBorderlessBackground()
            contentDescription = getString(R.string.view_options)
            setOnClickListener { showViewMenu(this) }
        }, Toolbar.LayoutParams(dp(48), -1).apply { gravity = Gravity.END })
        addView(ImageButton(this@MainActivity).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.WHITE)
            background = selectableBorderlessBackground()
            contentDescription = getString(R.string.search)
            setOnClickListener { showSearch() }
        }, Toolbar.LayoutParams(dp(48), -1).apply { gravity = Gravity.END })
    }

    private fun buildPlayerStrip(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(24, 24, 24))
        progress = SeekBar(this@MainActivity).apply {
            max = 1000
            splitTrack = false
            contentDescription = getString(R.string.playback_position)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = playback.duration()
                        if (duration > 0) playback.seekTo((duration * value / 1000L).toInt())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { syncMediaSessionAfterSeek() }
            })
        }
        addView(progress, LinearLayout.LayoutParams(-1, dp(24)).apply { marginStart = dp(10); marginEnd = dp(10) })

        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            background = selectableBackground()
            setOnClickListener { selectedTrack?.let { showNowPlaying(it) } }
        }
        miniArtwork = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(48, 48, 48))
        }
        row.addView(miniArtwork, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        val miniText = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        miniTitleView = textLine(14f, Color.WHITE, true, Gravity.START or Gravity.CENTER_VERTICAL)
        miniArtistView = textLine(11f, Color.rgb(155, 155, 155), false, Gravity.START or Gravity.CENTER_VERTICAL)
        miniText.addView(miniTitleView, LinearLayout.LayoutParams(-1, dp(24)))
        miniText.addView(miniArtistView, LinearLayout.LayoutParams(-1, dp(18)))
        row.addView(miniText, LinearLayout.LayoutParams(0, -1, 1f))
        playButton = ImageButton(this@MainActivity).apply {
            background = selectableBorderlessBackground()
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.play_pause)
            setOnClickListener {
                if (playback.duration() > 0) playback.toggle() else selectedTrack?.let { play(it) }
            }
        }
        row.addView(playButton, LinearLayout.LayoutParams(dp(52), -1))
        addView(row, LinearLayout.LayoutParams(-1, dp(52)))
        updatePlayButton()
    }

    private fun buildBottomNav(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(17, 17, 17))
        addView(navItem(R.drawable.ic_home, getString(R.string.home), true) { setViewMode(true) }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(navItem(R.drawable.ic_search, getString(R.string.search), false) { showSearch() }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(navItem(R.drawable.ic_like, getString(R.string.likes), false) { showLikes() }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(navItem(R.drawable.ic_library, getString(R.string.library), false) { showLibrary() }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun navItem(iconRes: Int, label: String, active: Boolean, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = selectableBackground()
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(iconRes)
            setColorFilter(if (active) Color.rgb(255, 123, 38) else Color.rgb(165, 165, 165))
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(-1, dp(30)))
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 11f; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setTextColor(if (active) Color.WHITE else Color.rgb(148, 148, 148))
        }, LinearLayout.LayoutParams(-1, dp(24)))
    }

    private fun hasAccount(): Boolean = authSession.hasAccount()

    private fun showLikes() {
        showStoredTrackScreen(getString(R.string.likes), collections.likes(), getString(R.string.no_liked_tracks))
    }

    private fun showStoredTrackScreen(title: String, items: List<Track>, emptyMessage: String) {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 12, 12))
        }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12)); this.title = title; setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back)
            setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        if (items.isEmpty()) {
            screen.addView(TextView(this).apply {
                text = emptyMessage
                textSize = 14f
                setTextColor(Color.rgb(155, 155, 155))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val list = ListView(this).apply {
                divider = ColorDrawable(Color.rgb(38, 38, 38)); dividerHeight = 1
                selector = selectableBackground(); isVerticalScrollBarEnabled = false
                adapter = TrackAdapter(items)
                installArtworkScrollPolicy(this)
                installSwipeToCache(this, items)
                setOnItemClickListener { _, _, position, _ ->
                    updateHomeCollection(items, position)
                    play(items[position])
                    showNowPlaying(items[position])
                }
                setOnItemLongClickListener { _, _, position, _ -> showTrackMenu(items[position]); true }
            }
            screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        showOverlay(screen)
    }

    private fun showRemoteTrackScreen(title: String, loader: () -> List<Track>) {
        if (!hasAccount()) {
            showConnectScreen(title)
            return
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 12, 12))
        }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12)); this.title = title; setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back)
            setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val status = TextView(this).apply {
            text = getString(R.string.loading); textSize = 14f; setTextColor(Color.rgb(165,165,165)); gravity = Gravity.CENTER
        }
        screen.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        showOverlay(screen)
        io.execute {
            val result = runCatching(loader)
            main.post {
                if (overlay !== screen) return@post
                result.onSuccess { items ->
                    screen.removeView(status)
                    if (items.isEmpty()) {
                        status.text = getString(R.string.nothing_here)
                        screen.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
                    } else {
                        val list = ListView(this).apply {
                            divider = ColorDrawable(Color.rgb(40,40,40)); dividerHeight = 1
                            selector = selectableBackground(); isVerticalScrollBarEnabled = false
                            adapter = TrackAdapter(items)
                            installArtworkScrollPolicy(this)
                            installSwipeToCache(this, items)
                            setOnItemClickListener { _, _, position, _ ->
                                updateHomeCollection(items, position)
                                play(items[position])
                                showNowPlaying(items[position])
                            }
                        }
                        screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
                    }
                }.onFailure { error ->
                    status.text = if (error is SoundCloudException && error.statusCode == 401) getString(R.string.session_expired) else getString(R.string.couldnt_load_section, title)
                }
            }
        }
    }

    private fun showLibrary() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12,12,12))
        }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12,12,12)); title = getString(R.string.library); setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back)
            setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val list = ListView(this).apply {
            divider = ColorDrawable(Color.rgb(38,38,38)); dividerHeight = 1
            selector = selectableBackground(); isVerticalScrollBarEnabled = false
        }
        val local = LocalLibrary(this).all()
        val albumCount = local.map { it.album?.takeIf(String::isNotBlank) ?: getString(R.string.unknown_album) }.distinct().size
        val artistCount = local.map { it.artist.ifBlank { getString(R.string.unknown_artist) } }.distinct().size
        val recentCount = collections.recent().size
        val likeCount = collections.likes().size
        val cacheMb = sessionCache.currentBytes().toDouble() / (1024.0 * 1024.0)
        val cachedLabel = if (cacheMb < 0.05) getString(R.string.cached_empty) else getString(R.string.cached_size, "%.1f".format(cacheMb))
        val common = arrayOf(
            getString(R.string.songs_count, local.size),
            getString(R.string.albums_count, albumCount),
            getString(R.string.artists_count, artistCount),
            cachedLabel,
            getString(R.string.recent_count, recentCount),
            getString(R.string.likes_count, likeCount)
        )
        val entries = if (hasAccount()) common + arrayOf(getString(R.string.soundcloud_likes), getString(R.string.playlists))
        else common + arrayOf(getString(R.string.connect_soundcloud))
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
        list.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> showLocalFiles()
                1 -> showLocalGroups(true)
                2 -> showLocalGroups(false)
                3 -> showCachedTracks()
                4 -> showStoredTrackScreen(getString(R.string.recently_played), collections.recent(), getString(R.string.nothing_played))
                5 -> showLikes()
                6 -> if (hasAccount()) showRemoteTrackScreen(getString(R.string.soundcloud_likes)) { api.likedTracks(50) } else showConnectScreen(getString(R.string.soundcloud))
                7 -> if (hasAccount()) showPlaylists()
            }
        }
        screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        showOverlay(screen)
    }


    private fun showLocalFiles() {
        showOverlay(buildLocalFilesScreen())
    }

    private fun buildLocalFilesScreen(): View {
        val localLibrary = LocalLibrary(this)
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,12,12)) }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12,12,12)); title = getString(R.string.local_files); setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back); setNavigationOnClickListener { closeOverlay() }
            menu.add(getString(R.string.add)).apply {
                setIcon(R.drawable.ic_add)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            setOnMenuItemClickListener { item ->
                if (item.title == getString(R.string.add)) { openLocalAudioPicker(); true } else false
            }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val local = localLibrary.all()
        if (local.isEmpty()) {
            screen.addView(TextView(this).apply {
                text = getString(R.string.no_local_tracks_help)
                gravity = Gravity.CENTER; setTextColor(Color.GRAY); textSize = 14f
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val list = ListView(this).apply {
                divider = ColorDrawable(Color.rgb(38,38,38)); dividerHeight = 1
                selector = selectableBackground(); isVerticalScrollBarEnabled = false
                adapter = TrackAdapter(local)
                installArtworkScrollPolicy(this)
                setOnItemClickListener { _, _, position, _ ->
                    updateHomeCollection(local, position)
                    play(local[position])
                    showNowPlaying(local[position])
                }
                setOnItemLongClickListener { _, _, position, _ ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(local[position].title)
                        .setItems(arrayOf(getString(R.string.remove_from_library))) { _, _ ->
                            localLibrary.remove(local[position])
                            showLocalFiles()
                        }
                        .show()
                    true
                }
            }
            screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return screen
    }

    private fun openLocalAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_LOCAL_AUDIO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOCAL_AUDIO || resultCode != RESULT_OK || data == null) return
        val library = LocalLibrary(this)
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let(uris::add)
        val readable = uris.distinct().filter { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
        }
        if (readable.isEmpty()) {
            toast(resources.getQuantityString(R.plurals.added_tracks, 0, 0))
            return
        }
        toast(getString(R.string.adding_tracks))
        io.execute {
            val added = library.addAll(readable)
            main.post {
                if (isDestroyed) return@post
                toast(resources.getQuantityString(R.plurals.added_tracks, added, added))
                showLocalFiles()
            }
        }
    }

    private fun showLocalGroups(byAlbum: Boolean) {
        val tracks = LocalLibrary(this).all()
        val groups = tracks.groupBy {
            if (byAlbum) (it.album?.takeIf { value -> value.isNotBlank() } ?: getString(R.string.unknown_album))
            else it.artist.ifBlank { getString(R.string.unknown_artist) }
        }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val title = if (byAlbum) getString(R.string.albums) else getString(R.string.artists)
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,12,12)) }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12,12,12)); this.title = title; setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        if (groups.isEmpty()) {
            screen.addView(TextView(this).apply { text = getString(R.string.no_local_tracks); gravity = Gravity.CENTER; setTextColor(Color.GRAY) }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val names = groups.keys.toList()
            val labels = names.map { name -> "$name  ·  ${groups[name]?.size ?: 0}" }
            val list = ListView(this).apply {
                divider = ColorDrawable(Color.rgb(38,38,38)); dividerHeight = 1; selector = selectableBackground()
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels)
                setOnItemClickListener { _, _, position, _ -> showLocalSubset(names[position], groups[names[position]].orEmpty()) }
            }
            screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        showOverlay(screen)
    }

    private fun showLocalSubset(title: String, tracks: List<Track>) {
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,12,12)) }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12,12,12)); this.title = title; setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val list = ListView(this).apply {
            divider = ColorDrawable(Color.rgb(38,38,38)); dividerHeight = 1; selector = selectableBackground()
            adapter = TrackAdapter(tracks)
            installArtworkScrollPolicy(this)
            setOnItemClickListener { _, _, position, _ ->
                updateHomeCollection(tracks, position); play(tracks[position]); showNowPlaying(tracks[position])
            }
        }
        screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        showOverlay(screen)
    }


    private fun showCachedTracks() { showOverlay(buildCachedTracksScreen()) }

    private fun buildCachedTracksScreen(): View {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 12, 12))
        }
        val usedMb = sessionCache.currentBytes().toDouble() / (1024.0 * 1024.0)
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12))
            title = if (usedMb < 0.05) getString(R.string.cached) else getString(R.string.cached_size, "%.1f".format(usedMb))
            setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back)
            navigationContentDescription = getString(R.string.back)
            setNavigationOnClickListener { closeOverlay() }
            menu.add(getString(R.string.cache_size))
            menu.add(getString(R.string.clear_all))
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    getString(R.string.cache_size) -> { showCacheSizeDialog(); true }
                    getString(R.string.clear_all) -> { sessionCache.clear(); replaceOverlay(buildCachedTracksScreen()); true }
                    else -> false
                }
            }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        screen.addView(TextView(this).apply {
            text = getString(R.string.cache_temporary)
            textSize = 11f
            setTextColor(Color.rgb(145, 145, 145))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }, LinearLayout.LayoutParams(-1, dp(34)))
        val items = sessionCache.cachedTracks()
        if (items.isEmpty()) {
            screen.addView(TextView(this).apply {
                text = getString(R.string.no_cached_tracks)
                textSize = 14f
                setTextColor(Color.rgb(155, 155, 155))
                gravity = Gravity.CENTER
                setPadding(dp(36), 0, dp(36), 0)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val list = ListView(this).apply {
                divider = ColorDrawable(Color.rgb(38, 38, 38)); dividerHeight = 1
                selector = selectableBackground(); isVerticalScrollBarEnabled = false
                adapter = TrackAdapter(items)
                installArtworkScrollPolicy(this)
                setOnItemClickListener { _, _, position, _ ->
                    updateHomeCollection(items, position); play(items[position]); showNowPlaying(items[position])
                }
                setOnItemLongClickListener { _, _, position, _ ->
                    val track = items[position]
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(track.title)
                        .setItems(arrayOf(getString(R.string.remove_session_cache))) { _, _ ->
                            sessionCache.remove(track)
                            replaceOverlay(buildCachedTracksScreen())
                        }
                        .show()
                    true
                }
            }
            screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return screen
    }

    private fun showPlaylists() {
        if (!hasAccount()) { showConnectScreen(getString(R.string.playlists)); return }
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,12,12)) }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12,12,12)); title = getString(R.string.playlists); setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back); setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val status = TextView(this).apply { text = getString(R.string.loading); textSize = 14f; setTextColor(Color.GRAY); gravity = Gravity.CENTER }
        screen.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        showOverlay(screen)
        io.execute {
            val result = runCatching { api.playlists(50) }
            main.post {
                if (overlay !== screen) return@post
                result.onSuccess { playlists ->
                    screen.removeView(status)
                    val labels = playlists.map { p -> if (p.trackCount > 0) "${p.title}  ·  ${getString(R.string.track_count, p.trackCount)}" else p.title }
                    val list = ListView(this).apply {
                        divider = ColorDrawable(Color.rgb(38,38,38)); dividerHeight = 1; selector = selectableBackground()
                        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels)
                        setOnItemClickListener { _, _, position, _ ->
                            val playlist = playlists[position]
                            showRemoteTrackScreen(playlist.title) { api.playlistTracks(playlist.id, 100) }
                        }
                    }
                    screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
                }.onFailure { status.text = getString(R.string.couldnt_load_playlists) }
            }
        }
    }

    private fun showConnectScreen(sectionTitle: String) {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Color.rgb(12,12,12))
        }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12,12,12)); title = sectionTitle; setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back); setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        body.addView(TextView(this).apply {
            text = getString(R.string.connect_soundcloud); textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(54)))
        body.addView(TextView(this).apply {
            text = getString(R.string.connect_explainer); textSize = 14f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(76)))
        body.addView(Button(this).apply {
            text = getString(R.string.connect)
            setOnClickListener { startSoundCloudConnect() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { marginStart = dp(36); marginEnd = dp(36) })
        screen.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        showOverlay(screen)
    }


    private fun startSoundCloudConnect() {
        if (!authBroker.configured) {
            toast(getString(R.string.auth_not_configured))
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, authBroker.authorizationUri(authRedirectUri)))
        }.onFailure { toast(getString(R.string.couldnt_open_signin)) }
    }

    private fun handleAuthIntent(incoming: Intent?) {
        val data = incoming?.data ?: return
        if (data.scheme != "cloudwalk" || data.host != "auth" || data.path != "/callback") return
        val code = data.getQueryParameter("code")
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            toast(getString(R.string.connection_cancelled))
            return
        }
        if (code.isNullOrBlank() || !authBroker.configured) return
        io.execute {
            val result = runCatching { authBroker.exchange(code, authRedirectUri) }
            main.post {
                result.onSuccess { tokens ->
                    authSession.save(tokens)
                    closeOverlay()
                    toast(getString(R.string.connected))
                }.onFailure {
                    toast(getString(R.string.connection_failed))
                }
            }
        }
    }

    private fun showSearch() {
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 12, 12)) }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12)); title = getString(R.string.search); setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back)
            setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val search = SearchView(this).apply { queryHint = getString(R.string.search_hint); isIconified = false; imeOptions = EditorInfo.IME_ACTION_SEARCH }
        screen.addView(search, LinearLayout.LayoutParams(-1, dp(58)))
        val searchStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(155, 155, 155))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            visibility = View.GONE
        }
        screen.addView(searchStatus, LinearLayout.LayoutParams(-1, dp(32)))
        val results = ListView(this).apply {
            divider = ColorDrawable(Color.rgb(40, 40, 40)); dividerHeight = 1; selector = selectableBackground(); isVerticalScrollBarEnabled = false
            installArtworkScrollPolicy(this)
        }
        screen.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        fun showSearchStatus(message: String?) {
            searchStatus.text = message.orEmpty()
            searchStatus.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        fun showResults(items: List<Track>) {
            showSearchStatus(null)
            results.adapter = TrackAdapter(items)
            installSwipeToCache(results, items)
            results.setOnItemClickListener { _, _, position, _ ->
                val track = items[position]
                if (items.isNotEmpty()) updateHomeCollection(items, position)
                play(track); showNowPlaying(track)
            }
            results.setOnItemLongClickListener { _, _, position, _ -> showTrackMenu(items[position]); true }
        }
        showResults(homeTracks)
        var pendingSearch: Runnable? = null
        var searchSerial = 0
        var searchInFlight = false
        var queuedSearch: String? = null
        fun performSearch(q: String) {
            if (overlay !== screen) return
            val serial = ++searchSerial
            if (searchInFlight) {
                queuedSearch = q
                return
            }
            if (!isOnline()) {
                showResults(emptyList())
                showSearchStatus(getString(R.string.offline))
                return
            }
            showSearchStatus(getString(R.string.searching))
            searchInFlight = true
            io.execute {
                val attempt = runCatching {
                    if (isSoundCloudUrl(q)) {
                        webApi.resolveTrackUrl(q)?.let(::listOf).orEmpty()
                    } else if (hasAccount()) {
                        api.searchTracks(q, 30)
                    } else {
                        webApi.searchTracks(q, 30)
                    }
                }
                attempt.exceptionOrNull()?.let { Log.e("CloudWalkSearch", "Search failed", it) }
                main.post {
                    searchInFlight = false
                    if (overlay === screen && serial == searchSerial) {
                        attempt.onSuccess { items ->
                            showResults(items)
                            if (items.isEmpty()) showSearchStatus(getString(R.string.no_results))
                        }.onFailure {
                            showResults(emptyList())
                            showSearchStatus(getString(R.string.search_failed))
                        }
                    }
                    val next = queuedSearch
                    queuedSearch = null
                    if (overlay === screen && !next.isNullOrBlank()) performSearch(next)
                }
            }
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                pendingSearch?.let(main::removeCallbacks)
                val q = newText.orEmpty().trim()
                if (q.length < 2) {
                    searchSerial++
                    queuedSearch = null
                    showResults(if (q.isEmpty()) homeTracks else emptyList())
                } else {
                    pendingSearch = Runnable {
                        if (overlay === screen) performSearch(q)
                    }.also { main.postDelayed(it, 500L) }
                }
                return true
            }
            override fun onQueryTextSubmit(query: String?): Boolean {
                pendingSearch?.let(main::removeCallbacks)
                val q = query.orEmpty().trim()
                if (q.length >= 2) performSearch(q)
                search.clearFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(search.windowToken, 0)
                return true
            }
        })
        showOverlay(screen); search.requestFocus()
    }

    private fun showNowPlaying(track: Track) {
        val cached = nowPlayingScreen
        if (cached != null) {
            refreshNowPlaying(track)
            showOverlay(cached)
            return
        }

        val compactNowPlaying = resources.configuration.screenHeightDp <= 700
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 12, 12)) }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12)); title = getString(R.string.now_playing); setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back)
            setNavigationOnClickListener { closeOverlay() }
            nowPlayingLike = ImageButton(this@MainActivity).apply {
                background = selectableBorderlessBackground()
                contentDescription = getString(R.string.like)
                setOnClickListener {
                    val current = selectedTrack ?: return@setOnClickListener
                    val liked = collections.toggleLike(current)
                    refreshNowPlaying(current)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    toast(if (liked) getString(R.string.added_to_likes) else getString(R.string.removed_from_likes))
                }
            }
            addView(nowPlayingLike, Toolbar.LayoutParams(dp(48), -1).apply { gravity = Gravity.END })
            menu.add(getString(R.string.sleep_timer))
            setOnMenuItemClickListener { item ->
                if (item.title == getString(R.string.sleep_timer)) { showSleepTimerDialog(); true } else false
            }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(if (compactNowPlaying) 10 else 22), dp(26), dp(if (compactNowPlaying) 8 else 18))
        }
        val coverDp = if (compactNowPlaying) 180 else 270
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(45, 45, 45)) }
        nowPlayingArtwork = cover
        content.addView(cover, LinearLayout.LayoutParams(dp(coverDp), dp(coverDp)).apply { bottomMargin = dp(if (compactNowPlaying) 14 else 24) })
        nowPlayingTitle = textLine(if (compactNowPlaying) 21f else 24f, Color.WHITE, true, Gravity.CENTER)
        nowPlayingArtist = textLine(14f, Color.LTGRAY, false, Gravity.CENTER)
        content.addView(nowPlayingTitle, LinearLayout.LayoutParams(-1, dp(if (compactNowPlaying) 32 else 38)))
        content.addView(nowPlayingArtist, LinearLayout.LayoutParams(-1, dp(if (compactNowPlaying) 24 else 28)))
        nowPlayingPosition = textLine(11f, Color.rgb(145, 145, 145), false, Gravity.CENTER)
        content.addView(nowPlayingPosition, LinearLayout.LayoutParams(-1, dp(if (compactNowPlaying) 18 else 22)))

        val seek = SeekBar(this).apply { max = 1000; progress = this@MainActivity.progress.progress }
        nowPlayingSeek = seek
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = playback.duration(); if (duration > 0) playback.seekTo((duration * value / 1000L).toInt())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) { syncMediaSessionAfterSeek() }
        })
        content.addView(seek, LinearLayout.LayoutParams(-1, dp(if (compactNowPlaying) 34 else 42)).apply { topMargin = dp(if (compactNowPlaying) 2 else 8) })
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nowPlayingElapsed = textLine(11f, Color.rgb(160, 160, 160), false, Gravity.START)
        nowPlayingDuration = textLine(11f, Color.rgb(160, 160, 160), false, Gravity.END)
        timeRow.addView(nowPlayingElapsed, LinearLayout.LayoutParams(0, dp(20), 1f))
        timeRow.addView(nowPlayingDuration, LinearLayout.LayoutParams(0, dp(20), 1f))
        content.addView(timeRow, LinearLayout.LayoutParams(-1, dp(20)))

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        nowPlayingPrev = ImageButton(this).apply {
            setImageResource(R.drawable.ic_prev); setColorFilter(Color.WHITE); background = selectableBorderlessBackground(); contentDescription = getString(R.string.previous)
            setOnClickListener { playRelative(-1) }
        }
        controls.addView(nowPlayingPrev, LinearLayout.LayoutParams(dp(64), dp(64)))
        nowPlayingPlay = ImageButton(this).apply {
            setColorFilter(Color.WHITE); background = selectableBorderlessBackground(); contentDescription = getString(R.string.play_pause)
            setOnClickListener {
                val current = selectedTrack
                if (current != null && playback.duration() > 0) playback.toggle() else current?.let { play(it) }
            }
        }
        controls.addView(nowPlayingPlay, LinearLayout.LayoutParams(dp(80), dp(80)))
        nowPlayingNext = ImageButton(this).apply {
            setImageResource(R.drawable.ic_next); setColorFilter(Color.WHITE); background = selectableBorderlessBackground(); contentDescription = getString(R.string.next)
            setOnClickListener { playRelative(1) }
        }
        controls.addView(nowPlayingNext, LinearLayout.LayoutParams(dp(64), dp(64)))
        content.addView(controls, LinearLayout.LayoutParams(-1, dp(if (compactNowPlaying) 72 else 92)).apply { topMargin = dp(if (compactNowPlaying) 2 else 8) })

        val secondary = LinearLayout(this).apply { gravity = Gravity.CENTER }
        nowPlayingShuffle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_shuffle); background = selectableBorderlessBackground(); contentDescription = getString(R.string.shuffle)
            setOnClickListener { toggleShuffle() }
        }
        secondary.addView(nowPlayingShuffle, LinearLayout.LayoutParams(dp(56), dp(48)))
        secondary.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_queue); setColorFilter(Color.LTGRAY); background = selectableBorderlessBackground(); contentDescription = getString(R.string.queue)
            setOnClickListener { showQueue() }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        nowPlayingRepeat = ImageButton(this).apply {
            setImageResource(R.drawable.ic_repeat); background = selectableBorderlessBackground(); contentDescription = getString(R.string.repeat_off)
            setOnClickListener { cycleRepeatMode() }
        }
        secondary.addView(nowPlayingRepeat, LinearLayout.LayoutParams(dp(56), dp(48)))
        content.addView(secondary, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(if (compactNowPlaying) 0 else 2) })
        screen.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        nowPlayingScreen = screen
        refreshNowPlaying(track)
        showOverlay(screen)
    }

    private fun refreshNowPlaying(track: Track) {
        nowPlayingTitle?.text = track.title
        nowPlayingArtist?.text = track.artist
        nowPlayingArtwork?.let { artwork.load(track.artworkUrl, it, dp(if (resources.configuration.screenHeightDp <= 700) 180 else 270)) }
        nowPlayingPlay?.setImageResource(if (playing && selectedTrack?.id == track.id) R.drawable.ic_pause else R.drawable.ic_play)
        val liked = collections.isLiked(track)
        nowPlayingLike?.setImageResource(if (liked) R.drawable.ic_like_filled else R.drawable.ic_like)
        nowPlayingLike?.setColorFilter(if (liked) Color.rgb(255, 123, 38) else Color.WHITE)
        nowPlayingLike?.contentDescription = if (liked) getString(R.string.unlike) else getString(R.string.like)
        val index = homeTracks.indexOfFirst { it.id == track.id }
        nowPlayingPosition?.text = if (index >= 0) getString(R.string.position_of, index + 1, homeTracks.size) else ""
        val canWrap = repeatMode == RepeatMode.ALL && homeTracks.size > 1
        val shuffleIndex = if (shuffleEnabled) shuffledTrackIds.indexOf(track.id) else -1
        val canPrev = if (shuffleEnabled) shuffleIndex > 0 || canWrap else index > 0 || canWrap
        val canNext = if (shuffleEnabled) shuffleIndex >= 0 && (shuffleIndex < shuffledTrackIds.lastIndex || canWrap) else index >= 0 && (index < homeTracks.lastIndex || canWrap)
        nowPlayingPrev?.isEnabled = canPrev
        nowPlayingPrev?.alpha = if (canPrev) 1f else 0.35f
        nowPlayingNext?.isEnabled = canNext
        nowPlayingNext?.alpha = if (canNext) 1f else 0.35f
        nowPlayingShuffle?.setColorFilter(if (shuffleEnabled) Color.rgb(255, 123, 38) else Color.LTGRAY)
        nowPlayingShuffle?.contentDescription = if (shuffleEnabled) getString(R.string.shuffle_on_desc) else getString(R.string.shuffle_off_desc)
        nowPlayingRepeat?.setImageResource(if (repeatMode == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        nowPlayingRepeat?.setColorFilter(if (repeatMode == RepeatMode.OFF) Color.LTGRAY else Color.rgb(255, 123, 38))
        nowPlayingRepeat?.contentDescription = when (repeatMode) {
            RepeatMode.OFF -> getString(R.string.repeat_off)
            RepeatMode.ALL -> getString(R.string.repeat_all)
            RepeatMode.ONE -> getString(R.string.repeat_one)
        }
        nowPlayingElapsed?.text = formatTime(playback.currentPosition())
        val knownDuration = playback.duration().takeIf { it > 0 } ?: track.durationMs.toInt()
        nowPlayingDuration?.text = formatTime(knownDuration)
    }

    private fun updateHomeCollection(items: List<Track>, selectedIndex: Int = 0) {
        if (items.isEmpty()) return
        val snapshot = if (items === homeTracks) ArrayList(items) else items
        homeTracks.clear()
        homeTracks.addAll(snapshot)
        flowView.tracks = homeTracks
        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
        flowView.setSelected(selectedIndex.coerceIn(0, homeTracks.lastIndex), false)
        homeEmptyView.visibility = View.GONE
        trackInfoPanel.visibility = View.VISIBLE
        playerStripView.visibility = View.VISIBLE
        setViewMode(showingFlow)
        rebuildShuffleOrder(selectedTrack?.id)
        updateMediaSessionQueue()
    }

    private fun rebuildShuffleOrder(currentId: String? = selectedTrack?.id) {
        shuffledTrackIds.clear()
        if (!shuffleEnabled || homeTracks.isEmpty()) return
        val ids = homeTracks.map { it.id }.toMutableList()
        currentId?.let { ids.remove(it) }
        ids.shuffle()
        if (currentId != null && homeTracks.any { it.id == currentId }) shuffledTrackIds.add(currentId)
        shuffledTrackIds.addAll(ids)
    }

    private fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        rebuildShuffleOrder(selectedTrack?.id)
        updateMediaSessionQueue()
        selectedTrack?.let(::refreshNowPlaying)
        toast(if (shuffleEnabled) getString(R.string.shuffle_on) else getString(R.string.shuffle_off))
    }

    private fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        selectedTrack?.let(::refreshNowPlaying)
        toast(when (repeatMode) { RepeatMode.OFF -> getString(R.string.repeat_off); RepeatMode.ALL -> getString(R.string.repeat_all); RepeatMode.ONE -> getString(R.string.repeat_one) })
    }

    private fun playRelative(delta: Int) {
        if (homeTracks.isEmpty()) return
        val currentId = selectedTrack?.id ?: homeTracks.first().id
        val targetId = if (shuffleEnabled && homeTracks.size > 1) {
            if (shuffledTrackIds.size != homeTracks.size || !shuffledTrackIds.contains(currentId)) rebuildShuffleOrder(currentId)
            val current = shuffledTrackIds.indexOf(currentId).coerceAtLeast(0)
            var next = current + delta
            if (repeatMode == RepeatMode.ALL) next = (next % shuffledTrackIds.size + shuffledTrackIds.size) % shuffledTrackIds.size
            if (next !in shuffledTrackIds.indices) return
            shuffledTrackIds[next]
        } else {
            val current = homeTracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            var next = current + delta
            if (repeatMode == RepeatMode.ALL && homeTracks.size > 1) next = (next % homeTracks.size + homeTracks.size) % homeTracks.size
            if (next !in homeTracks.indices) return
            homeTracks[next].id
        }
        val nextIndex = homeTracks.indexOfFirst { it.id == targetId }
        if (nextIndex < 0) return
        flowView.setSelected(nextIndex, false)
        play(homeTracks[nextIndex])
        refreshNowPlaying(homeTracks[nextIndex])
    }

    private fun showQueue() {
        val queueItems = if (shuffleEnabled && shuffledTrackIds.isNotEmpty()) {
            shuffledTrackIds.mapNotNull { id -> homeTracks.firstOrNull { it.id == id } }
        } else homeTracks.toList()
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 12, 12)) }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12))
            title = if (shuffleEnabled) getString(R.string.queue_shuffled_count, queueItems.size) else getString(R.string.queue_count, queueItems.size)
            setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back); setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        if (queueItems.isEmpty()) {
            screen.addView(TextView(this).apply { text = getString(R.string.queue_empty); gravity = Gravity.CENTER; setTextColor(Color.GRAY) }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val list = ListView(this).apply {
                divider = ColorDrawable(Color.rgb(38, 38, 38)); dividerHeight = 1; selector = selectableBackground(); isVerticalScrollBarEnabled = false
                adapter = TrackAdapter(queueItems); installArtworkScrollPolicy(this)
                setOnItemClickListener { _, _, position, _ ->
                    val track = queueItems[position]
                    val homeIndex = homeTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                    flowView.setSelected(homeIndex, false)
                    play(track)
                    closeOverlay()
                    showNowPlaying(track)
                }
                setOnItemLongClickListener { _, _, position, _ -> showTrackMenu(queueItems[position], allowRemoveFromQueue = true); true }
            }
            screen.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        showOverlay(screen)
    }

    private fun showTrackMenu(track: Track, position: Int = homeTracks.indexOfFirst { it.id == track.id }, allowRemoveFromQueue: Boolean = false) {
        val actions = ArrayList<String>()
        actions.add(getString(R.string.play))
        if (selectedTrack != null && selectedTrack?.id != track.id) actions.add(getString(R.string.play_next))
        actions.add(if (collections.isLiked(track)) getString(R.string.remove_from_likes) else getString(R.string.add_to_likes))
        if (playback.canSessionCache(track)) actions.add(if (playback.isSessionCached(track)) getString(R.string.remove_session_cache) else getString(R.string.keep_for_session))
        if (!track.permalinkUrl.isNullOrBlank()) actions.add(getString(R.string.artist_tracks))
        if (!track.permalinkUrl.isNullOrBlank()) actions.add(getString(R.string.related_tracks))
        if (!track.permalinkUrl.isNullOrBlank()) {
            actions.add(getString(R.string.open_soundcloud))
            actions.add(getString(R.string.share_track))
        }
        if (allowRemoveFromQueue && selectedTrack?.id != track.id) actions.add(getString(R.string.remove_from_queue))
        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.play) -> play(track)
                    getString(R.string.play_next) -> {
                        val current = homeTracks.indexOfFirst { it.id == selectedTrack?.id }.coerceAtLeast(0)
                        val existing = homeTracks.indexOfFirst { it.id == track.id }
                        if (existing >= 0) homeTracks.removeAt(existing)
                        val insert = (current + 1).coerceAtMost(homeTracks.size)
                        homeTracks.add(insert, track)
                        flowView.tracks = homeTracks
                        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                        rebuildShuffleOrder(selectedTrack?.id)
                        if (shuffleEnabled) {
                            shuffledTrackIds.remove(track.id)
                            val shuffleCurrent = shuffledTrackIds.indexOf(selectedTrack?.id).coerceAtLeast(0)
                            shuffledTrackIds.add((shuffleCurrent + 1).coerceAtMost(shuffledTrackIds.size), track.id)
                        }
                        updateMediaSessionQueue()
                        toast(getString(R.string.playing_next))
                    }
                    getString(R.string.add_to_likes), getString(R.string.remove_from_likes) -> { collections.toggleLike(track); toast(if (collections.isLiked(track)) getString(R.string.added_to_likes) else getString(R.string.removed_from_likes)) }
                    getString(R.string.keep_for_session) -> playback.keepForSession(track) { _, message -> toast(message) }
                    getString(R.string.remove_session_cache) -> { sessionCache.remove(track); toast(getString(R.string.removed_session_cache)) }
                    getString(R.string.artist_tracks) -> {
                        if (!isOnline()) { toast(getString(R.string.offline)); return@setItems }
                        toast(getString(R.string.loading))
                        io.execute {
                            val result = runCatching { webApi.artistTracks(track, 30) }
                            main.post {
                                result.onSuccess { items -> showStoredTrackScreen(getString(R.string.artist_tracks), items, getString(R.string.nothing_here)) }
                                    .onFailure { toast(getString(R.string.couldnt_load_section, getString(R.string.artist_tracks))) }
                            }
                        }
                    }
                    getString(R.string.related_tracks) -> {
                        if (!isOnline()) { toast(getString(R.string.offline)); return@setItems }
                        toast(getString(R.string.loading))
                        io.execute {
                            val result = runCatching { webApi.relatedTracks(track, 30) }
                            main.post {
                                result.onSuccess { items -> showStoredTrackScreen(getString(R.string.related_tracks), items, getString(R.string.nothing_here)) }
                                    .onFailure { toast(getString(R.string.couldnt_load_section, getString(R.string.related_tracks))) }
                            }
                        }
                    }
                    getString(R.string.open_soundcloud) -> track.permalinkUrl?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) } }
                    getString(R.string.share_track) -> track.permalinkUrl?.let { url ->
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        startActivity(Intent.createChooser(share, getString(R.string.share_track)))
                    }
                    getString(R.string.remove_from_queue) -> {
                        homeTracks.removeAll { it.id == track.id }
                        shuffledTrackIds.remove(track.id)
                        flowView.tracks = homeTracks
                        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                        updateMediaSessionQueue()
                        setViewMode(showingFlow)
                        toast(getString(R.string.removed_from_queue))
                        closeOverlay()
                        showQueue()
                    }
                }
            }.show()
    }

    private fun showOverlay(view: View) {
        val current = overlay
        if (current === view) return
        overlayStack.remove(view)
        if (current != null) {
            (current.parent as? ViewGroup)?.removeView(current)
            overlayStack.addLast(current)
            while (overlayStack.size > 6) overlayStack.removeFirst()
        }
        overlay = view
        attachOverlay(view)
    }

    private fun replaceOverlay(view: View) {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = view
        attachOverlay(view)
    }

    private fun attachOverlay(view: View) {
        val base = overlayBasePadding.getOrPut(view) {
            intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        }
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = systemBarInsets(insets)
            target.setPadding(base[0], base[1] + bars[0], base[2], base[3] + bars[1])
            insets
        }
        addContentView(view, ViewGroup.LayoutParams(-1, -1))
        view.requestApplyInsets()
    }

    private fun systemBarInsets(insets: android.view.WindowInsets): IntArray {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            intArrayOf(bars.top, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            intArrayOf(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
        }
    }

    private fun closeOverlay() {
        val current = overlay ?: return
        (current.parent as? ViewGroup)?.removeView(current)
        overlay = null
        if (overlayStack.isNotEmpty()) {
            val previous = overlayStack.removeLast()
            overlay = previous
            attachOverlay(previous)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { handleBackAction() }

    private fun showViewMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, getString(R.string.cover_flow)); menu.add(0, 2, 1, getString(R.string.track_list))
            menu.add(0, 3, 2, if (lowPowerMode) getString(R.string.disable_low_power) else getString(R.string.enable_low_power))
            menu.add(0, 5, 3, getString(R.string.session_cache_size))
            menu.add(0, 6, 4, getString(R.string.sleep_timer))
            selectedTrack?.takeIf { playback.canSessionCache(it) }?.let { track ->
                menu.add(0, 4, 4, if (playback.isSessionCached(track)) getString(R.string.cached_for_session) else getString(R.string.keep_for_session))
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> setViewMode(true)
                    2 -> setViewMode(false)
                    3 -> {
                        lowPowerMode = !lowPowerMode
                        flowView.lowPowerMode = lowPowerMode
                        if (playing) startProgressTicker()
                        toast(if (lowPowerMode) getString(R.string.low_power_on) else getString(R.string.low_power_off))
                    }
                    5 -> showCacheSizeDialog()
                    6 -> showSleepTimerDialog()
                    4 -> {
                        val track = selectedTrack
                        if (track != null) {
                            toast(getString(R.string.caching_session))
                            playback.keepForSession(track) { ok, message -> toast(message) }
                        }
                    }
                }
                true
            }
            show()
        }
    }


    private fun showSleepTimerDialog() {
        val labels = arrayOf(
            getString(R.string.sleep_off),
            getString(R.string.sleep_15),
            getString(R.string.sleep_30),
            getString(R.string.sleep_60),
            getString(R.string.sleep_end_track)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sleep_timer))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> cancelSleepTimer(true)
                    1 -> setSleepTimer(15, labels[1])
                    2 -> setSleepTimer(30, labels[2])
                    3 -> setSleepTimer(60, labels[3])
                    4 -> {
                        cancelSleepTimer(false)
                        sleepAtTrackEnd = true
                        toast(getString(R.string.sleep_set, labels[4]))
                    }
                }
            }.show()
    }

    private fun setSleepTimer(minutes: Int, label: String) {
        cancelSleepTimer(false)
        sleepTimerRunnable = Runnable {
            playback.pause()
            sleepTimerRunnable = null
            sleepAtTrackEnd = false
        }.also { main.postDelayed(it, minutes * 60_000L) }
        toast(getString(R.string.sleep_set, label))
    }

    private fun cancelSleepTimer(showToast: Boolean) {
        sleepTimerRunnable?.let(main::removeCallbacks)
        sleepTimerRunnable = null
        sleepAtTrackEnd = false
        if (showToast) toast(getString(R.string.sleep_cancelled))
    }

    private fun showCacheSizeDialog() {
        val labels = ArrayList<String>()
        CacheSettings.PRESETS_MB.forEach { labels.add("$it MB") }
        labels.add(getString(R.string.custom))
        labels.add(getString(R.string.clear_session_cache))
        val currentMb = sessionCache.maxBytes() / (1024L * 1024L)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.session_cache_title, currentMb))
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < CacheSettings.PRESETS_MB.size -> {
                        val mb = CacheSettings.PRESETS_MB[which]
                        sessionCache.setMaxBytes(mb.toLong() * 1024L * 1024L)
                        toast(getString(R.string.session_cache_set, mb))
                    }
                    which == CacheSettings.PRESETS_MB.size -> showCustomCacheDialog()
                    else -> { sessionCache.clear(); toast(getString(R.string.session_cache_cleared)) }
                }
            }
            .show()
    }

    private fun showCustomCacheDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "MB"
            setText((sessionCache.maxBytes() / (1024L * 1024L)).toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.custom_cache_size))
            .setMessage(getString(R.string.cache_minimum))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val mb = input.text.toString().toLongOrNull()?.coerceIn(16L, 2048L) ?: return@setPositiveButton
                sessionCache.setMaxBytes(mb * 1024L * 1024L)
                toast(getString(R.string.session_cache_set, mb))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun installSwipeToCache(list: ListView, items: List<Track>) {
        var downX = 0f
        var downY = 0f
        var activeRow: View? = null
        var activeTrack: Track? = null
        var horizontal = false
        var thresholdHaptic = false
        val trigger = dp(86).toFloat()
        val maxShift = dp(132).toFloat()

        list.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    horizontal = false
                    thresholdHaptic = false
                    val position = list.pointToPosition(event.x.toInt(), event.y.toInt())
                    if (position != AdapterView.INVALID_POSITION && position < items.size) {
                        val candidate = items[position]
                        if (playback.canSessionCache(candidate)) {
                            activeTrack = candidate
                            val container = list.getChildAt(position - list.firstVisiblePosition)
                            activeRow = container?.findViewById(android.R.id.content) ?: container
                        } else {
                            activeTrack = null
                            activeRow = null
                        }
                    } else {
                        activeTrack = null
                        activeRow = null
                    }
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!horizontal && dx > dp(10) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f) {
                        horizontal = true
                        list.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal && dx > 0f) {
                        val shift = dx.coerceAtMost(maxShift)
                        activeRow?.translationX = shift
                        activeRow?.alpha = 1f - 0.12f * (shift / maxShift)
                        if (shift >= trigger && !thresholdHaptic) {
                            thresholdHaptic = true
                            list.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        } else if (shift < trigger * 0.8f) {
                            thresholdHaptic = false
                        }
                        true
                    } else false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (horizontal) {
                        val dx = event.x - downX
                        val row = activeRow
                        val track = activeTrack
                        if (event.actionMasked == android.view.MotionEvent.ACTION_UP && dx >= trigger && track != null && !track.streamUrl.isNullOrBlank()) {
                            if (row != null) {
                                row.animate().translationX(maxShift).alpha(0.88f).setDuration(90).withEndAction {
                                    row.animate().translationX(0f).alpha(1f).setDuration(150).start()
                                }.start()
                            }
                            if (playback.isSessionCached(track)) {
                                toast(getString(R.string.already_cached))
                            } else {
                                toast(getString(R.string.caching_session))
                                val action = (row?.parent as? View)?.findViewById<TextView>(android.R.id.hint)
                                playback.keepForSession(track, { percent ->
                                    action?.text = getString(R.string.caching_percent, percent)
                                }) { ok, message ->
                                    action?.text = if (ok) getString(R.string.cached_badge) else getString(R.string.cache_badge)
                                    toast(message)
                                }
                            }
                        } else {
                            row?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(140)?.start()
                        }
                        list.requestDisallowInterceptTouchEvent(false)
                        activeRow = null
                        activeTrack = null
                        horizontal = false
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun setViewMode(flow: Boolean) {
        showingFlow = flow && !lowPowerMode
        if (homeTracks.isEmpty()) {
            flowView.visibility = View.GONE
            listView.visibility = View.GONE
            homeEmptyView.visibility = View.VISIBLE
            trackInfoPanel.visibility = View.GONE
            playerStripView.visibility = View.GONE
            return
        }
        homeEmptyView.visibility = View.GONE
        flowView.visibility = if (showingFlow) View.VISIBLE else View.GONE
        listView.visibility = if (showingFlow) View.GONE else View.VISIBLE
    }

    private fun play(track: Track) {
        collections.addRecent(track)
        showTrack(track)
        playback.play(track)
    }

    private fun showTrack(track: Track) {
        val changed = selectedTrack?.id != track.id
        selectedTrack = track
        if (::trackInfoPanel.isInitialized) trackInfoPanel.visibility = View.VISIBLE
        if (::playerStripView.isInitialized) playerStripView.visibility = View.VISIBLE
        titleView.text = track.title
        artistView.text = track.artist
        miniTitleView.text = track.title
        miniArtistView.text = track.artist
        artwork.load(track.artworkUrl, miniArtwork, dp(48))
        if (changed) {
            (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
            updateMediaSession()
        }
    }

    private fun updatePlayButton() {
        if (::playButton.isInitialized) playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        selectedTrack?.let { refreshNowPlaying(it) }
    }

    private fun startProgressTicker() {
        stopProgressTicker()
        if (!playing) return
        progressTicker = object : Runnable {
            override fun run() {
                if (!playing) return
                val duration = playback.duration()
                val newProgress = if (duration > 0) (playback.currentPosition() * 1000L / duration).toInt() else 0
                if (progress.progress != newProgress) progress.progress = newProgress
                if (nowPlayingSeek?.progress != newProgress) nowPlayingSeek?.progress = newProgress
                nowPlayingElapsed?.text = formatTime(playback.currentPosition())
                if (nowPlayingDuration?.text.isNullOrEmpty()) nowPlayingDuration?.text = formatTime(duration)
                main.postDelayed(this, if (lowPowerMode) 1500 else 750)
            }
        }.also(main::post)
    }

    private fun stopProgressTicker() {
        progressTicker?.let(main::removeCallbacks)
        progressTicker = null
    }

    override fun onBuffering(track: Track) { showTrack(track); stopProgressTicker() }
    override fun onReady(track: Track, durationMs: Int) { showTrack(track) }
    override fun onPlayingChanged(track: Track, playing: Boolean) {
        this.playing = playing
        updatePlayButton()
        if (playing) {
            startProgressTicker()
            startPlaybackKeepAlive()
        } else {
            stopProgressTicker()
            schedulePlaybackKeepAliveStop()
        }
        updateMediaSession()
    }
    override fun onCompleted(track: Track) {
        playing = false
        stopProgressTicker()
        if (sleepAtTrackEnd) {
            sleepAtTrackEnd = false
            cancelSleepTimer(false)
            updatePlayButton()
            updateMediaSession()
            schedulePlaybackKeepAliveStop()
            return
        }
        progress.progress = 0
        nowPlayingSeek?.progress = 0
        if (repeatMode == RepeatMode.ONE) {
            play(track)
            return
        }
        val index = homeTracks.indexOfFirst { it.id == track.id }
        if (shuffleEnabled || (index >= 0 && index < homeTracks.lastIndex) || repeatMode == RepeatMode.ALL) {
            playRelative(1)
        } else {
            updatePlayButton()
            updateMediaSession()
        }
    }
    override fun onError(track: Track?, message: String) {
        playing = false
        stopProgressTicker()
        schedulePlaybackKeepAliveStop()
        updatePlayButton()
        updateMediaSession()
        toast(message)
    }

    private fun startPlaybackKeepAlive() {
        playbackServiceStop?.let(main::removeCallbacks)
        playbackServiceStop = null
        if (!::mediaSession.isInitialized) return
        val intent = Intent(this, PlaybackKeepAliveService::class.java)
            .putExtra(PlaybackKeepAliveService.EXTRA_SESSION_TOKEN, mediaSession.sessionToken)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun schedulePlaybackKeepAliveStop() {
        playbackServiceStop?.let(main::removeCallbacks)
        playbackServiceStop = Runnable {
            stopService(Intent(this, PlaybackKeepAliveService::class.java))
            playbackServiceStop = null
        }.also { main.postDelayed(it, 10_000L) }
    }

    private fun stopPlaybackKeepAliveNow() {
        playbackServiceStop?.let(main::removeCallbacks)
        playbackServiceStop = null
        stopService(Intent(this, PlaybackKeepAliveService::class.java))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        artwork.trimMemory(level)
    }

    override fun onDestroy() {
        stopProgressTicker()
        cancelSleepTimer(false)
        stopPlaybackKeepAliveNow()
        playback.release()
        sessionCache.close()
        artwork.close()
        if (::mediaSession.isInitialized) mediaSession.release()
        io.shutdownNow()
        super.onDestroy()
    }

    private inner class TrackAdapter(private val items: List<Track>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Track = items[position]
        override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toUInt().toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                minimumHeight = dp(64)
                addView(TextView(this@MainActivity).apply {
                    id = android.R.id.hint
                    text = getString(R.string.cache_badge)
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), 0, 0, 0)
                    setBackgroundColor(Color.rgb(205, 86, 26))
                }, FrameLayout.LayoutParams(-1, -1))
                addView(LinearLayout(this@MainActivity).apply {
                    id = android.R.id.content
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), 0, dp(14), 0)
                    setBackgroundColor(Color.rgb(12, 12, 12))
                    foreground = selectableBackground()
                    addView(ImageView(this@MainActivity).apply {
                        id = android.R.id.icon1
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.rgb(44, 44, 44))
                    }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(textLine(15f, Color.WHITE, true, Gravity.START).apply { id = android.R.id.text1 })
                        addView(textLine(12f, Color.rgb(155, 155, 155), false, Gravity.START).apply { id = android.R.id.text2 })
                    }, LinearLayout.LayoutParams(0, -1, 1f))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            val foreground = row.findViewById<View>(android.R.id.content)
            foreground.translationX = 0f
            foreground.alpha = 1f
            val track = getItem(position)
            val title = row.findViewById<TextView>(android.R.id.text1)
            val isCurrent = selectedTrack?.id == track.id
            title.text = if (isCurrent) "▶  ${track.title}" else track.title
            title.setTextColor(if (isCurrent) Color.rgb(255, 123, 38) else Color.WHITE)
            row.findViewById<TextView>(android.R.id.text2).text = track.artist
            val action = row.findViewById<TextView>(android.R.id.hint)
            when {
                !track.localUri.isNullOrBlank() -> { action.text = getString(R.string.local_badge); action.setBackgroundColor(Color.rgb(72, 72, 72)) }
                playback.isSessionCached(track) -> { action.text = getString(R.string.cached_badge); action.setBackgroundColor(Color.rgb(72, 110, 72)) }
                else -> { action.text = getString(R.string.cache_badge); action.setBackgroundColor(Color.rgb(205, 86, 26)) }
            }
            val art = row.findViewById<ImageView>(android.R.id.icon1)
            if (deferArtworkLoads) {
                art.tag = null
                art.setImageDrawable(null)
            } else {
                artwork.load(track.artworkUrl, art, dp(48))
            }
            return row
        }
    }

    private fun installArtworkScrollPolicy(list: ListView) {
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                val shouldDefer = scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (deferArtworkLoads == shouldDefer) return
                deferArtworkLoads = shouldDefer
                if (!shouldDefer) (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
            }
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) = Unit
        })
    }

    private fun textLine(size: Float, color: Int, bold: Boolean, gravityValue: Int) = TextView(this).apply {
        textSize = size; setTextColor(color); gravity = gravityValue; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.create("sans", Typeface.BOLD)
    }

    private fun formatTime(ms: Int): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val minutes = total / 60
        val seconds = total % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun selectableBackground() = resolveDrawable(android.R.attr.selectableItemBackground)
    private fun selectableBorderlessBackground() = resolveDrawable(android.R.attr.selectableItemBackgroundBorderless)
    private fun resolveDrawable(attr: Int) = TypedValue().let { value -> theme.resolveAttribute(attr, value, true); getDrawable(value.resourceId) }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
