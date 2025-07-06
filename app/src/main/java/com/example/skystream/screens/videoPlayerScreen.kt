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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

data class AudioTrack(
    val id: String,
    val language: String?,
    val label: String,
    val trackGroup: TrackGroup,
    val trackIndex: Int
)

data class SubtitleTrack(
    val id: String,
    val language: String?,
    val label: String,
    val trackGroup: TrackGroup,
    val trackIndex: Int
)

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    video: VideoItem,
    videoList: List<VideoItem>,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity

    // Enhanced state management for keep screen on
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(false) }
    var userKeepScreenOnEnabled by rememberSaveable { mutableStateOf(true) }
    var keepScreenOnTimeout by remember { mutableStateOf(0L) }
    val keepScreenOnDuration = 30000L // 30 seconds after pausing

    // SYSTEM AUDIO MANAGER - Updated for real Android volume control
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // Get system volume info
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentSystemVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // Convert to 0.0f - 1.0f range for UI
    var currentVolume by remember {
        mutableFloatStateOf(currentSystemVolume.toFloat() / maxVolume.toFloat())
    }

    // SYSTEM BRIGHTNESS - Updated for real Android brightness control
    val contentResolver = context.contentResolver

    // Get current system brightness (0-255 range)
    var currentSystemBrightness by remember {
        mutableIntStateOf(
            try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) {
                128 // Default to 50%
            }
        )
    }

    // Convert to 0.0f - 1.0f range for UI
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

    // Volume and Brightness States
    var showVolumeSlider by remember { mutableStateOf(false) }
    var showBrightnessSlider by remember { mutableStateOf(false) }

    // Video State
    var currentVideoIndex by rememberSaveable { mutableIntStateOf(videoList.indexOf(video)) }
    var scalingMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1.0f) }

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

    // Request brightness permission on first launch
    RequestWriteSettingsPermission(context)

    // ADVANCED KEEP SCREEN ON LOGIC with timeout
    LaunchedEffect(isPlaying, isBuffering, isControlsVisible, userKeepScreenOnEnabled) {
        when {
            !userKeepScreenOnEnabled -> {
                keepScreenOn = false
                keepScreenOnTimeout = 0L
            }
            isPlaying || isBuffering -> {
                keepScreenOn = true
                keepScreenOnTimeout = 0L // Reset timeout when playing/buffering
            }
            isControlsVisible -> {
                keepScreenOn = true
                keepScreenOnTimeout = System.currentTimeMillis() + keepScreenOnDuration
            }
            else -> {
                // Check if timeout has expired
                if (keepScreenOnTimeout > 0L && System.currentTimeMillis() > keepScreenOnTimeout) {
                    keepScreenOn = false
                    keepScreenOnTimeout = 0L
                }
            }
        }
    }

    // Timeout checker coroutine
    LaunchedEffect(keepScreenOnTimeout) {
        if (keepScreenOnTimeout > 0L) {
            val timeToWait = keepScreenOnTimeout - System.currentTimeMillis()
            if (timeToWait > 0) {
                delay(timeToWait)
                if (System.currentTimeMillis() >= keepScreenOnTimeout && !isPlaying && !isBuffering && !isControlsVisible) {
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

    // SYSTEM VOLUME CONTROL FUNCTION
    fun setSystemVolume(volume: Float) {
        val volumeLevel = (volume * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel, 0)
        currentSystemVolume = volumeLevel
        currentVolume = volume
    }

    // SYSTEM BRIGHTNESS CONTROL FUNCTION
    fun setSystemBrightness(brightness: Float) {
        val brightnessLevel = (brightness * 255).toInt().coerceIn(0, 255)
        try {
            // Check permission first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(context)) {
                    // Request permission
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            // Set system brightness mode to manual
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            // Set the actual brightness value
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessLevel
            )

            // Also set window brightness for immediate effect
            val window = activity.window
            val layoutParams = window.attributes
            layoutParams.screenBrightness = brightness
            window.attributes = layoutParams

            currentSystemBrightness = brightnessLevel
            currentBrightness = brightness
        } catch (e: Exception) {
            // Fallback to window brightness only
            val window = activity.window
            val layoutParams = window.attributes
            layoutParams.screenBrightness = brightness
            window.attributes = layoutParams
            currentBrightness = brightness
        }
    }

    // SYSTEM UI - Hide status bar for immersive experience
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

    // ENHANCED TRACK SELECTOR
    val trackSelector = remember {
        DefaultTrackSelector(context).apply {
            val parametersBuilder = buildUponParameters()
                .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxVideoSizeSd()
                .setPreferredAudioLanguage(null)
                .setForceLowestBitrate(false)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
            setParameters(parametersBuilder.build())
        }
    }

    // YOUTUBE-STYLE EXOPLAYER
    val exoPlayer = remember(currentVideo.uri) {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2000, 15000, 1000, 2000)
                    .setTargetBufferBytes(8 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setBackBuffer(5000, true)
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

    // ENHANCED PLAYER CLEANUP
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

    // ENHANCED VIDEO LOADING
    LaunchedEffect(currentVideo.uri) {
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            System.gc()
            delay(100)

            val mediaItem = MediaItem.Builder()
                .setUri(currentVideo.uri)
                .build()

            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            exoPlayer.setPlaybackSpeed(playbackSpeed)

            subtitleTracks = emptyList()
            audioTracks = emptyList()
            selectedSubtitleTrack = -1
            selectedAudioTrack = 0
            isSubtitlesEnabled = false
            currentPosition = 0L
            duration = 0L
        } catch (e: Exception) {
            // Handle loading errors gracefully
        }
    }

    // PRELOAD NEXT VIDEO
    LaunchedEffect(currentVideoIndex) {
        if (currentVideoIndex < videoList.size - 1) {
            delay(5000)
            try {
                val nextVideo = videoList[currentVideoIndex + 1]
                val preloadPlayer = ExoPlayer.Builder(context)
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(1000, 5000, 500, 1000)
                            .setTargetBufferBytes(2 * 1024 * 1024)
                            .build()
                    )
                    .build()

                val preloadItem = MediaItem.fromUri(nextVideo.uri)
                preloadPlayer.setMediaItem(preloadItem)
                preloadPlayer.prepare()

                delay(3000)
                preloadPlayer.release()
            } catch (e: Exception) {
                // Ignore preload errors
            }
        }
    }

    // ORIENTATION HANDLING
    LaunchedEffect(isFullscreen) {
        activity.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Enhanced player listener with buffering state
    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }


            // NAVIGATION FUNCTIONS
            fun navigateToNext() {
                if (currentVideoIndex < videoList.size - 1) {
                    currentPosition = 0L
                    currentVideoIndex++
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L

                // Update buffering state for keep screen on logic
                isBuffering = playbackState == Player.STATE_BUFFERING

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        // Keep screen on during buffering
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

        // Position updates with system sync
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

    // AUDIO TRACK SELECTION
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

    // SUBTITLE TRACK ENABLING
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

    // SUBTITLE DISABLING
    fun disableSubtitles() {
        val parametersBuilder = trackSelector.buildUponParameters()
            .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
        trackSelector.setParameters(parametersBuilder.build())
        isSubtitlesEnabled = false
        selectedSubtitleTrack = -1
    }

    // PLAYBACK SPEED CONTROL
    fun setPlaybackSpeedAndSave(speed: Float) {
        playbackSpeed = speed
        exoPlayer.setPlaybackSpeed(speed)
    }

    // AUTO-HIDE CONTROLS
    LaunchedEffect(isControlsVisible) {
        if (isControlsVisible && !isLocked) {
            delay(4000)
            isControlsVisible = false
        }
    }

    // BACK PRESS HANDLING
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

    // UI LAYOUT
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // VIDEO PLAYER VIEW
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
                                    // SYSTEM BRIGHTNESS CONTROL
                                    val brightnessChange = -deltaY / screenHeight
                                    val newBrightness = (currentBrightness + brightnessChange).coerceIn(0.1f, 1.0f)
                                    setSystemBrightness(newBrightness)
                                } else {
                                    // SYSTEM VOLUME CONTROL
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

        // SUBTITLE OVERLAY
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

        // SYSTEM VOLUME SLIDER
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

        // SYSTEM BRIGHTNESS SLIDER
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

        // CONDITIONAL UNLOCK BUTTON - Only visible when locked
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

        // PLAYER CONTROLS
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
                // TOP CONTROLS
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
                        Text(
                            text = currentVideo.displayName,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${currentVideoIndex + 1} of ${videoList.size}",
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

                // CENTER CONTROLS
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

                // BOTTOM CONTROLS
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    // Progress bar
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

                    // Bottom action buttons with lock button
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

                            // LOCK BUTTON - In bottom controls when unlocked
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

        // SUBTITLE DIALOG
        if (showSubtitleDialog) {
            Dialog(
                onDismissRequest = { showSubtitleDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.6f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Subtitles",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        LazyColumn {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            disableSubtitles()
                                            showSubtitleDialog = false
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = !isSubtitlesEnabled,
                                        onClick = {
                                            disableSubtitles()
                                            showSubtitleDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Off")
                                }
                            }

                            items(subtitleTracks) { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            enableSubtitleTrack(subtitleTracks.indexOf(track))
                                            showSubtitleDialog = false
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSubtitlesEnabled && selectedSubtitleTrack == subtitleTracks.indexOf(track),
                                        onClick = {
                                            enableSubtitleTrack(subtitleTracks.indexOf(track))
                                            showSubtitleDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(track.label)
                                }
                            }
                        }
                    }
                }
            }
        }

        // AUDIO DIALOG
        if (showAudioDialog) {
            Dialog(
                onDismissRequest = { showAudioDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.6f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Audio Tracks",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        LazyColumn {
                            items(audioTracks) { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectAudioTrack(audioTracks.indexOf(track))
                                            showAudioDialog = false
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedAudioTrack == audioTracks.indexOf(track),
                                        onClick = {
                                            selectAudioTrack(audioTracks.indexOf(track))
                                            showAudioDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(track.label)
                                }
                            }
                        }
                    }
                }
            }
        }

        // SCALING DIALOG
        if (showScalingDialog) {
            Dialog(onDismissRequest = { showScalingDialog = false }) {
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
                            text = "Video Scaling",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        val scalingOptions = listOf(
                            "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
                            "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
                            "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                            "Fixed Height" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
                            "Fixed Width" to AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                        )

                        scalingOptions.forEach { (label, mode) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scalingMode = mode
                                        showScalingDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = scalingMode == mode,
                                    onClick = {
                                        scalingMode = mode
                                        showScalingDialog = false
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

        // SPEED DIALOG
        if (showSpeedDialog) {
            Dialog(onDismissRequest = { showSpeedDialog = false }) {
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
                            text = "Playback Speed",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        val speedOptions = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

                        speedOptions.forEach { speed ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        setPlaybackSpeedAndSave(speed)
                                        showSpeedDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = playbackSpeed == speed,
                                    onClick = {
                                        setPlaybackSpeedAndSave(speed)
                                        showSpeedDialog = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${speed}x")
                            }
                        }
                    }
                }
            }
        }

        // ENHANCED MORE MENU DIALOG with Keep Screen On Control
        if (showMoreMenu) {
            Dialog(onDismissRequest = { showMoreMenu = false }) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.95f), // wider, as you requested earlier
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        // Keep Screen On Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { userKeepScreenOnEnabled = !userKeepScreenOnEnabled }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Keep Screen On",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                Text(
                                    text = "Prevent screen from dimming during playback",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Switch(
                                checked = userKeepScreenOnEnabled,
                                onCheckedChange = { userKeepScreenOnEnabled = it }
                            )
                        }

// Screen On Status Indicator
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (keepScreenOn) Icons.Default.Lightbulb else Icons.Default.LightMode,
                                contentDescription = "Screen Status",
                                tint = if (keepScreenOn) Color.Green else Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (keepScreenOn) "Screen is staying on" else "Screen can dim",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (keepScreenOn) Color.Green else Color.Gray
                            )
                        }

                        // Timeout Information
                        if (keepScreenOnTimeout > 0L && !isPlaying && !isBuffering) {
                            val remainingTime = (keepScreenOnTimeout - System.currentTimeMillis()) / 1000
                            if (remainingTime > 0) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Timer",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Screen will dim in ${remainingTime}s",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RequestWriteSettingsPermission(context: Context) {
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(context)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Handle gracefully
                }
            }
        }
    }
}

// UTILITY FUNCTIONS
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

private fun formatFileSize(size: Long): String {
    return try {
        when {
            size <= 0 -> "0 B"
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${String.format("%.1f", size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${String.format("%.1f", size / (1024.0 * 1024.0))} MB"
            else -> "${String.format("%.1f", size / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    } catch (e: Exception) {
        "Unknown size"
    }
}
