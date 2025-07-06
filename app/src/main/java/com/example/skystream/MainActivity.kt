package com.example.skystream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.skystream.screens.CloudScreen
import com.example.skystream.screens.LocalScreen
import com.example.skystream.screens.SettingsScreen
import com.example.skystream.screens.VideoItem
import com.example.skystream.screens.VideoPlayerScreen
import com.example.skystream.screens.CloudVideoPlayerScreen
import com.example.skystream.services.DriveVideoFile
import com.example.skystream.auth.GoogleDriveAuthManager
import com.example.skystream.ui.theme.SkyStreamTheme
import com.example.skystream.widgets.AppLogoPlaceholder
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SkyStreamTheme {
                SkyStreamApp()
            }
        }
    }
}

sealed class AppScreen {
    object Main : AppScreen()
    object VideoPlayer : AppScreen()
    object CloudVideoPlayer : AppScreen()
}

// Enhanced Custom Saver for AppScreen
val AppScreenSaver = Saver<AppScreen, String>(
    save = { screen ->
        when (screen) {
            is AppScreen.Main -> "main"
            is AppScreen.VideoPlayer -> "video_player"
            is AppScreen.CloudVideoPlayer -> "cloud_video_player"
        }
    },
    restore = { value ->
        when (value) {
            "main" -> AppScreen.Main
            "video_player" -> AppScreen.VideoPlayer
            "cloud_video_player" -> AppScreen.CloudVideoPlayer
            else -> AppScreen.Main
        }
    }
)

@Composable
fun SkyStreamApp() {
    val context = LocalContext.current

    // Enhanced state management for both local and cloud videos
    var currentScreen by rememberSaveable(stateSaver = AppScreenSaver) {
        mutableStateOf<AppScreen>(AppScreen.Main)
    }

    // Local video states
    var selectedVideoId by rememberSaveable { mutableLongStateOf(-1L) }
    var selectedVideoUri by rememberSaveable { mutableStateOf("") }
    var selectedVideoName by rememberSaveable { mutableStateOf("") }
    var selectedVideoDuration by rememberSaveable { mutableLongStateOf(0L) }
    var selectedVideoSize by rememberSaveable { mutableLongStateOf(0L) }
    var selectedVideoPath by rememberSaveable { mutableStateOf("") }
    var selectedVideoThumbnailPath by rememberSaveable { mutableStateOf("") }
    var videoListData by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    // Cloud video states
    var selectedCloudVideo by remember { mutableStateOf<DriveVideoFile?>(null) }
    var cloudVideoList by remember { mutableStateOf<List<DriveVideoFile>>(emptyList()) }
    var cloudAuthManager by remember { mutableStateOf<GoogleDriveAuthManager?>(null) }

    when (currentScreen) {
        is AppScreen.Main -> {
            MainScreen(
                onVideoSelected = { video, videos ->
                    // Store local video data in saveable state
                    selectedVideoId = video.id
                    selectedVideoUri = video.uri.toString()
                    selectedVideoName = video.displayName
                    selectedVideoDuration = video.duration
                    selectedVideoSize = video.size
                    selectedVideoPath = video.path
                    selectedVideoThumbnailPath = video.thumbnailPath ?: ""

                    // Store video list as serializable data with thumbnail support
                    videoListData = videos.map {
                        "${it.id}|${it.uri}|${it.displayName}|${it.duration}|${it.size}|${it.path}|${it.thumbnailPath ?: ""}"
                    }

                    currentScreen = AppScreen.VideoPlayer
                },
                onCloudVideoSelected = { video, videos, authManager ->
                    // Store cloud video data in non-saveable state (for current session)
                    selectedCloudVideo = video
                    cloudVideoList = videos
                    cloudAuthManager = authManager
                    currentScreen = AppScreen.CloudVideoPlayer
                }
            )
        }

        is AppScreen.VideoPlayer -> {
            if (selectedVideoId != -1L && videoListData.isNotEmpty()) {
                // Reconstruct local video objects from saved data
                val videoList = videoListData.mapNotNull { data ->
                    val parts = data.split("|")
                    if (parts.size >= 6) {
                        VideoItem(
                            id = parts[0].toLongOrNull() ?: 0L,
                            uri = android.net.Uri.parse(parts[1]),
                            displayName = parts[2],
                            duration = parts[3].toLongOrNull() ?: 0L,
                            size = parts[4].toLongOrNull() ?: 0L,
                            path = parts[5],
                            thumbnailPath = if (parts.size > 6 && parts[6].isNotEmpty()) parts[6] else null
                        )
                    } else null
                }

                val selectedVideo = VideoItem(
                    id = selectedVideoId,
                    uri = android.net.Uri.parse(selectedVideoUri),
                    displayName = selectedVideoName,
                    duration = selectedVideoDuration,
                    size = selectedVideoSize,
                    path = selectedVideoPath,
                    thumbnailPath = if (selectedVideoThumbnailPath.isNotEmpty()) selectedVideoThumbnailPath else null
                )

                VideoPlayerScreen(
                    video = selectedVideo,
                    videoList = videoList,
                    onBackPressed = {
                        currentScreen = AppScreen.Main
                    }
                )
            } else {
                // Fallback to main screen if data is corrupted
                currentScreen = AppScreen.Main
            }
        }

        is AppScreen.CloudVideoPlayer -> {
            if (selectedCloudVideo != null && cloudAuthManager != null) {
                CloudVideoPlayerScreen(
                    video = selectedCloudVideo!!,
                    videoList = cloudVideoList,
                    authManager = cloudAuthManager!!,
                    onBackPressed = {
                        // Clear cloud video states when going back
                        selectedCloudVideo = null
                        cloudVideoList = emptyList()
                        cloudAuthManager = null
                        currentScreen = AppScreen.Main
                    }
                )
            } else {
                // Fallback to main screen if cloud data is missing
                currentScreen = AppScreen.Main
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onVideoSelected: (VideoItem, List<VideoItem>) -> Unit,
    onCloudVideoSelected: (DriveVideoFile, List<DriveVideoFile>, GoogleDriveAuthManager) -> Unit
) {
    // Use rememberSaveable for navigation state to survive rotation
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var showBar by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // Auto-hide timer effect
    LaunchedEffect(showBar, currentIndex) {
        if (showBar) {
            delay(10000) // 10 seconds
            showBar = false
        }
    }

    val onUserInteraction = {
        showBar = true
        coroutineScope.launch {
            delay(10000)
            showBar = false
        }
    }

    // Animated offset for bottom bar
    val bottomBarOffset by animateFloatAsState(
        targetValue = if (showBar) 0f else 1.2f,
        animationSpec = tween(durationMillis = 400),
        label = "bottomBarOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onUserInteraction() },
                    onPress = { onUserInteraction() }
                )
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top App Bar
            TopAppBar(
                title = {
                    Text(
                        text = "Sky Stream",
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.shadow(1.dp)
            )

            // Screen Content with enhanced navigation
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { onUserInteraction() }
                    }
            ) {
                when (currentIndex) {
                    0 -> LocalScreen(onVideoSelected = onVideoSelected)
                    1 -> CloudScreen(onVideoSelected = onCloudVideoSelected)
                    2 -> SettingsScreen()
                }
            }
        }

        // Custom Bottom Navigation Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .graphicsLayer {
                    translationY = bottomBarOffset * size.height
                }
        ) {
            CustomBottomNavigationBar(
                currentIndex = currentIndex,
                onIndexChanged = { newIndex ->
                    currentIndex = newIndex
                    onUserInteraction()
                }
            )
        }
    }
}

@Composable
fun CustomBottomNavigationBar(
    currentIndex: Int,
    onIndexChanged: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp),
        shape = RoundedCornerShape(35.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 10.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Logo Placeholder
            AppLogoPlaceholder()

            // Navigation Items
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NavBarItem(
                    icon = Icons.Rounded.Storage,
                    label = "Local",
                    selected = currentIndex == 0,
                    onTap = { onIndexChanged(0) },
                    modifier = Modifier.weight(1f)
                )

                NavBarItem(
                    icon = Icons.Outlined.CloudQueue,
                    label = "Cloud",
                    selected = currentIndex == 1,
                    onTap = { onIndexChanged(1) },
                    modifier = Modifier.weight(1f)
                )

                NavBarItem(
                    icon = Icons.Outlined.Settings,
                    label = "Settings",
                    selected = currentIndex == 2,
                    onTap = { onIndexChanged(2) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun NavBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable { onTap() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(if (selected) 28.dp else 24.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = if (selected) 15.sp else 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SkyStreamAppPreview() {
    SkyStreamTheme {
        SkyStreamApp()
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SkyStreamAppDarkPreview() {
    SkyStreamTheme {
        SkyStreamApp()
    }
}
