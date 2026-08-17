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
    private val SEARCH_PAGE_SIZE = 30
    private val MAX_SEARCH_RESULTS = 120

    private lateinit var api: SoundCloudApi
    private lateinit var webApi: WebSoundCloudApi
    private lateinit var playback: PlaybackController
    private lateinit var sessionCache: SessionAudioCache
    private lateinit var cacheSettings: CacheSettings
    private lateinit var artwork: ArtworkCache
    private lateinit var authBroker: AuthBroker
    private lateinit var authSession: AuthSession
    private lateinit var collections: LocalCollections
    private lateinit var localLibrary: LocalLibrary
    private lateinit var mediaSession: MediaSession
    private lateinit var uiPrefs: android.content.SharedPreferences
    private val authRedirectUri = "cloudwalk://auth/callback"

    private lateinit var flowView: CoverFlowView
    private lateinit var listView: ListView
    private lateinit var contentHost: FrameLayout
    private lateinit var homeEmptyView: TextView
    private lateinit var homeSectionLabel: TextView
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
    private var focusedTrack: Track? = null
    private var playing = false
    private var showingFlow = true
    private var lowPowerMode = false
    private var progressTicker: Runnable? = null
    private var activityVisible = false
    private var playbackServiceStop: Runnable? = null
    private var sleepTimerRunnable: Runnable? = null
    private var sleepAtTrackEnd = false
    private var overlay: View? = null
    private val overlayStack = ArrayDeque<View>()
    private var lastBackHandledAt = 0L
    private var overlayTransitionRunning = false
    private var lastNowPlayingTrackId: String? = null
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
    private var tabMiniRefresh: (() -> Unit)? = null
    private val cachingTrackIds = HashSet<String>()
    private var currentTopTab = 0
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
        localLibrary = LocalLibrary(this)
        uiPrefs = getSharedPreferences("cloudwalk_ui", MODE_PRIVATE)
        lowPowerMode = uiPrefs.getBoolean("low_power", false)
        showingFlow = uiPrefs.getBoolean("home_flow", true) && !lowPowerMode
        shuffleEnabled = uiPrefs.getBoolean("shuffle", false)
        repeatMode = RepeatMode.entries.getOrElse(uiPrefs.getInt("repeat_mode", 0)) { RepeatMode.OFF }
        val savedQueue = collections.queue()
        val recentHome = collections.recent()
        val startupQueue = if (savedQueue.isNotEmpty()) savedQueue else recentHome.take(30)
        if (startupQueue.isNotEmpty()) {
            homeTracks.clear()
            homeTracks.addAll(startupQueue)
        }
        cacheSettings = CacheSettings(this)
        sessionCache = SessionAudioCache(this, cacheSettings)
        playback = PlaybackController(this, api, webApi, sessionCache).also { it.listener = this }
        artwork = ArtworkCache(this)
        setupMediaSession()
        setContentView(buildUi())
        flowView.lowPowerMode = lowPowerMode
        setViewMode(showingFlow)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBackAction() }
        }
        if (homeTracks.isNotEmpty()) {
            val lastId = uiPrefs.getString("last_track_id", null)
            val initialIndex = homeTracks.indexOfFirst { it.id == lastId }.takeIf { it >= 0 } ?: 0
            val initialTrack = homeTracks[initialIndex]
            flowView.setSelected(initialIndex, false)
            showTrack(initialTrack)
            if (shuffleEnabled) rebuildShuffleOrder(initialTrack.id)
            updateMediaSessionQueue()
            updateMediaSession()
        }
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
        if (overlayTransitionRunning) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackHandledAt < 150L) return
        lastBackHandledAt = now
        if (overlay != null) {
            closeOverlay()
        } else if (playing) {
            moveTaskToBack(true)
        } else {
            finish()
        }
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
            val result = runCatching { webApi.resolvePublicUrl(url) }
            main.post {
                result.onSuccess { resolved ->
                    when {
                        resolved.track != null -> {
                            updateHomeCollection(listOf(resolved.track), 0)
                            play(resolved.track)
                            showNowPlaying(resolved.track)
                        }
                        resolved.profile != null -> showPublicProfileActions(resolved.profile)
                        else -> toast(getString(R.string.not_public_track))
                    }
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

    private fun updateQueueFlowHeader() {
        if (!::homeSectionLabel.isInitialized) return
        val flow = showingFlow && !lowPowerMode
        homeSectionLabel.text = if (flow) {
            getString(R.string.queue_flow_count, homeTracks.size)
        } else {
            getString(R.string.track_list_count, homeTracks.size)
        }
        homeSectionLabel.isEnabled = flow && homeTracks.isNotEmpty()
        homeSectionLabel.isClickable = flow && homeTracks.isNotEmpty()
        homeSectionLabel.isFocusable = flow && homeTracks.isNotEmpty()
        homeSectionLabel.contentDescription = if (flow) getString(R.string.queue_flow_open_desc) else getString(R.string.track_list)
        homeSectionLabel.alpha = if (homeTracks.isNotEmpty()) 1f else 0.5f
    }

    private fun currentQueueOrder(): List<Track> =
        if (shuffleEnabled && shuffledTrackIds.isNotEmpty()) shuffledTrackIds.mapNotNull { id -> homeTracks.firstOrNull { it.id == id } } else homeTracks

    private fun updateMediaSessionQueue() {
        updateQueueFlowHeader()
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
        val queue = currentQueueOrder()
        val activeIndex = track?.let { current -> queue.indexOfFirst { it.id == current.id } } ?: -1
        val canWrap = repeatMode == RepeatMode.ALL && queue.size > 1
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO
        if (queue.isNotEmpty()) actions = actions or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
        if (activeIndex > 0 || canWrap) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if ((activeIndex >= 0 && activeIndex < queue.lastIndex) || canWrap) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        val state = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                playback.currentPosition().toLong(),
                if (playing) 1f else 0f
            )
        if (activeIndex >= 0) state.setActiveQueueItemId(activeIndex.toLong())
        mediaSession.setPlaybackState(state.build())
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
        homeSectionLabel = TextView(this).apply {
            text = getString(R.string.queue_flow_count, homeTracks.size)
            textSize = 12f
            setTextColor(Color.rgb(174, 174, 174))
            typeface = Typeface.create("sans", Typeface.BOLD)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            background = selectableBackground()
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.queue_flow_open_desc)
            setOnClickListener { if (homeTracks.isNotEmpty()) showQueue() }
        }
        root.addView(homeSectionLabel, LinearLayout.LayoutParams(-1, dp(38)))

        contentHost = FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))

        flowView = CoverFlowView(this).apply {
            artworkCache = this@MainActivity.artwork
            tracks = this@MainActivity.homeTracks
            onSelectionChanged = { _, track -> showFocusedTrack(track) }
            onTrackClick = { track -> play(track) }
            onTrackLongClick = { track -> showTrackMenu(track) }
            playingTrackId = selectedTrack?.id
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

        playerStripView = buildPlayerStrip().apply { visibility = if (selectedTrack == null) View.GONE else View.VISIBLE }
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
            setImageResource(R.drawable.ic_view_options)
            setColorFilter(Color.WHITE)
            background = selectableBorderlessBackground()
            contentDescription = getString(R.string.view_options)
            setOnClickListener { showViewMenu() }
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
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val duration = playback.duration()
                    val value = seekBar?.progress ?: return
                    if (duration > 0) playback.seekTo((duration * value / 1000L).toInt())
                    syncMediaSessionAfterSeek()
                }
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
        installPressMotion(playButton, 0.88f)
        installPressMotion(row, 0.985f)
        addView(row, LinearLayout.LayoutParams(-1, dp(52)))
        updatePlayButton()
    }

    private fun buildTabMiniPlayer(canShow: () -> Boolean = { true }): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
        setBackgroundColor(Color.rgb(22, 22, 22))
        background = selectableBackground()
        isClickable = true
        isFocusable = true

        val art = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(44, 44, 44))
        }
        addView(art, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })

        val text = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = textLine(13f, Color.WHITE, true, Gravity.START or Gravity.CENTER_VERTICAL)
        val artist = textLine(11f, Color.rgb(150, 150, 150), false, Gravity.START or Gravity.CENTER_VERTICAL)
        text.addView(title, LinearLayout.LayoutParams(-1, dp(22)))
        text.addView(artist, LinearLayout.LayoutParams(-1, dp(18)))
        addView(text, LinearLayout.LayoutParams(0, -1, 1f))

        val button = ImageButton(this@MainActivity).apply {
            setColorFilter(Color.WHITE)
            background = selectableBorderlessBackground()
            contentDescription = getString(R.string.play_pause)
            setOnClickListener {
                if (playback.duration() > 0) playback.toggle() else selectedTrack?.let { play(it) }
            }
        }
        addView(button, LinearLayout.LayoutParams(dp(48), -1))
        installPressMotion(button, 0.88f)
        installPressMotion(this, 0.99f)
        setOnClickListener { selectedTrack?.let(::showNowPlaying) }

        val refresh = {
            val track = selectedTrack
            val hasSession = track != null && (playing || playback.duration() > 0) && canShow()
            setMotionVisibility(this, hasSession, 6)
            if (track != null && hasSession) {
                title.text = track.title
                artist.text = track.artist
                artwork.load(track.artworkUrl, art, dp(40))
                setMotionIcon(button, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            }
        }
        tabMiniRefresh = refresh
        refresh()
    }

    private fun buildBottomNav(activeTab: Int = 0): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(17, 17, 17))
        addView(navItem(R.drawable.ic_home, getString(R.string.home), activeTab == 0) { showHomeTab() }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(navItem(R.drawable.ic_search, getString(R.string.search), activeTab == 1) { showSearch() }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(navItem(R.drawable.ic_like, getString(R.string.likes), activeTab == 2) { showLikes() }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(navItem(R.drawable.ic_library, getString(R.string.library), activeTab == 3) { showLibrary() }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun navItem(iconRes: Int, label: String, active: Boolean, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = selectableBackground()
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        installPressMotion(this, 0.94f)
        val icon = ImageView(this@MainActivity).apply {
            setImageResource(iconRes)
            setColorFilter(if (active) Color.rgb(255, 123, 38) else Color.rgb(165, 165, 165))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = label
        }
        addView(icon, LinearLayout.LayoutParams(-1, dp(30)))
        val tabLabel = TextView(this@MainActivity).apply {
            text = label
            textSize = 11f
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            typeface = Typeface.create("sans", if (active) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (active) Color.WHITE else Color.rgb(148, 148, 148))
        }
        addView(tabLabel, LinearLayout.LayoutParams(-1, dp(24)))
        if (active && !lowPowerMode) {
            icon.scaleX = 0.82f
            icon.scaleY = 0.82f
            icon.alpha = 0.65f
            tabLabel.alpha = 0.7f
            post {
                icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(145L).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
                tabLabel.animate().alpha(1f).translationY(0f).setDuration(145L).start()
            }
        }
    }

    private fun installPressMotion(view: View, pressedScale: Float = 0.92f) {
        view.setOnTouchListener { target, event ->
            if (lowPowerMode || !target.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .alpha(0.82f)
                        .setDuration(65L)
                        .start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(115L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                        .start()
                }
            }
            false
        }
    }

    private fun setMotionIcon(button: ImageButton, resId: Int) {
        if (button.tag == resId) return
        button.tag = resId
        button.setImageResource(resId)
        if (lowPowerMode || !button.isLaidOut) {
            button.scaleX = 1f
            button.scaleY = 1f
            button.alpha = 1f
            return
        }
        button.animate().cancel()
        button.scaleX = 0.76f
        button.scaleY = 0.76f
        button.alpha = 0.55f
        button.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(135L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .start()
    }

    private fun setMotionVisibility(view: View, visible: Boolean, distanceDp: Int = 6) {
        if (lowPowerMode || !view.isLaidOut) {
            view.animate().cancel()
            view.visibility = if (visible) View.VISIBLE else View.GONE
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        if (visible) {
            if (view.visibility == View.VISIBLE) return
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = dp(distanceDp).toFloat()
            view.scaleX = 1f
            view.scaleY = 1f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f))
                .start()
        } else if (view.visibility == View.VISIBLE) {
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .translationY(dp(distanceDp / 2).toFloat())
                .setDuration(95L)
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.translationY = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
                .start()
        }
    }

    private fun resetMotionState(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun animateNowPlayingTrackChange() {
        val cover = nowPlayingArtwork
        val title = nowPlayingTitle
        val artist = nowPlayingArtist
        cover?.animate()?.cancel()
        title?.animate()?.cancel()
        artist?.animate()?.cancel()
        cover?.apply {
            scaleX = 0.955f
            scaleY = 0.955f
            alpha = 0.62f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f)).start()
        }
        title?.apply {
            translationY = dp(5).toFloat()
            alpha = 0.35f
            animate().translationY(0f).alpha(1f).setDuration(155L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f)).start()
        }
        artist?.apply {
            translationY = dp(4).toFloat()
            alpha = 0.3f
            animate().translationY(0f).alpha(1f).setStartDelay(20L).setDuration(155L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f)).start()
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { focused ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }

    private fun showHomeTab() {
        val current = overlay
        overlay = null
        overlayStack.clear()
        currentTopTab = 0
        deferArtworkLoads = false
        tabMiniRefresh = null
        syncHomeSurfaceVisibility()
        if (current != null) {
            if (lowPowerMode) {
                (current.parent as? ViewGroup)?.removeView(current)
            } else {
                current.animate().cancel()
                current.animate()
                    .alpha(0f)
                    .translationX(dp(12).toFloat())
                    .setDuration(120L)
                    .withEndAction { (current.parent as? ViewGroup)?.removeView(current) }
                    .start()
            }
        }
    }


    private fun hasAccount(): Boolean = authSession.hasAccount()

    private fun showLikes() {
        showStoredTrackScreen(getString(R.string.likes), collections.likes(), getString(R.string.no_liked_tracks), topLevelTab = 2)
    }

    private fun showStoredTrackScreen(title: String, items: List<Track>, emptyMessage: String, topLevelTab: Int? = null) {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 12, 12))
        }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12)); this.title = title; setTitleTextColor(Color.WHITE)
            if (topLevelTab == null) {
                setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back)
                setNavigationOnClickListener { closeOverlay() }
            }
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
        if (topLevelTab != null) {
            screen.addView(buildTabMiniPlayer(), LinearLayout.LayoutParams(-1, dp(52)))
            screen.addView(buildBottomNav(topLevelTab), LinearLayout.LayoutParams(-1, dp(64)))
            showTopLevelOverlay(screen, topLevelTab)
        } else {
            showOverlay(screen)
        }
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
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12,12,12))
            setPadding(0, dp(4), 0, dp(12))
        }

        fun addSection(label: String) {
            body.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.rgb(132,132,132))
                typeface = Typeface.create("sans", Typeface.BOLD)
                letterSpacing = 0.08f
                gravity = Gravity.BOTTOM or Gravity.START
                setPadding(dp(20), 0, dp(20), dp(6))
            }, LinearLayout.LayoutParams(-1, dp(34)))
        }

        fun addRow(label: String, enabled: Boolean = true, action: () -> Unit) {
            body.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(if (enabled) Color.rgb(238,238,238) else Color.rgb(112,112,112))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                background = if (enabled) selectableBackground() else null
                isEnabled = enabled
                isClickable = enabled
                isFocusable = enabled
                if (enabled) setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, dp(56)))
            body.addView(View(this).apply { setBackgroundColor(Color.rgb(35,35,35)) }, LinearLayout.LayoutParams(-1, 1))
        }

        val local = localLibrary.all()
        val albumCount = local.map { it.album?.takeIf(String::isNotBlank) ?: getString(R.string.unknown_album) }.distinct().size
        val artistCount = local.map { it.artist.ifBlank { getString(R.string.unknown_artist) } }.distinct().size
        val recentCount = collections.recent().size
        val cacheMb = sessionCache.currentBytes().toDouble() / (1024.0 * 1024.0)
        val cachedLabel = if (cacheMb < 0.05) getString(R.string.cached_empty) else getString(R.string.cached_size, "%.1f".format(cacheMb))

        addSection(getString(R.string.library_on_device))
        addRow(getString(R.string.songs_count, local.size)) { showLocalFiles() }
        addRow(getString(R.string.albums_count, albumCount), local.isNotEmpty()) { showLocalGroups(true) }
        addRow(getString(R.string.artists_count, artistCount), local.isNotEmpty()) { showLocalGroups(false) }

        addSection(getString(R.string.library_cloudwalk))
        addRow(cachedLabel) { showCachedTracks() }
        addRow(getString(R.string.recent_count, recentCount), recentCount > 0) {
            showStoredTrackScreen(getString(R.string.recently_played), collections.recent(), getString(R.string.nothing_played))
        }

        addSection(getString(R.string.library_soundcloud))
        addRow(getString(R.string.public_profile_import)) { showPublicProfileImport() }
        if (hasAccount()) {
            addRow(getString(R.string.soundcloud_likes)) { showRemoteTrackScreen(getString(R.string.soundcloud_likes)) { api.likedTracks(50) } }
            addRow(getString(R.string.playlists)) { showPlaylists() }
        } else if (authBroker.configured) {
            addRow(getString(R.string.connect_soundcloud)) { showConnectScreen(getString(R.string.soundcloud)) }
        }

        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        screen.addView(buildTabMiniPlayer(), LinearLayout.LayoutParams(-1, dp(52)))
        screen.addView(buildBottomNav(3), LinearLayout.LayoutParams(-1, dp(64)))
        showTopLevelOverlay(screen, 3)
    }


    private fun showLocalFiles() {
        showOverlay(buildLocalFilesScreen())
    }

    private fun buildLocalFilesScreen(): View {
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
            val added = localLibrary.addAll(readable)
            main.post {
                if (isDestroyed) return@post
                toast(resources.getQuantityString(R.plurals.added_tracks, added, added))
                showLocalFiles()
            }
        }
    }

    private fun showLocalGroups(byAlbum: Boolean) {
        val tracks = localLibrary.all()
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

    private fun showPublicProfileImport(prefill: String? = null) {
        val input = EditText(this).apply {
            hint = getString(R.string.public_profile_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(prefill.orEmpty())
            if (!prefill.isNullOrBlank()) selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.public_profile_import))
            .setMessage(getString(R.string.public_profile_import_help))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.load)) { _, _ ->
                val url = input.text.toString().trim()
                if (!isSoundCloudUrl(url) || !isOnline()) {
                    toast(if (!isOnline()) getString(R.string.offline) else getString(R.string.public_profile_not_found))
                    return@setPositiveButton
                }
                val loading = AlertDialog.Builder(this)
                    .setMessage(getString(R.string.public_profile_loading))
                    .setCancelable(false)
                    .show()
                io.execute {
                    val result = runCatching { webApi.resolveProfileUrl(url) ?: error("not_profile") }
                    main.post {
                        loading.dismiss()
                        result.onSuccess(::showPublicProfileActions).onFailure {
                            toast(if (it.message == "not_profile") getString(R.string.public_profile_not_found) else getString(R.string.public_profile_failed))
                        }
                    }
                }
            }
            .show()
    }

    private fun showPublicProfileActions(profile: SoundCloudPublicProfile) {
        hideKeyboard()
        val labels = arrayOf(
            getString(R.string.import_public_likes_action),
            getString(R.string.use_public_tracks_queue_action)
        )
        AlertDialog.Builder(this)
            .setTitle(profile.username)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> importPublicProfileLikes(profile)
                    1 -> loadPublicProfileQueue(profile)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun importPublicProfileLikes(profile: SoundCloudPublicProfile) {
        val loading = AlertDialog.Builder(this)
            .setMessage(getString(R.string.importing_public_likes))
            .setCancelable(false)
            .show()
        io.execute {
            val result = runCatching { webApi.profileLikes(profile, 300) }
            main.post {
                loading.dismiss()
                result.onSuccess { likes ->
                    if (likes.isEmpty()) toast(getString(R.string.public_profile_no_likes)) else {
                        val added = collections.importLikes(likes)
                        toast(if (added > 0) getString(R.string.imported_likes, added) else getString(R.string.no_new_likes))
                        showLikes()
                    }
                }.onFailure { toast(getString(R.string.public_profile_failed)) }
            }
        }
    }

    private fun loadPublicProfileQueue(profile: SoundCloudPublicProfile) {
        val loading = AlertDialog.Builder(this)
            .setMessage(getString(R.string.loading_public_uploads))
            .setCancelable(false)
            .show()
        io.execute {
            val result = runCatching { webApi.profileTracks(profile, 120) }
            main.post {
                loading.dismiss()
                result.onSuccess { uploads ->
                    if (uploads.isEmpty()) toast(getString(R.string.public_profile_no_uploads)) else {
                        updateHomeCollection(uploads, 0)
                        toast(getString(R.string.queue_replaced, uploads.size))
                        showHomeTab()
                    }
                }.onFailure { toast(getString(R.string.public_profile_failed)) }
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

        val displayedItems = ArrayList<Track>()
        val searchAdapter = TrackAdapter(displayedItems)
        val results = ListView(this).apply {
            divider = ColorDrawable(Color.rgb(40, 40, 40)); dividerHeight = 1; selector = selectableBackground(); isVerticalScrollBarEnabled = false
            adapter = searchAdapter
        }
        screen.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))

        fun showSearchStatus(message: String?) {
            searchStatus.text = message.orEmpty()
            searchStatus.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        fun showResults(items: List<Track>) {
            showSearchStatus(null)
            displayedItems.clear()
            displayedItems.addAll(items)
            searchAdapter.notifyDataSetChanged()
        }

        results.setOnItemClickListener { _, _, position, _ ->
            val track = displayedItems.getOrNull(position) ?: return@setOnItemClickListener
            if (displayedItems.isNotEmpty()) updateHomeCollection(displayedItems, position)
            play(track); showNowPlaying(track)
        }
        results.setOnItemLongClickListener { _, _, position, _ ->
            displayedItems.getOrNull(position)?.let(::showTrackMenu)
            true
        }
        installSwipeToCache(results, displayedItems)
        showResults(emptyList())

        var pendingSearch: Runnable? = null
        var searchSerial = 0
        var searchInFlight = false
        var queuedSearch: String? = null
        var activePublicQuery: String? = null
        var canLoadMore = false
        var loadMoreInFlight = false
        var nextPublicOffset = SEARCH_PAGE_SIZE

        fun loadMore() {
            val q = activePublicQuery ?: return
            if (overlay !== screen || loadMoreInFlight || searchInFlight || !canLoadMore || !isOnline()) return
            if (displayedItems.size >= MAX_SEARCH_RESULTS || nextPublicOffset >= MAX_SEARCH_RESULTS) {
                canLoadMore = false
                return
            }
            val serial = searchSerial
            val offset = nextPublicOffset
            val limit = minOf(SEARCH_PAGE_SIZE, MAX_SEARCH_RESULTS - offset)
            loadMoreInFlight = true
            io.execute {
                val attempt = runCatching { webApi.searchTracks(q, limit, offset) }
                main.post {
                    loadMoreInFlight = false
                    if (overlay !== screen || serial != searchSerial || activePublicQuery != q) return@post
                    attempt.onSuccess { page ->
                        val before = displayedItems.size
                        val existing = displayedItems.asSequence().map { it.id }.toHashSet()
                        page.asSequence()
                            .filter { existing.add(it.id) }
                            .take(MAX_SEARCH_RESULTS - displayedItems.size)
                            .forEach(displayedItems::add)
                        nextPublicOffset = offset + limit
                        val added = displayedItems.size - before
                        if (added > 0) searchAdapter.notifyDataSetChanged()
                        canLoadMore = page.size >= limit && added > 0 &&
                            displayedItems.size < MAX_SEARCH_RESULTS && nextPublicOffset < MAX_SEARCH_RESULTS
                    }.onFailure { canLoadMore = false }
                }
            }
        }

        installArtworkScrollPolicy(results) { first, visible, total ->
            if (total > 0 && first + visible >= total - 4) loadMore()
        }

        fun performSearch(q: String) {
            if (overlay !== screen) return
            val serial = ++searchSerial
            if (searchInFlight) {
                queuedSearch = q
                return
            }
            if (!isOnline()) {
                activePublicQuery = null
                canLoadMore = false
                showResults(emptyList())
                showSearchStatus(getString(R.string.offline))
                return
            }
            showSearchStatus(getString(R.string.searching))
            activePublicQuery = null
            canLoadMore = false
            loadMoreInFlight = false
            nextPublicOffset = SEARCH_PAGE_SIZE
            searchInFlight = true
            io.execute {
                val publicPaged = !isSoundCloudUrl(q) && !hasAccount()
                var resolvedProfile: SoundCloudPublicProfile? = null
                val attempt = runCatching {
                    if (isSoundCloudUrl(q)) {
                        val resolved = webApi.resolvePublicUrl(q)
                        resolvedProfile = resolved.profile
                        resolved.track?.let(::listOf).orEmpty()
                    } else if (hasAccount()) {
                        api.searchTracks(q, SEARCH_PAGE_SIZE)
                    } else {
                        webApi.searchTracks(q, SEARCH_PAGE_SIZE, 0)
                    }
                }
                attempt.exceptionOrNull()?.let { Log.e("CloudWalkSearch", "Search failed", it) }
                main.post {
                    searchInFlight = false
                    if (overlay === screen && serial == searchSerial) {
                        attempt.onSuccess { items ->
                            resolvedProfile?.let { profile ->
                                showPublicProfileActions(profile)
                                return@onSuccess
                            }
                            showResults(items)
                            if (items.isEmpty()) {
                                showSearchStatus(getString(R.string.no_results))
                            } else if (publicPaged) {
                                activePublicQuery = q
                                nextPublicOffset = SEARCH_PAGE_SIZE
                                canLoadMore = items.size >= SEARCH_PAGE_SIZE && nextPublicOffset < MAX_SEARCH_RESULTS
                            }
                        }.onFailure {
                            activePublicQuery = null
                            canLoadMore = false
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
                    activePublicQuery = null
                    canLoadMore = false
                    nextPublicOffset = SEARCH_PAGE_SIZE
                    showResults(emptyList())
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
        screen.addView(buildTabMiniPlayer { !search.hasFocus() }, LinearLayout.LayoutParams(-1, dp(52)))
        search.setOnQueryTextFocusChangeListener { _, _ -> tabMiniRefresh?.invoke() }
        screen.addView(buildBottomNav(1), LinearLayout.LayoutParams(-1, dp(64)))
        showTopLevelOverlay(screen, 1); search.requestFocus()
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
            nowPlayingLike?.let { installPressMotion(it, 0.88f) }
            addView(nowPlayingLike, Toolbar.LayoutParams(dp(48), -1).apply { gravity = Gravity.END })
            menu.add(getString(R.string.sleep_timer)).apply {
                setIcon(R.drawable.ic_sleep)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
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
        var artworkDownX = 0f
        var artworkDownY = 0f
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(45, 45, 45))
            contentDescription = getString(R.string.now_playing_artwork)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        artworkDownX = event.x
                        artworkDownY = event.y
                        view.animate().cancel()
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - artworkDownX
                        val dy = event.y - artworkDownY
                        if (!lowPowerMode && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            view.translationX = dx.coerceIn(-dp(32).toFloat(), dp(32).toFloat())
                            view.alpha = (1f - kotlin.math.min(kotlin.math.abs(dx) / dp(420).toFloat(), 0.12f)).coerceAtLeast(0.88f)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val dx = event.x - artworkDownX
                        val dy = event.y - artworkDownY
                        val swipe = event.actionMasked == android.view.MotionEvent.ACTION_UP &&
                            kotlin.math.abs(dx) >= dp(64) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f
                        view.animate().translationX(0f).alpha(1f).setDuration(130L).start()
                        if (swipe) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            playRelative(if (dx < 0f) 1 else -1)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
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
                    val duration = playback.duration()
                    if (duration > 0) nowPlayingElapsed?.text = formatTime((duration * value / 1000L).toInt())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = playback.duration()
                val value = seekBar?.progress ?: return
                if (duration > 0) playback.seekTo((duration * value / 1000L).toInt())
                syncMediaSessionAfterSeek()
            }
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
        nowPlayingPrev?.let { installPressMotion(it, 0.88f) }
        controls.addView(nowPlayingPrev, LinearLayout.LayoutParams(dp(64), dp(64)))
        nowPlayingPlay = ImageButton(this).apply {
            setColorFilter(Color.WHITE); background = selectableBorderlessBackground(); contentDescription = getString(R.string.play_pause)
            setOnClickListener {
                val current = selectedTrack
                if (current != null && playback.duration() > 0) playback.toggle() else current?.let { play(it) }
            }
        }
        nowPlayingPlay?.let { installPressMotion(it, 0.84f) }
        controls.addView(nowPlayingPlay, LinearLayout.LayoutParams(dp(80), dp(80)))
        nowPlayingNext = ImageButton(this).apply {
            setImageResource(R.drawable.ic_next); setColorFilter(Color.WHITE); background = selectableBorderlessBackground(); contentDescription = getString(R.string.next)
            setOnClickListener { playRelative(1) }
        }
        nowPlayingNext?.let { installPressMotion(it, 0.88f) }
        controls.addView(nowPlayingNext, LinearLayout.LayoutParams(dp(64), dp(64)))
        content.addView(controls, LinearLayout.LayoutParams(-1, dp(if (compactNowPlaying) 72 else 92)).apply { topMargin = dp(if (compactNowPlaying) 2 else 8) })

        val secondary = LinearLayout(this).apply { gravity = Gravity.CENTER }
        nowPlayingShuffle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_shuffle); background = selectableBorderlessBackground(); contentDescription = getString(R.string.shuffle)
            setOnClickListener { toggleShuffle() }
        }
        nowPlayingShuffle?.let { installPressMotion(it, 0.88f) }
        secondary.addView(nowPlayingShuffle, LinearLayout.LayoutParams(dp(56), dp(48)))
        secondary.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_queue); setColorFilter(Color.LTGRAY); background = selectableBorderlessBackground(); contentDescription = getString(R.string.queue)
            setOnClickListener { showQueue() }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        nowPlayingRepeat = ImageButton(this).apply {
            setImageResource(R.drawable.ic_repeat); background = selectableBorderlessBackground(); contentDescription = getString(R.string.repeat_off)
            setOnClickListener { cycleRepeatMode() }
        }
        nowPlayingRepeat?.let { installPressMotion(it, 0.88f) }
        secondary.addView(nowPlayingRepeat, LinearLayout.LayoutParams(dp(56), dp(48)))
        content.addView(secondary, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(if (compactNowPlaying) 0 else 2) })
        screen.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        nowPlayingScreen = screen
        refreshNowPlaying(track)
        showOverlay(screen)
    }

    private fun refreshNowPlaying(track: Track) {
        if (overlay !== nowPlayingScreen) return
        val animateTrackChange = lastNowPlayingTrackId != null && lastNowPlayingTrackId != track.id && !lowPowerMode
        lastNowPlayingTrackId = track.id
        nowPlayingTitle?.text = track.title
        nowPlayingArtist?.text = track.artist
        nowPlayingArtwork?.let { artwork.load(track.artworkUrl, it, dp(if (resources.configuration.screenHeightDp <= 700) 180 else 270)) }
        nowPlayingPlay?.let { setMotionIcon(it, if (playing && selectedTrack?.id == track.id) R.drawable.ic_pause else R.drawable.ic_play) }
        if (animateTrackChange) animateNowPlayingTrackChange()
        val liked = collections.isLiked(track)
        nowPlayingLike?.let { setMotionIcon(it, if (liked) R.drawable.ic_like_filled else R.drawable.ic_like) }
        nowPlayingLike?.setColorFilter(if (liked) Color.rgb(255, 123, 38) else Color.WHITE)
        nowPlayingLike?.contentDescription = if (liked) getString(R.string.unlike) else getString(R.string.like)
        val index = homeTracks.indexOfFirst { it.id == track.id }
        nowPlayingPosition?.text = if (index >= 0) getString(R.string.queue_position, index + 1, homeTracks.size) else ""
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

    private fun persistQueue() {
        val snapshot = homeTracks.toList()
        io.execute { collections.saveQueue(snapshot) }
    }

    private fun updateHomeCollection(items: List<Track>, selectedIndex: Int = 0) {
        if (items.isEmpty()) return
        val snapshot = if (items === homeTracks) ArrayList(items) else items
        homeTracks.clear()
        homeTracks.addAll(snapshot)
        persistQueue()
        flowView.tracks = homeTracks
        updateQueueFlowHeader()
        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
        val safeIndex = selectedIndex.coerceIn(0, homeTracks.lastIndex)
        focusedTrack = homeTracks[safeIndex]
        flowView.setSelected(safeIndex, false)
        syncHomeSurfaceVisibility()
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
        uiPrefs.edit().putBoolean("shuffle", shuffleEnabled).apply()
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
        uiPrefs.edit().putInt("repeat_mode", repeatMode.ordinal).apply()
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
        val queueItems = (if (shuffleEnabled && shuffledTrackIds.isNotEmpty()) {
            shuffledTrackIds.mapNotNull { id -> homeTracks.firstOrNull { it.id == id } }
        } else homeTracks.toList()).toMutableList()
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 12, 12)) }
        val bar = Toolbar(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12))
            title = if (shuffleEnabled) getString(R.string.queue_shuffled_count, queueItems.size) else getString(R.string.queue_count, queueItems.size)
            setTitleTextColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_back); navigationContentDescription = getString(R.string.back); setNavigationOnClickListener { closeOverlay() }
        }
        screen.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        if (queueItems.isNotEmpty()) {
            screen.addView(TextView(this).apply {
                text = getString(R.string.queue_help)
                textSize = 11f
                setTextColor(Color.rgb(145, 145, 145))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
            }, LinearLayout.LayoutParams(-1, dp(30)))
        }
        if (queueItems.isEmpty()) {
            screen.addView(TextView(this).apply { text = getString(R.string.queue_empty); gravity = Gravity.CENTER; setTextColor(Color.GRAY) }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            lateinit var list: ListView
            lateinit var queueAdapter: TrackAdapter
            var dragIndex = -1
            var dragMoved = false

            fun commitQueueOrder() {
                if (shuffleEnabled) {
                    shuffledTrackIds.clear()
                    shuffledTrackIds.addAll(queueItems.map { it.id })
                } else {
                    val focusId = focusedTrack?.id
                    homeTracks.clear()
                    homeTracks.addAll(queueItems)
                    persistQueue()
                    flowView.tracks = homeTracks
                    val focusIndex = homeTracks.indexOfFirst { it.id == focusId }
                    if (focusIndex >= 0) flowView.setSelected(focusIndex, false)
                    (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
                updateMediaSessionQueue()
            }

            queueAdapter = TrackAdapter(queueItems) { position, handle, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dragIndex = position
                        dragMoved = false
                        if (!lowPowerMode) handle.animate().scaleX(1.12f).scaleY(1.12f).alpha(1f).setDuration(80L).start()
                        (handle as? ImageView)?.setColorFilter(Color.rgb(255, 123, 38))
                        list.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (dragIndex !in queueItems.indices) return@TrackAdapter true
                        val location = IntArray(2)
                        list.getLocationOnScreen(location)
                        val localY = (event.rawY - location[1]).toInt()
                        val target = list.pointToPosition(list.width / 2, localY)
                        if (target in queueItems.indices && target != dragIndex) {
                            val item = queueItems.removeAt(dragIndex)
                            queueItems.add(target, item)
                            dragIndex = target
                            dragMoved = true
                            queueAdapter.notifyDataSetChanged()
                            list.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (dragMoved) commitQueueOrder()
                        handle.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(90L).start()
                        (handle as? ImageView)?.setColorFilter(Color.rgb(150, 150, 150))
                        dragIndex = -1
                        dragMoved = false
                        list.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    else -> true
                }
            }
            list = ListView(this).apply {
                divider = ColorDrawable(Color.rgb(38, 38, 38)); dividerHeight = 1; selector = selectableBackground(); isVerticalScrollBarEnabled = false
                adapter = queueAdapter; installArtworkScrollPolicy(this)
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
                        val existing = homeTracks.indexOfFirst { it.id == track.id }
                        if (existing >= 0) homeTracks.removeAt(existing)
                        val current = homeTracks.indexOfFirst { it.id == selectedTrack?.id }
                        val insert = if (current >= 0) (current + 1).coerceAtMost(homeTracks.size) else homeTracks.size
                        homeTracks.add(insert, track)
                        persistQueue()
                        flowView.tracks = homeTracks
                        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
                        rebuildShuffleOrder(selectedTrack?.id)
                        if (shuffleEnabled) {
                            shuffledTrackIds.remove(track.id)
                            val shuffleCurrent = shuffledTrackIds.indexOf(selectedTrack?.id)
                            val shuffleInsert = if (shuffleCurrent >= 0) (shuffleCurrent + 1).coerceAtMost(shuffledTrackIds.size) else shuffledTrackIds.size
                            shuffledTrackIds.add(shuffleInsert, track.id)
                        }
                        updateMediaSessionQueue()
                        toast(getString(R.string.playing_next))
                        if (allowRemoveFromQueue && overlay != null) {
                            closeOverlay()
                            showQueue()
                        }
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
                        persistQueue()
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

    private fun showTopLevelOverlay(view: View, tabIndex: Int) {
        hideKeyboard()
        val previous = overlay
        val previousTab = currentTopTab
        overlayStack.clear()
        overlay = view
        attachOverlay(view)
        if (previous != null && previous !== view) {
            if (lowPowerMode) {
                (previous.parent as? ViewGroup)?.removeView(previous)
            } else {
                previous.animate().cancel()
                previous.animate().alpha(0f).setDuration(80L).withEndAction {
                    (previous.parent as? ViewGroup)?.removeView(previous)
                    previous.alpha = 1f
                }.start()
            }
        }
        currentTopTab = tabIndex
        syncHomeSurfaceVisibility()
        animateScreenIn(view, if (tabIndex >= previousTab) 1f else -1f, 22)
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
        syncHomeSurfaceVisibility()
        animateOverlayIn(view, 1f, 20)
    }

    private fun replaceOverlay(view: View) {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = view
        attachOverlay(view)
        syncHomeSurfaceVisibility()
        animateOverlayIn(view, 1f, 16)
    }

    private fun animateOverlayIn(view: View, direction: Float, distanceDp: Int) {
        if (view === nowPlayingScreen && !lowPowerMode) {
            view.animate().cancel()
            view.alpha = 0f
            view.translationX = 0f
            view.translationY = dp(34).toFloat()
            view.scaleX = 0.975f
            view.scaleY = 0.975f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f))
                .start()
            return
        }
        animateScreenIn(view, direction, distanceDp)
    }

    private fun animateScreenIn(view: View, direction: Float, distanceDp: Int) {
        if (lowPowerMode) {
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        view.translationX = dp(distanceDp).toFloat() * direction
        view.scaleX = 0.994f
        view.scaleY = 0.994f
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(175L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }

    private fun attachOverlay(view: View) {
        deferArtworkLoads = false
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
        if (view === nowPlayingScreen) selectedTrack?.let(::refreshNowPlaying)
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
        if (overlayTransitionRunning) return
        deferArtworkLoads = false
        val current = overlay ?: return
        val previous = if (overlayStack.isNotEmpty()) overlayStack.removeLast() else null
        overlay = previous

        if (previous != null) {
            attachOverlay(previous)
            current.bringToFront()
        }
        syncHomeSurfaceVisibility()

        if (lowPowerMode) {
            (current.parent as? ViewGroup)?.removeView(current)
            resetMotionState(current)
            return
        }

        overlayTransitionRunning = true
        current.animate().cancel()
        val nowPlaying = current === nowPlayingScreen
        current.animate()
            .alpha(if (nowPlaying) 0.25f else 0f)
            .translationX(if (nowPlaying) 0f else dp(18).toFloat())
            .translationY(if (nowPlaying) dp(38).toFloat() else 0f)
            .scaleX(if (nowPlaying) 0.98f else 0.996f)
            .scaleY(if (nowPlaying) 0.98f else 0.996f)
            .setDuration(if (nowPlaying) 165L else 135L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.25f))
            .withEndAction {
                (current.parent as? ViewGroup)?.removeView(current)
                resetMotionState(current)
                overlayTransitionRunning = false
                syncHomeSurfaceVisibility()
            }
            .start()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { handleBackAction() }

    private fun showViewMenu() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val flowRadio = RadioButton(this).apply {
            text = getString(R.string.cover_flow)
            isChecked = showingFlow && !lowPowerMode
            setPadding(0, dp(4), 0, dp(4))
        }
        val listRadio = RadioButton(this).apply {
            text = getString(R.string.track_list)
            isChecked = !showingFlow || lowPowerMode
            setPadding(0, dp(4), 0, dp(4))
        }
        group.addView(flowRadio, RadioGroup.LayoutParams(-1, dp(48)))
        group.addView(listRadio, RadioGroup.LayoutParams(-1, dp(48)))
        container.addView(group, LinearLayout.LayoutParams(-1, -2))
        val lowPower = CheckBox(this).apply {
            text = getString(R.string.low_power_mode)
            isChecked = lowPowerMode
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(lowPower, LinearLayout.LayoutParams(-1, dp(48)))

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.home_view_options))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        flowRadio.setOnClickListener {
            if (lowPowerMode) {
                lowPowerMode = false
                lowPower.isChecked = false
                flowView.lowPowerMode = false
            }
            setViewMode(true)
            uiPrefs.edit().putBoolean("low_power", false).putBoolean("home_flow", true).apply()
            if (playing) startProgressTicker()
            dialog.dismiss()
        }
        listRadio.setOnClickListener {
            setViewMode(false)
            uiPrefs.edit().putBoolean("home_flow", false).apply()
            dialog.dismiss()
        }
        lowPower.setOnCheckedChangeListener { _, enabled ->
            if (lowPowerMode == enabled) return@setOnCheckedChangeListener
            lowPowerMode = enabled
            flowView.lowPowerMode = enabled
            if (enabled) {
                showingFlow = false
                flowRadio.isChecked = false
                listRadio.isChecked = true
                setViewMode(false)
            } else {
                updateQueueFlowHeader()
            }
            uiPrefs.edit().putBoolean("low_power", enabled).putBoolean("home_flow", showingFlow).apply()
            if (playing) startProgressTicker()
            toast(if (enabled) getString(R.string.low_power_on) else getString(R.string.low_power_off))
        }
        dialog.show()
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
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val trigger = dp(68).toFloat()
        val maxShift = dp(116).toFloat()

        fun resetGesture(animateRow: Boolean = true) {
            if (animateRow) {
                activeRow?.animate()?.cancel()
                activeRow?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(120L)?.start()
            }
            list.requestDisallowInterceptTouchEvent(false)
            activeRow = null
            activeTrack = null
            horizontal = false
            thresholdHaptic = false
        }

        fun takeHorizontalGesture(event: android.view.MotionEvent) {
            if (horizontal) return
            horizontal = true
            list.cancelLongPress()
            val cancel = android.view.MotionEvent.obtain(event).apply {
                action = android.view.MotionEvent.ACTION_CANCEL
            }
            // ListView already received ACTION_DOWN normally. Cancel only that native
            // gesture once we decide this is our horizontal cache swipe.
            list.onTouchEvent(cancel)
            cancel.recycle()
            list.requestDisallowInterceptTouchEvent(true)
        }

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
                            activeRow?.animate()?.cancel()
                        } else {
                            activeTrack = null
                            activeRow = null
                        }
                    } else {
                        activeTrack = null
                        activeRow = null
                    }
                    // Let ListView handle taps, long-press and vertical scrolling normally.
                    false
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (activeTrack != null && !horizontal && dx > touchSlop &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.18f
                    ) {
                        takeHorizontalGesture(event)
                    }
                    if (!horizontal) {
                        false
                    } else {
                        val shift = dx.coerceIn(0f, maxShift)
                        activeRow?.translationX = shift
                        activeRow?.alpha = 1f - 0.10f * (shift / maxShift)
                        if (shift >= trigger && !thresholdHaptic) {
                            thresholdHaptic = true
                            list.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        } else if (shift < trigger * 0.78f) {
                            thresholdHaptic = false
                        }
                        true
                    }
                }

                android.view.MotionEvent.ACTION_UP -> {
                    if (!horizontal) {
                        resetGesture(animateRow = false)
                        false
                    } else {
                        val dx = event.x - downX
                        val row = activeRow
                        val track = activeTrack
                        val committed = dx >= trigger && track != null
                        if (committed) {
                            val targetTrack = track ?: return@setOnTouchListener true
                            row?.animate()?.cancel()
                            row?.animate()?.translationX(maxShift)?.alpha(0.90f)?.setDuration(70L)?.withEndAction {
                                row.animate().translationX(0f).alpha(1f).setDuration(140L).start()
                            }?.start()
                            when {
                                playback.isSessionCached(targetTrack) -> toast(getString(R.string.already_cached))
                                cachingTrackIds.add(targetTrack.id) -> {
                                    toast(getString(R.string.caching_session))
                                    (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                                    val action = (row?.parent as? View)?.findViewById<TextView>(android.R.id.hint)
                                    playback.keepForSession(targetTrack, { percent ->
                                        action?.text = getString(R.string.caching_percent, percent)
                                    }) { ok, message ->
                                        cachingTrackIds.remove(targetTrack.id)
                                        action?.apply {
                                            text = if (ok) getString(R.string.cached_badge) else getString(R.string.cache_badge)
                                            setBackgroundColor(if (ok) Color.rgb(72, 110, 72) else Color.rgb(205, 86, 26))
                                        }
                                        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                                        toast(message)
                                    }
                                }
                            }
                        }
                        resetGesture(animateRow = !committed)
                        true
                    }
                }

                android.view.MotionEvent.ACTION_CANCEL -> {
                    val wasHorizontal = horizontal
                    resetGesture()
                    wasHorizontal
                }

                else -> horizontal
            }
        }
    }

    private fun syncHomeSurfaceVisibility() {
        if (!::contentHost.isInitialized) return
        updateQueueFlowHeader()
        val showHome = overlay == null
        contentHost.visibility = if (showHome) View.VISIBLE else View.GONE
        if (!showHome) {
            if (::flowView.isInitialized) flowView.setSurfaceActive(false)
            if (::trackInfoPanel.isInitialized) trackInfoPanel.visibility = View.GONE
            if (::playerStripView.isInitialized) playerStripView.visibility = View.GONE
            return
        }
        setViewMode(showingFlow)
        if (homeTracks.isNotEmpty()) {
            trackInfoPanel.visibility = if (showingFlow && !lowPowerMode) View.VISIBLE else View.GONE
            val current = selectedTrack
            playerStripView.visibility = if (current != null) View.VISIBLE else View.GONE
            if (current != null) {
                refreshHomeTrackUi(current)
            } else {
                focusedTrack?.let { focus ->
                    titleView.text = focus.title
                    artistView.text = focus.artist
                }
            }
        }
    }

    private fun setViewMode(flow: Boolean) {
        val wasFlowVisible = ::flowView.isInitialized && flowView.visibility == View.VISIBLE
        showingFlow = flow && !lowPowerMode
        updateQueueFlowHeader()
        if (homeTracks.isEmpty()) {
            flowView.visibility = View.GONE
            listView.visibility = View.GONE
            homeEmptyView.visibility = View.VISIBLE
            trackInfoPanel.visibility = View.GONE
            playerStripView.visibility = View.GONE
            flowView.setSurfaceActive(false)
            return
        }
        homeEmptyView.visibility = View.GONE
        val target = if (showingFlow) flowView else listView
        val changed = wasFlowVisible != showingFlow
        flowView.visibility = if (showingFlow) View.VISIBLE else View.GONE
        listView.visibility = if (showingFlow) View.GONE else View.VISIBLE
        trackInfoPanel.visibility = if (showingFlow) View.VISIBLE else View.GONE
        flowView.setSurfaceActive(overlay == null && showingFlow)
        if (changed && overlay == null && !lowPowerMode) {
            target.animate().cancel()
            target.alpha = 0f
            target.translationY = dp(6).toFloat()
            target.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(145L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            target.alpha = 1f
            target.translationY = 0f
        }
    }

    private fun play(track: Track) {
        collections.addRecent(track)
        showTrack(track)
        playback.play(track)
    }

    private fun showTrack(track: Track) {
        val changed = selectedTrack?.id != track.id
        selectedTrack = track
        focusedTrack = track
        if (::flowView.isInitialized) flowView.playingTrackId = track.id
        if (overlay == null) refreshHomeTrackUi(track)
        tabMiniRefresh?.invoke()
        if (changed) {
            uiPrefs.edit().putString("last_track_id", track.id).apply()
            updateMediaSession()
        }
    }

    private fun showFocusedTrack(track: Track) {
        val changed = focusedTrack?.id != track.id
        focusedTrack = track
        if (overlay == null && ::titleView.isInitialized) {
            if (changed && showingFlow && !lowPowerMode && titleView.text.isNotEmpty()) {
                titleView.animate().cancel()
                artistView.animate().cancel()
                titleView.animate().alpha(0.22f).translationY(-dp(2).toFloat()).setDuration(45L).withEndAction {
                    titleView.text = track.title
                    artistView.text = track.artist
                    titleView.translationY = dp(2).toFloat()
                    artistView.translationY = dp(2).toFloat()
                    titleView.animate().alpha(1f).translationY(0f).setDuration(85L).start()
                    artistView.animate().alpha(1f).translationY(0f).setDuration(85L).start()
                }.start()
                artistView.animate().alpha(0.22f).translationY(-dp(2).toFloat()).setDuration(45L).start()
            } else {
                titleView.alpha = 1f
                artistView.alpha = 1f
                titleView.translationY = 0f
                artistView.translationY = 0f
                titleView.text = track.title
                artistView.text = track.artist
            }
        }
    }

    private fun refreshHomeTrackUi(track: Track) {
        if (!::titleView.isInitialized) return
        trackInfoPanel.visibility = if (showingFlow && !lowPowerMode) View.VISIBLE else View.GONE
        playerStripView.visibility = View.VISIBLE
        val focus = focusedTrack ?: track
        titleView.text = focus.title
        artistView.text = focus.artist
        miniTitleView.text = track.title
        miniArtistView.text = track.artist
        artwork.load(track.artworkUrl, miniArtwork, dp(48))
        setMotionIcon(playButton, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
        val duration = playback.duration()
        progress.progress = if (duration > 0) (playback.currentPosition() * 1000L / duration).toInt() else 0
    }

    private fun updatePlayButton() {
        if (overlay == null && ::playButton.isInitialized) setMotionIcon(playButton, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (overlay === nowPlayingScreen) selectedTrack?.let { refreshNowPlaying(it) }
        tabMiniRefresh?.invoke()
    }

    private fun startProgressTicker() {
        stopProgressTicker()
        if (!playing || !activityVisible) return
        progressTicker = object : Runnable {
            override fun run() {
                if (!playing) return
                val duration = playback.duration()
                val position = playback.currentPosition()
                val newProgress = if (duration > 0) (position * 1000L / duration).toInt() else 0
                if (overlay == null) {
                    if (progress.progress != newProgress) progress.progress = newProgress
                } else if (overlay === nowPlayingScreen) {
                    if (nowPlayingSeek?.progress != newProgress) nowPlayingSeek?.progress = newProgress
                    nowPlayingElapsed?.text = formatTime(position)
                    if (nowPlayingDuration?.text.isNullOrEmpty()) nowPlayingDuration?.text = formatTime(duration)
                }
                main.postDelayed(this, if (lowPowerMode) 1500 else 750)
            }
        }.also(main::post)
    }

    private fun stopProgressTicker() {
        progressTicker?.let(main::removeCallbacks)
        progressTicker = null
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        if (playing) startProgressTicker()
    }

    override fun onStop() {
        activityVisible = false
        stopProgressTicker()
        super.onStop()
    }

    override fun onBuffering(track: Track) {
        playing = false
        showTrack(track)
        stopProgressTicker()
        updatePlayButton()
        updateMediaSession()
    }
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

    private inner class TrackAdapter(
        private val items: List<Track>,
        private val onDragTouch: ((Int, View, android.view.MotionEvent) -> Boolean)? = null
    ) : BaseAdapter() {
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
                    if (onDragTouch != null) {
                        addView(ImageView(this@MainActivity).apply {
                            id = android.R.id.icon2
                            setImageResource(R.drawable.ic_drag_handle)
                            setColorFilter(Color.rgb(150, 150, 150))
                            contentDescription = getString(R.string.reorder)
                            setPadding(dp(10), dp(12), dp(6), dp(12))
                            background = selectableBorderlessBackground()
                        }, LinearLayout.LayoutParams(dp(44), -1))
                    }
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
            val subtitle = row.findViewById<TextView>(android.R.id.text2)
            val action = row.findViewById<TextView>(android.R.id.hint)
            when {
                !track.localUri.isNullOrBlank() -> { action.text = getString(R.string.local_badge); action.setBackgroundColor(Color.rgb(72, 72, 72)) }
                playback.isSessionCached(track) -> { action.text = getString(R.string.cached_badge); action.setBackgroundColor(Color.rgb(72, 110, 72)) }
                cachingTrackIds.contains(track.id) -> { action.text = getString(R.string.caching_badge); action.setBackgroundColor(Color.rgb(160, 78, 28)) }
                playback.canSessionCache(track) -> { action.text = getString(R.string.cache_badge); action.setBackgroundColor(Color.rgb(205, 86, 26)) }
                else -> { action.text = ""; action.setBackgroundColor(Color.TRANSPARENT) }
            }
            subtitle.text = when {
                !track.localUri.isNullOrBlank() -> "${track.artist} · ${getString(R.string.local_badge)}"
                playback.isSessionCached(track) -> "${track.artist} · ${getString(R.string.cached_badge)}"
                cachingTrackIds.contains(track.id) -> "${track.artist} · ${getString(R.string.caching_badge)}"
                else -> track.artist
            }
            row.findViewById<View?>(android.R.id.icon2)?.setOnTouchListener { handle, event ->
                onDragTouch?.invoke(position, handle, event) == true
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

    private fun installArtworkScrollPolicy(
        list: ListView,
        onScroll: ((firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) -> Unit)? = null
    ) {
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                val shouldDefer = scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (deferArtworkLoads != shouldDefer) {
                    deferArtworkLoads = shouldDefer
                    if (!shouldDefer) (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
            }
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                onScroll?.invoke(firstVisibleItem, visibleItemCount, totalItemCount)
            }
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
