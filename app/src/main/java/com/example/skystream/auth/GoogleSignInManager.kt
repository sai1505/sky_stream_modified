package com.example.skystream.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.skystream.models.GoogleAccount
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GoogleSignInManager(private val context: Context) {
    private val credentialManager = CredentialManager.Companion.create(context)

    private val _signedInAccounts = MutableStateFlow<List<GoogleAccount>>(emptyList())
    val signedInAccounts: StateFlow<List<GoogleAccount>> = _signedInAccounts.asStateFlow()

    private val _currentAccount = MutableStateFlow<GoogleAccount?>(null)
    val currentAccount: StateFlow<GoogleAccount?> = _currentAccount.asStateFlow()

    // Your actual Web Client ID
    private val webClientId = "295250683661-larc5033eommnjaf5dgohi7d7aldf07u.apps.googleusercontent.com"

    companion object {
        private const val TAG = "GoogleSignInManager"
    }

    suspend fun signIn(): Result<GoogleAccount> {
        return try {
            Log.d(TAG, "Starting sign in process with client ID: $webClientId")

            // Step 1: Try to get previously authorized accounts first
            val signInResult = attemptSignInWithAuthorizedAccounts()
            if (signInResult.isSuccess) {
                return signInResult
            }

            // Step 2: If no authorized accounts, try sign up with all accounts
            Log.d(TAG, "No authorized accounts found, attempting sign up with all accounts...")
            return attemptSignUpWithAllAccounts()

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in sign in flow", e)
            Result.failure(Exception("Sign in failed: ${e.message}"))
        }
    }

    private suspend fun attemptSignInWithAuthorizedAccounts(): Result<GoogleAccount> {
        return try {
            Log.d(TAG, "Attempting sign in with authorized accounts...")

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true) // Only previously used accounts
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true) // Enable auto-select for returning users
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(
                request = request,
                context = context
            )

            handleSignInResult(result)
        } catch (e: NoCredentialException) {
            Log.d(TAG, "No authorized accounts available")
            Result.failure(e)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Error getting authorized credentials: ${e.type} - ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun attemptSignUpWithAllAccounts(): Result<GoogleAccount> {
        return try {
            Log.d(TAG, "Attempting sign up with all available accounts...")

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false) // Show all Google accounts
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false) // Let user choose account
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(
                request = request,
                context = context
            )

            handleSignInResult(result)
        } catch (e: NoCredentialException) {
            Log.e(TAG, "No Google accounts available on device", e)
            Result.failure(Exception("No Google accounts found. Please add a Google account to your device in Settings > Accounts."))
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Error getting credentials: ${e.type} - ${e.message}", e)
            Result.failure(Exception("Sign in failed: ${e.message}"))
        }
    }

    private fun handleSignInResult(result: GetCredentialResponse): Result<GoogleAccount> {
        return try {
            Log.d(TAG, "Processing sign in result...")
            val credential = GoogleIdTokenCredential.Companion.createFrom(result.credential.data)

            val account = GoogleAccount(
                id = credential.id,
                email = credential.id,
                displayName = credential.displayName,
                photoUrl = credential.profilePictureUri?.toString(),
                idToken = credential.idToken
            )

            Log.d(TAG, "Sign in successful for: ${account.email}")

            // Add to signed-in accounts if not already present
            val currentAccounts = _signedInAccounts.value.toMutableList()
            if (!currentAccounts.any { it.id == account.id }) {
                currentAccounts.add(account)
                _signedInAccounts.value = currentAccounts
            }

            // Set as current account
            _currentAccount.value = account

            Result.success(account)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sign in result", e)
            Result.failure(Exception("Failed to process sign in: ${e.message}"))
        }
    }

    fun selectAccount(account: GoogleAccount) {
        Log.d(TAG, "Selecting account: ${account.email}")
        _currentAccount.value = account
    }

    fun signOut() {
        Log.d(TAG, "Signing out current account")
        _currentAccount.value = null
    }

    fun removeAccount(account: GoogleAccount) {
        Log.d(TAG, "Removing account: ${account.email}")
        val currentAccounts = _signedInAccounts.value.toMutableList()
        currentAccounts.removeAll { it.id == account.id }
        _signedInAccounts.value = currentAccounts

        // If removed account was current, clear current account
        if (_currentAccount.value?.id == account.id) {
            _currentAccount.value = null
        }
    }

    fun isConfigured(): Boolean {
        return webClientId.isNotEmpty() && webClientId.contains("apps.googleusercontent.com")
    }
}