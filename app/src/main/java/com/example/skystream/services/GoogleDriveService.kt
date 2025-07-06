package com.example.skystream.services

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

data class DriveVideoFile(
    val id: String,
    val name: String,
    val size: Long?,
    val mimeType: String,
    val webViewLink: String?,
    val thumbnailLink: String?,
    val createdTime: String?,
    val modifiedTime: String?
)

class GoogleDriveService(
    private val context: Context,
    private val accessToken: String
) {
    // ✅ ADDED: HTTP client initialization - ONLY CHANGE MADE
    private val httpClient = OkHttpClient()

    private val driveService: Drive by lazy {
        // ✅ Fixed: Use HttpRequestInitializer for authentication
        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
            request.connectTimeout = 30000 // 30 seconds
            request.readTimeout = 30000 // 30 seconds
        }

        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            requestInitializer
        )
            .setApplicationName("SkyStream")
            .build()
    }

    companion object {
        private const val TAG = "GoogleDriveService"
        private const val PAGE_SIZE = 50
    }

    suspend fun getDirectVideoStreamUrl(fileId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // Method 1: Try direct download URL
                val directUrl = "https://drive.google.com/uc?export=download&id=$fileId"

                // Method 2: Alternative format for larger files
                val alternativeUrl = "https://drive.google.com/uc?export=download&id=$fileId&confirm=t"

                // ✅ CORRECT: Use OkHttp Request.Builder()
                val testRequest = Request.Builder()
                    .url(directUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .head() // HEAD request to test accessibility
                    .build()

                val response = httpClient.newCall(testRequest).execute()

                val finalUrl = if (response.isSuccessful) {
                    directUrl
                } else {
                    alternativeUrl
                }

                response.close()

                Log.d(TAG, "Using direct stream URL: $finalUrl")
                Result.success(finalUrl)

            } catch (e: Exception) {
                Log.e(TAG, "Error generating direct URL", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getVideoFiles(pageToken: String? = null): Result<Pair<List<DriveVideoFile>, String?>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching personal video files from Google Drive...")
                Log.d(TAG, "Using access token: ${accessToken.take(20)}...")

                val request = driveService.files().list().apply {
                    q = "(mimeType contains 'video/' or mimeType = 'video/mp4' or mimeType = 'video/avi' or mimeType = 'video/mov' or mimeType = 'video/wmv' or mimeType = 'video/flv' or mimeType = 'video/webm' or mimeType = 'video/mkv') and trashed = false and 'me' in owners"
                    spaces = "drive"
                    fields = "nextPageToken, files(id, name, size, mimeType, webViewLink, thumbnailLink, createdTime, modifiedTime, owners)"
                    pageSize = 100
                    orderBy = "modifiedTime desc"
                    this.pageToken = pageToken
                }

                val result: FileList = request.execute()

                Log.d(TAG, "Raw API response: Found ${result.files?.size ?: 0} personal video files")

                val videoFiles = result.files?.map { file ->
                    Log.d(TAG, "Personal video file: ${file.name}, MIME: ${file.mimeType}")
                    DriveVideoFile(
                        id = file.id,
                        name = file.name ?: "Unknown",
                        size = file.getSize(),
                        mimeType = file.mimeType ?: "",
                        webViewLink = file.webViewLink,
                        thumbnailLink = file.thumbnailLink,
                        createdTime = file.createdTime?.toString(),
                        modifiedTime = file.modifiedTime?.toString()
                    )
                } ?: emptyList()

                Log.d(TAG, "Successfully fetched ${videoFiles.size} personal video files")
                Result.success(Pair(videoFiles, result.nextPageToken))

            } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                when (e.statusCode) {
                    401 -> {
                        Log.e(TAG, "Authentication failed - token expired or invalid")
                        Result.failure(Exception("Authentication expired. Please sign in again."))
                    }
                    403 -> {
                        Log.e(TAG, "Permission denied - insufficient scopes")
                        Result.failure(Exception("Permission denied. Please check app permissions."))
                    }
                    else -> {
                        Log.e(TAG, "Google API error: ${e.statusCode} - ${e.message}")
                        Result.failure(Exception("API error: ${e.message}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching personal video files", e)
                Result.failure(Exception("Failed to fetch personal videos: ${e.message}"))
            }
        }
    }


    // Update your GoogleDriveService.kt
    suspend fun getVideoStreamUrl(fileId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Getting stream URL for file: $fileId")

                // Get file metadata first
                val file = driveService.files().get(fileId)
                    .setFields("id, name, mimeType, size")
                    .execute()

                Log.d(TAG, "File details:")
                Log.d(TAG, "  Name: ${file.name}")
                Log.d(TAG, "  MIME: ${file.mimeType}")
                Log.d(TAG, "  Size: ${file.getSize()}")

                // Use the new direct URL method
                getDirectVideoStreamUrl(fileId)

            } catch (e: Exception) {
                Log.e(TAG, "Error getting stream URL", e)
                Result.failure(Exception("Failed to get stream URL: ${e.message}"))
            }
        }
    }

    suspend fun searchVideoFiles(query: String): Result<List<DriveVideoFile>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Searching personal video files with query: $query")

                val request = driveService.files().list().apply {
                    // ✅ Search only in YOUR files
                    q = "(mimeType contains 'video/' or mimeType = 'video/mp4' or mimeType = 'video/avi' or mimeType = 'video/mov' or mimeType = 'video/wmv' or mimeType = 'video/flv' or mimeType = 'video/webm' or mimeType = 'video/mkv') and name contains '$query' and trashed = false and 'me' in owners"

                    spaces = "drive"
                    fields = "files(id, name, size, mimeType, webViewLink, thumbnailLink, createdTime, modifiedTime, owners)"
                    pageSize = 100
                    orderBy = "modifiedTime desc"
                }

                val result: FileList = request.execute()

                val videoFiles = result.files?.map { file ->
                    DriveVideoFile(
                        id = file.id,
                        name = file.name ?: "Unknown",
                        size = file.getSize(),
                        mimeType = file.mimeType ?: "",
                        webViewLink = file.webViewLink,
                        thumbnailLink = file.thumbnailLink,
                        createdTime = file.createdTime?.toString(),
                        modifiedTime = file.modifiedTime?.toString()
                    )
                } ?: emptyList()

                Log.d(TAG, "Found ${videoFiles.size} personal video files matching query")
                Result.success(videoFiles)

            } catch (e: Exception) {
                Log.e(TAG, "Error searching personal video files", e)
                Result.failure(Exception("Search failed: ${e.message}"))
            }
        }
    }
}
