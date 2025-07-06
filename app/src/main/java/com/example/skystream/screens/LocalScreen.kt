package com.example.skystream.screens

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val duration: Long,
    val size: Long,
    val path: String,
    val thumbnailPath: String? = null
)

@Composable
fun LocalScreen(onVideoSelected: (VideoItem, List<VideoItem>) -> Unit) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableStateOf(0f) }
    var hasPermission by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Optimized ImageLoader with caching
    val videoImageLoader = remember {
        context.imageLoader.newBuilder()
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("video_thumbnails_coil"))
                    .maxSizeBytes(50 * 1024 * 1024) // 50MB
                    .build()
            }
            .build()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasVideoPermission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                permissions[Manifest.permission.READ_MEDIA_VIDEO] == true
            }
            else -> {
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
            }
        }

        hasPermission = hasVideoPermission
        if (hasPermission) {
            isLoading = true
            loadingProgress = 0f
            coroutineScope.launch {
                loadVideosWithProgress(context) { videoList, progress ->
                    if (progress < 1f) {
                        loadingProgress = progress
                    } else {
                        videos = videoList
                        isLoading = false
                        loadingProgress = 1f
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            }
            else -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permissionLauncher.launch(permissions)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        when {
            !hasPermission -> {
                PermissionDeniedContent(onRequestPermission = {
                    val permissions = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                        }
                        else -> {
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }
                    permissionLauncher.launch(permissions)
                })
            }
            isLoading -> {
                LoadingContentWithProgress(progress = loadingProgress)
            }
            videos.isEmpty() -> {
                EmptyContent()
            }
            else -> {
                VideoList(
                    videos = videos,
                    onVideoClick = { video ->
                        onVideoSelected(video, videos)
                    },
                    imageLoader = videoImageLoader
                )
            }
        }
    }
}

@Composable
fun LoadingContentWithProgress(progress: Float) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                progress = progress,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (progress < 1f) "Generating thumbnails... ${(progress * 100).toInt()}%" else "Loading videos...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading videos...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EmptyContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No Videos Found",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No video files were found on your device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PermissionDeniedContent(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Storage Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "We need access to your videos to display them",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

@Composable
fun VideoList(
    videos: List<VideoItem>,
    onVideoClick: (VideoItem) -> Unit,
    imageLoader: coil.ImageLoader
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = videos,
            key = { it.id },
            contentType = { "video_item" }
        ) { video ->
            VideoItemCard(
                video = video,
                onClick = { onVideoClick(video) },
                imageLoader = imageLoader
            )
        }
    }
}

@Composable
fun VideoItemCard(
    video: VideoItem,
    onClick: () -> Unit,
    imageLoader: coil.ImageLoader
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Optimized thumbnail loading with cached thumbnails
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // Use cached thumbnail if available, otherwise fallback to video URI
                val imageModel = video.thumbnailPath?.let { File(it) } ?: video.uri

                AsyncImage(
                    model = imageModel,
                    contentDescription = "Video thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    imageLoader = imageLoader,
                    placeholder = painterResource(android.R.drawable.ic_media_play),
                    error = painterResource(android.R.drawable.ic_media_play)
                )

                // Play button overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Video details
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = video.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "⏱",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = formatDuration(video.duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "💾",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = formatFileSize(video.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(duration: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(duration)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(duration) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60

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

private suspend fun loadVideosWithProgress(
    context: Context,
    onProgress: (List<VideoItem>, Float) -> Unit
) = withContext(Dispatchers.IO) {
    val videos = mutableListOf<VideoItem>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.MIME_TYPE
    )
    val selection = "${MediaStore.Video.Media.MIME_TYPE} LIKE ?"
    val selectionArgs = arrayOf("video/%")

    try {
        val contentUris = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.INTERNAL_CONTENT_URI
        )

        // First pass: collect all video metadata
        val videoMetadata = mutableListOf<VideoItem>()

        for (contentUri in contentUris) {
            val cursor = context.contentResolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val name = it.getString(nameColumn) ?: "Unknown"
                    val duration = it.getLong(durationColumn)
                    val size = it.getLong(sizeColumn)
                    val path = it.getString(dataColumn) ?: ""

                    if (size > 0 && duration > 1000) {
                        val uri = ContentUris.withAppendedId(contentUri, id)
                        videoMetadata.add(
                            VideoItem(
                                id = id,
                                uri = uri,
                                displayName = name,
                                duration = duration,
                                size = size,
                                path = path,
                                thumbnailPath = null
                            )
                        )
                    }
                }
            }
        }

        // Second pass: generate thumbnails with progress updates
        val totalVideos = videoMetadata.size
        videoMetadata.forEachIndexed { index, videoItem ->
            val thumbnailPath = generateThumbnail(context, videoItem.uri, videoItem.id)
            val updatedVideo = videoItem.copy(thumbnailPath = thumbnailPath)
            videos.add(updatedVideo)

            // Update progress
            val progress = (index + 1).toFloat() / totalVideos
            withContext(Dispatchers.Main) {
                onProgress(videos.toList(), progress)
            }
        }

    } catch (e: Exception) {
        e.printStackTrace()
    }

    withContext(Dispatchers.Main) {
        onProgress(videos, 1f)
    }
}

private suspend fun generateThumbnail(context: Context, videoUri: Uri, videoId: Long): String? {
    return try {
        val thumbnailDir = File(context.cacheDir, "video_thumbnails")
        if (!thumbnailDir.exists()) thumbnailDir.mkdirs()

        val thumbnailFile = File(thumbnailDir, "$videoId.jpg")

        // Check if thumbnail already exists
        if (thumbnailFile.exists()) {
            return thumbnailFile.absolutePath
        }

        // Generate new thumbnail
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)

        // Get frame at 1 second or 10% of video duration, whichever is smaller
        val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val duration = durationString?.toLongOrNull() ?: 0L
        val timeUs = minOf(1000000L, duration * 1000 / 10) // 1 second or 10% of duration

        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

        bitmap?.let {
            val fos = FileOutputStream(thumbnailFile)
            it.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            fos.close()
            it.recycle() // Free memory
        }

        retriever.release()

        if (thumbnailFile.exists()) {
            thumbnailFile.absolutePath
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
