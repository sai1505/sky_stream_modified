package com.example.skystream.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.resume

class GoogleDriveAuthManager(private val context: Context) {

    private val authStateManager = AuthStateManager.getInstance(context)

    private val _signedInAccounts = MutableStateFlow<Map<String, GoogleUserInfo>>(emptyMap())
    val signedInAccounts: StateFlow<Map<String, GoogleUserInfo>> = _signedInAccounts.asStateFlow()

    private val _currentAccount = MutableStateFlow<GoogleUserInfo?>(null)
    val currentAccount: StateFlow<GoogleUserInfo?> = _currentAccount.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    // Your OAuth 2.0 credentials
    private val clientId = "295250683661-larc5033eommnjaf5dgohi7d7aldf07u.apps.googleusercontent.com"
    private val redirectUri = "com.example.skystream:/oauth2redirect"

    // Google OAuth endpoints
    private val authorizationEndpoint = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
    private val tokenEndpoint = Uri.parse("https://oauth2.googleapis.com/token")
    private val userInfoEndpoint = Uri.parse("https://www.googleapis.com/oauth2/v2/userinfo")

    // Google Drive scopes
    private val scopes = listOf(
        "https://www.googleapis.com/auth/drive.readonly",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/userinfo.email"
    )

    // HTTP client for API calls
    private val httpClient = OkHttpClient()

    companion object {
        private const val TAG = "GoogleDriveAuthManager"
    }

    private val serviceConfiguration = AuthorizationServiceConfiguration(
        authorizationEndpoint,
        tokenEndpoint
    )

    init {
        // Load persisted authentication state on initialization
        loadPersistedAuthState()
    }

    // Add this method to GoogleDriveAuthManager.kt
    suspend fun refreshTokenIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentAuthState = authStateManager.getCurrent()
            if (currentAuthState == null) {
                Log.w(TAG, "No auth state available for token refresh")
                return@withContext false
            }

            if (!currentAuthState.needsTokenRefresh) {
                Log.d(TAG, "Token refresh not needed")
                return@withContext true
            }

            Log.d(TAG, "Token refresh needed, attempting refresh...")

            return@withContext suspendCancellableCoroutine { continuation ->
                currentAuthState.performActionWithFreshTokens(
                    AuthorizationService(context)
                ) { accessToken, _, ex ->
                    if (ex != null) {
                        Log.e(TAG, "Token refresh failed", ex)
                        continuation.resume(false)
                    } else if (accessToken != null) {
                        Log.d(TAG, "Token refreshed successfully")

                        try {
                            // Get account ID from current account
                            val accountId = _currentAccount.value?.email ?: "default_account"

                            // Update the AuthState with refreshed token
                            authStateManager.replace(currentAuthState, accountId)

                            Log.d(TAG, "AuthState updated successfully for account: $accountId")
                            continuation.resume(true)

                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating AuthState after token refresh", e)
                            continuation.resume(false)
                        }
                    } else {
                        Log.e(TAG, "Token refresh returned null access token")
                        continuation.resume(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during token refresh", e)
            return@withContext false
        }
    }

    // Add to GoogleDriveAuthManager.kt
    suspend fun ensureValidToken(): Boolean {
        return try {
            val authState = authStateManager.getCurrent()

            if (authState == null || !authState.isAuthorized) {
                Log.w(TAG, "No valid auth state available")
                return false
            }

            // Check if token needs refresh
            if (authState.needsTokenRefresh) {
                Log.d(TAG, "Token needs refresh, attempting...")
                return refreshTokenIfNeeded()
            }

            // Token is valid
            Log.d(TAG, "Token is valid and fresh")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error validating token", e)
            false
        }
    }

    // Add to GoogleDriveAuthManager.kt
    private fun startTokenRefreshTimer() {
        // Refresh token every 45 minutes (before 1-hour expiration)
        Timer().schedule(object : TimerTask() {
            override fun run() {
                if (isAuthenticated.value) {
                    GlobalScope.launch {
                        refreshTokenIfNeeded()
                    }
                }
            }
        }, 45 * 60 * 1000L, 45 * 60 * 1000L) // 45 minutes
    }





    // Add token validation method
    fun isTokenValid(): Boolean {
        val authState = authStateManager.getCurrent()
        return authState.isAuthorized && !authState.needsTokenRefresh
    }


    private fun loadPersistedAuthState() {
        try {
            val currentAuthState = authStateManager.getCurrent()
            val currentAccountId = authStateManager.getCurrentAccountId()

            if (currentAuthState.isAuthorized && currentAccountId != null) {
                Log.d(TAG, "Found persisted auth state for account: $currentAccountId")
                _isAuthenticated.value = true

                // Load user info for current account
                getUserInfoFromAuthState(currentAuthState) { userInfo ->
                    if (userInfo != null) {
                        _currentAccount.value = userInfo
                        updateSignedInAccounts()
                    }
                }
            }

            // Load all stored accounts
            updateSignedInAccounts()

        } catch (e: Exception) {
            Log.e(TAG, "Error loading persisted auth state", e)
        }
    }

    fun createAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(
            serviceConfiguration,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri)
        )
            .setScopes(scopes)
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()
    }

    suspend fun handleAuthorizationResponse(
        authService: AuthorizationService,
        response: AuthorizationResponse
    ): Result<GoogleUserInfo> = suspendCancellableCoroutine { continuation ->

        val tokenRequest = response.createTokenExchangeRequest()

        Log.d(TAG, "Starting token exchange...")

        authService.performTokenRequest(tokenRequest) { tokenResponse, exception ->
            when {
                exception != null -> {
                    Log.e(TAG, "Token exchange failed: ${exception.message}")
                    continuation.resume(Result.failure(exception))
                }
                tokenResponse != null -> {
                    try {
                        Log.d(TAG, "Token exchange successful")

                        val authState = AuthState(response, null as AuthorizationException?)
                        authState.update(tokenResponse, null as AuthorizationException?)

                        // Get user info first to get account ID
                        getUserInfoFromAuthState(authState) { userInfo ->
                            if (userInfo != null) {
                                // Save AuthState with user email as account ID
                                authStateManager.replace(authState, userInfo.email)

                                _currentAccount.value = userInfo
                                _isAuthenticated.value = true

                                updateSignedInAccounts()

                                Log.d(TAG, "Account saved and set as current: ${userInfo.email}")
                                continuation.resume(Result.success(userInfo))
                            } else {
                                continuation.resume(Result.failure(Exception("Failed to get user info")))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process auth response", e)
                        continuation.resume(Result.failure(e))
                    }
                }
                else -> {
                    continuation.resume(Result.failure(Exception("Both tokenResponse and exception are null")))
                }
            }
        }
    }

    private fun getUserInfoFromAuthState(authState: AuthState, callback: (GoogleUserInfo?) -> Unit) {
        authState.performActionWithFreshTokens(AuthorizationService(context)) { accessToken, _, ex ->
            if (ex != null || accessToken == null) {
                Log.e(TAG, "Failed to get fresh token", ex)
                callback(null)
                return@performActionWithFreshTokens
            }

            fetchUserInfoFromApi(accessToken, callback)
        }
    }

    private fun fetchUserInfoFromApi(accessToken: String, callback: (GoogleUserInfo?) -> Unit) {
        Thread {
            try {
                val request = Request.Builder()
                    .url(userInfoEndpoint.toString())
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val jsonObject = JSONObject(responseBody)
                        val userInfo = GoogleUserInfo(
                            id = jsonObject.optString("id", ""),
                            email = jsonObject.optString("email", ""),
                            name = jsonObject.optString("name", ""),
                            picture = jsonObject.optString("picture", null)
                        )
                        callback(userInfo)
                    } else {
                        callback(null)
                    }
                } else {
                    Log.e(TAG, "HTTP request failed: ${response.code}")
                    callback(null)
                }
                response.close()
            } catch (e: IOException) {
                Log.e(TAG, "Network error while fetching user info", e)
                callback(null)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing user info response", e)
                callback(null)
            }
        }.start()
    }

    private fun updateSignedInAccounts() {
        val allAccounts = authStateManager.getAllAccounts()
        val accountsMap = mutableMapOf<String, GoogleUserInfo>()

        // For each stored account, try to get user info
        allAccounts.forEach { (accountId, authState) ->
            if (authState.isAuthorized) {
                // Create user info from stored account ID (email)
                // In a production app, you might want to store user info separately
                accountsMap[accountId] = GoogleUserInfo(
                    id = accountId,
                    email = accountId,
                    name = accountId.substringBefore("@"),
                    picture = null
                )
            }
        }

        _signedInAccounts.value = accountsMap
    }

    fun switchToAccount(accountEmail: String): Boolean {
        val success = authStateManager.switchToAccount(accountEmail)
        if (success) {
            val authState = authStateManager.getCurrent()
            if (authState.isAuthorized) {
                _isAuthenticated.value = true
                getUserInfoFromAuthState(authState) { userInfo ->
                    _currentAccount.value = userInfo
                }
                Log.d(TAG, "Switched to account: $accountEmail")
                return true
            }
        }
        return false
    }

    fun removeAccount(accountEmail: String) {
        authStateManager.removeAccount(accountEmail)

        // If removed account was current, clear current account
        if (_currentAccount.value?.email == accountEmail) {
            _currentAccount.value = null
            _isAuthenticated.value = false
        }

        updateSignedInAccounts()
        Log.d(TAG, "Removed account: $accountEmail")
    }

    fun signOut() {
        val currentAccountEmail = _currentAccount.value?.email
        if (currentAccountEmail != null) {
            authStateManager.removeAccount(currentAccountEmail)
        }

        _currentAccount.value = null
        _isAuthenticated.value = false
        updateSignedInAccounts()

        Log.d(TAG, "Signed out current account")
    }

    fun signOutAll() {
        authStateManager.clearAll()
        _currentAccount.value = null
        _isAuthenticated.value = false
        _signedInAccounts.value = emptyMap()
        Log.d(TAG, "Signed out all accounts")
    }

    fun getAccessToken(): String? {
        return authStateManager.getCurrent().accessToken
    }

    fun hasStoredAccounts(): Boolean {
        return authStateManager.hasStoredAccounts()
    }
}



data class GoogleUserInfo(
    val id: String,
    val email: String,
    val name: String,
    val picture: String?
)
