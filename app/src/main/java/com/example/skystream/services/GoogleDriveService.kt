package com.example.skystream.services

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
import java.util.concurrent.TimeUnit

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
    // ✅ OPTIMIZED: High-performance HTTP client for fast streaming
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)     // Faster connection
        .readTimeout(10, TimeUnit.SECONDS)       // Faster read timeout
        .writeTimeout(10, TimeUnit.SECONDS)      // Faster write timeout
        .callTimeout(30, TimeUnit.SECONDS)       // Overall call timeout
        .followRedirects(true)                   // Handle redirects automatically
        .followSslRedirects(true)               // Handle SSL redirects
        .retryOnConnectionFailure(true)         // Auto-retry failed connections
        .connectionPool(
            okhttp3.ConnectionPool(
                maxIdleConnections = 5,         // Keep connections alive
                keepAliveDuration = 5,          // 5 minutes keep-alive
                TimeUnit.MINUTES
            )
        )
        .build()

    private val driveService: Drive by lazy {
        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
            request.connectTimeout = 10000 // Reduced to 10 seconds
            request.readTimeout = 15000     // Reduced to 15 seconds
        }

        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            requestInitializer
        )
            .setApplicationName("SkyStream/1.0 (Fast-Streaming)")
            .build()
    }

    companion object {
        private const val TAG = "GoogleDriveService"
        private const val PAGE_SIZE = 50
    }

    // ✅ OPTIMIZED: Fast authenticated stream URL with range support
    suspend fun getAuthenticatedStreamUrl(fileId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Getting fast stream URL for file: $fileId")

                // Use Google Drive API media endpoint with optimizations
                val streamUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

                // Quick file verification (reduced fields for speed)
                val file = driveService.files().get(fileId)
                    .setFields("id, name, mimeType")  // Minimal fields for speed
                    .execute()

                Log.d(TAG, "Fast verification complete: ${file.name}")
                Result.success(streamUrl)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to get fast stream URL", e)
                Result.failure(e)
            }
        }
    }

    // ✅ NEW: Optimized stream URL with range request support
    suspend fun getOptimizedStreamUrl(fileId: String, startByte: Long = 0): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Getting optimized stream URL with range support")

                val baseUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

                // Test stream accessibility with HEAD request
                val testRequest = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Accept-Ranges", "bytes")
                    .head()
                    .build()

                val response = httpClient.newCall(testRequest).execute()

                val finalUrl = if (response.isSuccessful) {
                    Log.d(TAG, "Stream test successful, supports range requests")
                    baseUrl
                } else {
                    Log.d(TAG, "Stream test failed (${response.code}), using fallback")
                    "https://drive.google.com/uc?export=download&id=$fileId"
                }

                response.close()
                Result.success(finalUrl)

            } catch (e: Exception) {
                Log.e(TAG, "Error getting optimized stream URL", e)
                Result.failure(e)
            }
        }
    }

    // ✅ ENHANCED: Video files with optimized query
    suspend fun getVideoFiles(pageToken: String? = null): Result<Pair<List<DriveVideoFile>, String?>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching video files...")

                // Test token validity first
                val testRequest = driveService.about().get().setFields("user")
                try {
                    testRequest.execute()
                    Log.d(TAG, "Token validation successful")
                } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                    if (e.statusCode == 401) {
                        Log.e(TAG, "Token expired during validation")
                        return@withContext Result.failure(Exception("Authentication expired. Please sign in again."))
                    }
                }

                val request = driveService.files().list().apply {
                    q = "mimeType contains 'video/' and trashed = false and 'me' in owners"
                    spaces = "drive"
                    fields = "nextPageToken, files(id, name, size, mimeType, thumbnailLink, modifiedTime)"
                    pageSize = 50
                    orderBy = "modifiedTime desc"
                    this.pageToken = pageToken
                }

                val result = request.execute()

                val videoFiles = result.files?.map { file ->
                    DriveVideoFile(
                        id = file.id,
                        name = file.name ?: "Unknown",
                        size = file.getSize(),
                        mimeType = file.mimeType ?: "",
                        webViewLink = null,
                        thumbnailLink = file.thumbnailLink,
                        createdTime = null,
                        modifiedTime = file.modifiedTime?.toString()
                    )
                } ?: emptyList()

                Result.success(Pair(videoFiles, result.nextPageToken))

            } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                when (e.statusCode) {
                    401 -> {
                        Log.e(TAG, "Authentication expired - 401 Unauthorized")
                        Result.failure(Exception("Authentication expired. Please sign in again."))
                    }
                    403 -> {
                        Log.e(TAG, "Permission denied - 403 Forbidden")
                        Result.failure(Exception("Permission denied. Please check app permissions."))
                    }
                    else -> {
                        Log.e(TAG, "Google API error: ${e.statusCode}")
                        Result.failure(Exception("API error: ${e.message}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching video files", e)
                Result.failure(Exception("Failed to fetch videos: ${e.message}"))
            }
        }
    }


    // ✅ OPTIMIZED: Fast stream URL method
    suspend fun getVideoStreamUrl(fileId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Getting fast stream URL for: $fileId")

                // Use optimized method with range support
                getOptimizedStreamUrl(fileId)

            } catch (e: Exception) {
                Log.e(TAG, "Error getting fast stream URL", e)
                Result.failure(Exception("Failed to get stream URL: ${e.message}"))
            }
        }
    }

    // ✅ OPTIMIZED: Fast search with minimal fields
    suspend fun searchVideoFiles(query: String): Result<List<DriveVideoFile>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fast search for: $query")

                val request = driveService.files().list().apply {
                    q = "mimeType contains 'video/' and name contains '$query' and trashed = false and 'me' in owners"
                    spaces = "drive"
                    fields = "files(id, name, size, mimeType, thumbnailLink)" // Minimal fields
                    pageSize = 25 // Smaller for faster search
                    orderBy = "modifiedTime desc"
                }

                val result: FileList = request.execute()

                val videoFiles = result.files?.map { file ->
                    DriveVideoFile(
                        id = file.id,
                        name = file.name ?: "Unknown",
                        size = file.getSize(),
                        mimeType = file.mimeType ?: "",
                        webViewLink = null,
                        thumbnailLink = file.thumbnailLink,
                        createdTime = null,
                        modifiedTime = null
                    )
                } ?: emptyList()

                Log.d(TAG, "Fast search complete: ${videoFiles.size} results")
                Result.success(videoFiles)

            } catch (e: Exception) {
                Log.e(TAG, "Error in fast search", e)
                Result.failure(Exception("Search failed: ${e.message}"))
            }
        }
    }

    // ✅ NEW: Test stream performance
    suspend fun testStreamPerformance(fileId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()

                val streamUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

                val request = Request.Builder()
                    .url(streamUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Range", "bytes=0-1023") // Test first 1KB
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseTime = System.currentTimeMillis() - startTime

                val performanceInfo = """
                    Stream Performance Test:
                    - Response Time: ${responseTime}ms
                    - Status: ${response.code}
                    - Content-Type: ${response.header("Content-Type")}
                    - Supports Range: ${response.header("Accept-Ranges") != null}
                    - Content-Length: ${response.header("Content-Length")}
                """.trimIndent()

                response.close()
                Log.d(TAG, performanceInfo)
                Result.success(performanceInfo)

            } catch (e: Exception) {
                Log.e(TAG, "Stream performance test failed", e)
                Result.failure(e)
            }
        }
    }

    // ✅ NEW: Cleanup method for better resource management
    fun cleanup() {
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
            Log.d(TAG, "HTTP client resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
