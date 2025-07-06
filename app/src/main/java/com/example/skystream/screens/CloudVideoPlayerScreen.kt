package com.example.skystream.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.media3.common.*
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.skystream.services.DriveVideoFile
import com.example.skystream.services.GoogleDriveService
import com.example.skystream.auth.GoogleDriveAuthManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
@Composable
fun CloudVideoPlayerScreen(
    video: DriveVideoFile,
    videoList: List<DriveVideoFile>,
    authManager: GoogleDriveAuthManager,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val coroutineScope = rememberCoroutineScope()

    // Enhanced state management
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(false) }
    var userKeepScreenOnEnabled by rememberSaveable { mutableStateOf(true) }
    var keepScreenOnTimeout by remember { mutableStateOf(0L) }
    val keepScreenOnDuration = 30000L

    // System controls
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentSystemVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var currentVolume by remember {
        mutableFloatStateOf(currentSystemVolume.toFloat() / maxVolume.toFloat())
    }

    val contentResolver = context.contentResolver
    var currentSystemBrightness by remember {
        mutableIntStateOf(
            try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) {
                128
            }
        )
    }
    var currentBrightness by remember {
        mutableFloatStateOf(currentSystemBrightness / 255f)
    }

    // UI State
    var isControlsVisible by remember { mutableStateOf(true) }
    var currentPosition by rememberSaveable { mutableLongStateOf(0L) }
    var duration by rememberSaveable { mutableLongStateOf(0L) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }

    // Dialog States
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showScalingDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }

    // Volume and Brightness States
    var showVolumeSlider by remember { mutableStateOf(false) }
    var showBrightnessSlider by remember { mutableStateOf(false) }

    // Video State
    var currentVideoIndex by rememberSaveable { mutableIntStateOf(videoList.indexOf(video)) }
    var scalingMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1.0f) }

    // Cloud-specific states
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var isLoadingStream by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var networkQuality by remember { mutableStateOf("Auto") }

    // Track State
    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var audioTracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var selectedSubtitleTrack by rememberSaveable { mutableIntStateOf(-1) }
    var selectedAudioTrack by rememberSaveable { mutableIntStateOf(0) }
    var isSubtitlesEnabled by rememberSaveable { mutableStateOf(false) }

    // Gesture States
    var isDragging by remember { mutableStateOf(false) }
    var dragStartPosition by remember { mutableLongStateOf(0L) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val currentVideo = videoList.getOrNull(currentVideoIndex) ?: video

    // YouTube-style adaptive streaming configuration
    val trackSelector = remember {
        DefaultTrackSelector(context).apply {
            val parametersBuilder = buildUponParameters()
                .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxVideoSizeSd() // Start with SD quality
                .setPreferredAudioLanguage(null)
                .setForceLowestBitrate(false)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedChannelCountAdaptiveness(true)
                .setAllowAudioMixedSampleRateAdaptiveness(true)
            setParameters(parametersBuilder.build())
        }
    }

    // Enhanced ExoPlayer with cloud streaming optimizations
    val exoPlayer = remember(currentVideo.id) {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // YouTube-style buffering for cloud streaming
                    .setBufferDurationsMs(
                        15000,  // Min buffer: 15 seconds
                        50000,  // Max buffer: 50 seconds
                        2500,   // Playback buffer: 2.5 seconds
                        5000    // Playback after rebuffer: 5 seconds
                    )
                    .setTargetBufferBytes(16 * 1024 * 1024) // 16MB target buffer
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setBackBuffer(30000, true) // 30 second back buffer
                    .build()
            )
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
    }

    // Google Drive service for stream URL fetching
    val driveService = remember {
        authManager.getAccessToken()?.let { token ->
            GoogleDriveService(context, token)
        }
    }

    // System volume control function
    fun setSystemVolume(volume: Float) {
        val volumeLevel = (volume * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel, 0)
        currentSystemVolume = volumeLevel
        currentVolume = volume
    }

    // System brightness control function
    fun setSystemBrightness(brightness: Float) {
        val brightnessLevel = (brightness * 255).toInt().coerceIn(0, 255)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(context)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessLevel
            )

            val window = activity.window
            val layoutParams = window.attributes
            layoutParams.screenBrightness = brightness
            window.attributes = layoutParams

            currentSystemBrightness = brightnessLevel
            currentBrightness = brightness
        } catch (e: Exception) {
            val window = activity.window
            val layoutParams = window.attributes
            layoutParams.screenBrightness = brightness
            window.attributes = layoutParams
            currentBrightness = brightness
        }
    }

    // Fetch stream URL from Google Drive
    LaunchedEffect(currentVideo.id) {
        if (driveService != null) {
            isLoadingStream = true
            streamError = null

            // Use the new direct URL method instead
            driveService.getDirectVideoStreamUrl(currentVideo.id)
                .onSuccess { url ->
                    streamUrl = url
                    isLoadingStream = false
                }
                .onFailure { error ->
                    streamError = error.message
                    isLoadingStream = false
                }
        }
    }


    // Enhanced video loading with cloud streaming
    LaunchedEffect(streamUrl) {
        if (streamUrl != null) {
            try {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                System.gc()
                delay(100)

                // Create HTTP data source with authentication headers
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("SkyStream/1.0")
                    .setConnectTimeoutMs(30000)
                    .setReadTimeoutMs(30000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(
                        mapOf(
                            "Authorization" to "Bearer ${authManager.getAccessToken()}",
                            "Range" to "bytes=0-"
                        )
                    )

                val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

                // Create media source based on content type
                val mediaSource = when {
                    streamUrl!!.contains(".m3u8") -> {
                        // HLS streaming for adaptive quality
                        HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(streamUrl!!))
                    }
                    streamUrl!!.contains(".mpd") -> {
                        // DASH streaming for adaptive quality
                        DashMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(streamUrl!!))
                    }
                    else -> {
                        // Progressive streaming for regular video files
                        ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(streamUrl!!))
                    }
                }

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                exoPlayer.setPlaybackSpeed(playbackSpeed)

                // Reset track states
                subtitleTracks = emptyList()
                audioTracks = emptyList()
                selectedSubtitleTrack = -1
                selectedAudioTrack = 0
                isSubtitlesEnabled = false
                currentPosition = 0L
                duration = 0L
            } catch (e: Exception) {
                streamError = "Failed to load video: ${e.message}"
            }
        }
    }

    // Keep screen on logic
    LaunchedEffect(isPlaying, isBuffering, isControlsVisible, userKeepScreenOnEnabled) {
        when {
            !userKeepScreenOnEnabled -> {
                keepScreenOn = false
                keepScreenOnTimeout = 0L
            }
            isPlaying || isBuffering -> {
                keepScreenOn = true
                keepScreenOnTimeout = 0L
            }
            isControlsVisible -> {
                keepScreenOn = true
                keepScreenOnTimeout = System.currentTimeMillis() + keepScreenOnDuration
            }
            else -> {
                if (keepScreenOnTimeout > 0L && System.currentTimeMillis() > keepScreenOnTimeout) {
                    keepScreenOn = false
                    keepScreenOnTimeout = 0L
                }
            }
        }
    }

    // Apply screen on flag
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // System UI - Hide status bar for immersive experience
    LaunchedEffect(Unit) {
        activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
    }

    // Restore system UI on exit
    DisposableEffect(Unit) {
        onDispose {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // Orientation handling
    LaunchedEffect(isFullscreen) {
        activity.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Enhanced player listener
    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            fun navigateToNext() {
                if (currentVideoIndex < videoList.size - 1) {
                    currentPosition = 0L
                    currentVideoIndex++
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                isBuffering = playbackState == Player.STATE_BUFFERING

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        // Adaptive quality adjustment based on buffering
                        if (networkQuality == "Auto") {
                            adjustQualityBasedOnBuffering()
                        }
                    }
                    Player.STATE_READY -> {
                        // Video is ready to play
                    }
                    Player.STATE_ENDED -> {
                        if (currentVideoIndex < videoList.size - 1) {
                            navigateToNext()
                        }
                    }
                    Player.STATE_IDLE -> {
                        isBuffering = false
                    }
                }
            }


            // Quality adjustment function
            fun adjustQualityBasedOnBuffering() {
                val parametersBuilder = trackSelector.buildUponParameters()

                // If buffering frequently, reduce quality
                if (isBuffering) {
                    parametersBuilder.setMaxVideoSizeSd()
                } else {
                    parametersBuilder.clearVideoSizeConstraints()
                }

                trackSelector.setParameters(parametersBuilder.build())
            }

            // Track extraction function
            fun extractTracks(tracks: Tracks) {
                val audioList = mutableListOf<AudioTrack>()
                val subtitleList = mutableListOf<SubtitleTrack>()

                for (trackGroup in tracks.groups) {
                    if (trackGroup.length > 0) {
                        for (trackIndex in 0 until trackGroup.length) {
                            if (trackGroup.isTrackSupported(trackIndex)) {
                                val format = trackGroup.getTrackFormat(trackIndex)
                                val trackGroupObj = trackGroup.mediaTrackGroup

                                when {
                                    format.sampleMimeType?.startsWith("audio") == true -> {
                                        val language = format.language ?: "Unknown"
                                        val label = when {
                                            !format.label.isNullOrEmpty() -> format.label!!
                                            !format.language.isNullOrEmpty() -> format.language!!
                                            else -> "Audio Track ${audioList.size + 1}"
                                        }

                                        audioList.add(
                                            AudioTrack(
                                                id = format.id ?: "audio_$trackIndex",
                                                language = language,
                                                label = label,
                                                trackGroup = trackGroupObj,
                                                trackIndex = trackIndex
                                            )
                                        )
                                    }
                                    trackGroup.type == C.TRACK_TYPE_TEXT -> {
                                        val language = format.language ?: "Unknown"
                                        val label = when {
                                            !format.label.isNullOrEmpty() -> format.label!!
                                            !format.language.isNullOrEmpty() -> format.language!!
                                            else -> "Subtitle Track ${subtitleList.size + 1}"
                                        }

                                        subtitleList.add(
                                            SubtitleTrack(
                                                id = format.id ?: "subtitle_$trackIndex",
                                                language = language,
                                                label = label,
                                                trackGroup = trackGroupObj,
                                                trackIndex = trackIndex
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                audioTracks = audioList
                subtitleTracks = subtitleList
            }

            override fun onTracksChanged(tracks: Tracks) {
                try {
                    extractTracks(tracks)
                } catch (e: Exception) {
                    // Handle track extraction errors
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                streamError = "Playback error: ${error.message}"
                try {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    System.gc()
                } catch (e: Exception) {
                    // Ignore cleanup errors
                }
            }
        }
        exoPlayer.addListener(listener)

        // Position updates
        while (true) {
            try {
                if (exoPlayer.duration != C.TIME_UNSET) {
                    currentPosition = exoPlayer.currentPosition

                    // Sync system states every 5 seconds
                    if (currentPosition % 5000 < 1000) {
                        currentSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        currentVolume = currentSystemVolume.toFloat() / maxVolume.toFloat()

                        try {
                            currentSystemBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                            currentBrightness = currentSystemBrightness / 255f
                        } catch (e: Exception) {
                            // Handle gracefully
                        }
                    }
                }
                delay(1000)
            } catch (e: Exception) {
                break
            }
        }
    }

    // Auto-hide sliders
    LaunchedEffect(showVolumeSlider) {
        if (showVolumeSlider) {
            delay(3000)
            showVolumeSlider = false
        }
    }

    LaunchedEffect(showBrightnessSlider) {
        if (showBrightnessSlider) {
            delay(3000)
            showBrightnessSlider = false
        }
    }

    // Navigation functions
    fun navigateToPrevious() {
        if (currentVideoIndex > 0) {
            currentPosition = 0L
            currentVideoIndex--
        }
    }

    fun navigateToNext() {
        if (currentVideoIndex < videoList.size - 1) {
            currentPosition = 0L
            currentVideoIndex++
        }
    }

    // Audio track selection
    fun selectAudioTrack(trackIndex: Int) {
        if (trackIndex < audioTracks.size) {
            val track = audioTracks[trackIndex]
            val override = TrackSelectionOverride(track.trackGroup, track.trackIndex)
            val parametersBuilder = trackSelector.buildUponParameters()
                .setOverrideForType(override)
            trackSelector.setParameters(parametersBuilder.build())
            selectedAudioTrack = trackIndex
        }
    }

    // Subtitle track enabling
    fun enableSubtitleTrack(trackIndex: Int) {
        if (trackIndex < subtitleTracks.size) {
            val track = subtitleTracks[trackIndex]
            val override = TrackSelectionOverride(track.trackGroup, track.trackIndex)
            val parametersBuilder = trackSelector.buildUponParameters()
                .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(override)
            trackSelector.setParameters(parametersBuilder.build())
            selectedSubtitleTrack = trackIndex
            isSubtitlesEnabled = true
        }
    }

    // Subtitle disabling
    fun disableSubtitles() {
        val parametersBuilder = trackSelector.buildUponParameters()
            .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
        trackSelector.setParameters(parametersBuilder.build())
        isSubtitlesEnabled = false
        selectedSubtitleTrack = -1
    }

    // Playback speed control
    fun setPlaybackSpeedAndSave(speed: Float) {
        playbackSpeed = speed
        exoPlayer.setPlaybackSpeed(speed)
    }

    // Auto-hide controls
    LaunchedEffect(isControlsVisible) {
        if (isControlsVisible && !isLocked) {
            delay(4000)
            isControlsVisible = false
        }
    }

    // Back press handling
    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            try {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.release()
                System.gc()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
            onBackPressed()
        }
    }

    // Enhanced cleanup
    DisposableEffect(exoPlayer) {
        onDispose {
            try {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.release()
                System.gc()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    // UI Layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Loading state for stream URL
        if (isLoadingStream) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Loading video from Google Drive...",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                }
            }
        }

        // Error state
        streamError?.let { error ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        tint = Color.Red,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error,
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            // Retry loading
                            coroutineScope.launch {
                                if (driveService != null) {
                                    isLoadingStream = true
                                    streamError = null

                                    driveService.getVideoStreamUrl(currentVideo.id)
                                        .onSuccess { url ->
                                            streamUrl = url
                                            isLoadingStream = false
                                        }
                                        .onFailure { retryError ->
                                            streamError = retryError.message
                                            isLoadingStream = false
                                        }
                                }
                            }
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

        // Video player (only show when stream URL is available and no error)
        if (streamUrl != null && streamError == null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        subtitleView?.visibility = View.GONE
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                if (!isLocked) {
                                    isControlsVisible = !isControlsVisible
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                if (!isLocked) {
                                    isDragging = true
                                    dragStartPosition = currentPosition
                                    val screenWidth = boxSize.width.toFloat()

                                    if (offset.x < screenWidth / 2) {
                                        showBrightnessSlider = true
                                    } else {
                                        showVolumeSlider = true
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (!isLocked && isDragging) {
                                    val screenWidth = boxSize.width.toFloat()
                                    val screenHeight = boxSize.height.toFloat()
                                    val deltaY = dragAmount.y

                                    if (change.position.x < screenWidth / 2) {
                                        val brightnessChange = -deltaY / screenHeight
                                        val newBrightness = (currentBrightness + brightnessChange).coerceIn(0.1f, 1.0f)
                                        setSystemBrightness(newBrightness)
                                    } else {
                                        val volumeChange = -deltaY / screenHeight
                                        val newVolume = (currentVolume + volumeChange).coerceIn(0.0f, 1.0f)
                                        setSystemVolume(newVolume)
                                    }
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                            }
                        )
                    },
                update = { playerView ->
                    try {
                        playerView.player = exoPlayer
                        playerView.resizeMode = scalingMode
                    } catch (e: Exception) {
                        // Handle update errors
                    }
                }
            )

            // Subtitle overlay (same as original)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = if (isControlsVisible) 100.dp else 10.dp,
                            start = 32.dp,
                            end = 32.dp,
                        )
                ) {
                    AndroidView(
                        factory = { context ->
                            SubtitleView(context).apply {
                                setStyle(
                                    CaptionStyleCompat(
                                        android.graphics.Color.WHITE,
                                        android.graphics.Color.TRANSPARENT,
                                        android.graphics.Color.TRANSPARENT,
                                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                        android.graphics.Color.BLACK,
                                        Typeface.DEFAULT
                                    )
                                )
                                setApplyEmbeddedStyles(false)
                                setApplyEmbeddedFontSizes(false)
                                setFixedTextSize(Cue.TEXT_SIZE_TYPE_ABSOLUTE, 20f)
                            }
                        },
                        update = { subtitleView ->
                            subtitleView.setFixedTextSize(Cue.TEXT_SIZE_TYPE_ABSOLUTE, 20f)

                            exoPlayer.addListener(object : Player.Listener {
                                override fun onCues(cueGroup: CueGroup) {
                                    try {
                                        subtitleView.setCues(cueGroup.cues)
                                    } catch (e: Exception) {
                                        // Handle gracefully
                                    }
                                }
                            })
                        }
                    )
                }
            }

            // Volume and brightness sliders (same as original)
            AnimatedVisibility(
                visible = showVolumeSlider,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 32.dp)
            ) {
                Card(
                    modifier = Modifier
                        .width(70.dp)
                        .height(250.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (currentVolume > 0.5f) Icons.Default.VolumeUp
                            else if (currentVolume > 0f) Icons.Default.VolumeDown
                            else Icons.Default.VolumeOff,
                            contentDescription = "Volume",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = currentVolume,
                            onValueChange = { setSystemVolume(it) },
                            valueRange = 0f..1f,
                            modifier = Modifier
                                .height(120.dp)
                                .width(24.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.Gray
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${(currentVolume * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showBrightnessSlider,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 32.dp)
            ) {
                Card(
                    modifier = Modifier
                        .width(70.dp)
                        .height(250.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Brightness6,
                            contentDescription = "Brightness",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = currentBrightness,
                            onValueChange = { setSystemBrightness(it) },
                            valueRange = 0.1f..1f,
                            modifier = Modifier
                                .height(120.dp)
                                .width(24.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.Gray
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${(currentBrightness * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Unlock button when locked
            if (isLocked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                ) {
                    IconButton(
                        onClick = { isLocked = false },
                        modifier = Modifier
                            .background(
                                Color.Black.copy(alpha = 0.7f),
                                CircleShape
                            )
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "Unlock",
                            tint = Color.Red
                        )
                    }
                }
            }

            // Player controls (same as original but with cloud video info)
            if (isControlsVisible && !isLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.3f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.7f)
                                )
                            )
                        )
                ) {
                    // Top controls with cloud indicator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (isFullscreen) {
                                    isFullscreen = false
                                } else {
                                    onBackPressed()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = "Cloud",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = currentVideo.name,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Text(
                                text = "${currentVideoIndex + 1} of ${videoList.size} • Google Drive",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp
                            )
                        }

                        IconButton(
                            onClick = { showMoreMenu = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Center controls (same as original)
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { navigateToPrevious() },
                            enabled = currentVideoIndex > 0
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = if (currentVideoIndex > 0) Color.White else Color.Gray,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val newPosition = max(0, currentPosition - 10000)
                                exoPlayer.seekTo(newPosition)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Rewind 10s",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                            },
                            modifier = Modifier
                                .background(
                                    Color.White.copy(alpha = 0.2f),
                                    CircleShape
                                )
                                .size(64.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val newPosition = min(duration, currentPosition + 10000)
                                exoPlayer.seekTo(newPosition)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Forward 10s",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = { navigateToNext() },
                            enabled = currentVideoIndex < videoList.size - 1
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = if (currentVideoIndex < videoList.size - 1) Color.White else Color.Gray,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    // Bottom controls with buffering indicator
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        // Buffering indicator
                        if (isBuffering) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Buffering...",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Progress bar (same as original)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime(currentPosition),
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.width(50.dp)
                            )

                            Slider(
                                value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                                onValueChange = { progress ->
                                    val newPosition = (progress * duration).toLong()
                                    exoPlayer.seekTo(newPosition)
                                },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Red,
                                    activeTrackColor = Color.Red,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                )
                            )

                            Text(
                                text = formatTime(duration),
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.width(50.dp),
                                textAlign = TextAlign.End
                            )
                        }

                        // Bottom action buttons with quality control
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                IconButton(onClick = { showSubtitleDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Subtitles,
                                        contentDescription = "Subtitles",
                                        tint = if (isSubtitlesEnabled) Color.Red else Color.White
                                    )
                                }

                                IconButton(onClick = { showAudioDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.AudioFile,
                                        contentDescription = "Audio",
                                        tint = Color.White
                                    )
                                }

                                IconButton(onClick = { showSpeedDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = "Speed",
                                        tint = Color.White
                                    )
                                }

                                // Quality control button
                                IconButton(onClick = { showQualityDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.HighQuality,
                                        contentDescription = "Quality",
                                        tint = Color.White
                                    )
                                }

                                IconButton(
                                    onClick = { isLocked = true }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Lock",
                                        tint = Color.White
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                IconButton(onClick = { showScalingDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.AspectRatio,
                                        contentDescription = "Scaling",
                                        tint = Color.White
                                    )
                                }

                                IconButton(
                                    onClick = { isFullscreen = !isFullscreen }
                                ) {
                                    Icon(
                                        imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // All dialogs (same as original plus quality dialog)
        // ... (Include all the dialog implementations from the original code)

        // Quality Dialog (new for cloud streaming)
        if (showQualityDialog) {
            Dialog(onDismissRequest = { showQualityDialog = false }) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Video Quality",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        val qualityOptions = listOf(
                            "Auto" to "Auto",
                            "1080p" to "1080p",
                            "720p" to "720p",
                            "480p" to "480p",
                            "360p" to "360p"
                        )

                        qualityOptions.forEach { (label, quality) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        networkQuality = quality

                                        // Apply quality settings
                                        val parametersBuilder = trackSelector.buildUponParameters()
                                        when (quality) {
                                            "Auto" -> parametersBuilder.clearVideoSizeConstraints()
                                            "1080p" -> parametersBuilder.setMaxVideoSize(1920, 1080)
                                            "720p" -> parametersBuilder.setMaxVideoSize(1280, 720)
                                            "480p" -> parametersBuilder.setMaxVideoSize(854, 480)
                                            "360p" -> parametersBuilder.setMaxVideoSize(640, 360)
                                        }
                                        trackSelector.setParameters(parametersBuilder.build())

                                        showQualityDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = networkQuality == quality,
                                    onClick = {
                                        networkQuality = quality
                                        showQualityDialog = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Utility functions (same as original)
@SuppressLint("DefaultLocale")
private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

