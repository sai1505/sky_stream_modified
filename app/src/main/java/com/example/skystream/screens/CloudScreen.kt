package com.example.skystream.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.skystream.auth.GoogleDriveAuthManager
import com.example.skystream.auth.GoogleUserInfo
import com.example.skystream.services.DriveVideoFile
import com.example.skystream.services.GoogleDriveService
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CloudScreen(
    onVideoSelected: (DriveVideoFile, List<DriveVideoFile>, GoogleDriveAuthManager) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Initialize auth manager
    val authManager = remember { GoogleDriveAuthManager(context) }
    val authService = remember { AuthorizationService(context) }

    // Collect auth state
    val isAuthenticated by authManager.isAuthenticated.collectAsState()
    val currentAccount by authManager.currentAccount.collectAsState()
    val signedInAccounts by authManager.signedInAccounts.collectAsState()

    // UI state
    var showAccountSelector by remember { mutableStateOf(false) }
    var isSigningIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Video loading state
    var videoFiles by remember { mutableStateOf<List<DriveVideoFile>>(emptyList()) }
    var isLoadingVideos by remember { mutableStateOf(false) }

    // Google Drive service
    val driveService = remember(currentAccount) {
        if (isAuthenticated && currentAccount != null) {
            authManager.getAccessToken()?.let { token ->
                GoogleDriveService(context, token)
            }
        } else null
    }

// Update the video loading LaunchedEffect in CloudScreen.kt
    LaunchedEffect(currentAccount, driveService) {
        if (isAuthenticated && driveService != null) {
            isLoadingVideos = true

            // Check and refresh token before making API calls
            val tokenRefreshed = authManager.refreshTokenIfNeeded()
            if (!tokenRefreshed) {
                errorMessage = "Authentication expired. Please sign in again."
                isLoadingVideos = false
                authManager.signOut()
                return@LaunchedEffect
            }

            driveService.getVideoFiles()
                .onSuccess { (files, _) ->
                    videoFiles = files
                    isLoadingVideos = false
                }
                .onFailure { error ->
                    if (error.message?.contains("401") == true || error.message?.contains("Unauthorized") == true) {
                        // Handle authentication error
                        errorMessage = "Authentication expired. Please sign in again."
                        authManager.signOut()
                    } else {
                        errorMessage = error.message
                    }
                    isLoadingVideos = false
                }
        } else {
            videoFiles = emptyList()
        }
    }


    // Authorization launcher
    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            val response = net.openid.appauth.AuthorizationResponse.fromIntent(data)
            val exception = net.openid.appauth.AuthorizationException.fromIntent(data)

            if (response != null) {
                coroutineScope.launch {
                    isSigningIn = true
                    authManager.handleAuthorizationResponse(authService, response)
                        .onSuccess {
                            isSigningIn = false
                            errorMessage = null
                        }
                        .onFailure { error ->
                            isSigningIn = false
                            errorMessage = error.message
                        }
                }
            } else if (exception != null) {
                errorMessage = "Authorization failed: ${exception.message}"
                isSigningIn = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        when {
            !isAuthenticated && signedInAccounts.isEmpty() -> {
                // No accounts - show sign in
                SignInContent(
                    isSigningIn = isSigningIn,
                    errorMessage = errorMessage,
                    onSignInClick = {
                        val authRequest = authManager.createAuthorizationRequest()
                        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
                        authLauncher.launch(authIntent)
                    }
                )
            }

            !isAuthenticated && signedInAccounts.isNotEmpty() -> {
                // Has stored accounts but none selected
                AccountSelectionScreen(
                    accounts = signedInAccounts,
                    onAccountSelected = { accountEmail ->
                        authManager.switchToAccount(accountEmail)
                    },
                    onAddAccount = {
                        val authRequest = authManager.createAuthorizationRequest()
                        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
                        authLauncher.launch(authIntent)
                    },
                    onRemoveAccount = { accountEmail ->
                        authManager.removeAccount(accountEmail)
                    },
                    isSigningIn = isSigningIn,
                    errorMessage = errorMessage
                )
            }

            else -> {
                // Authenticated - show content with account switcher
                AuthenticatedContent(
                    currentAccount = currentAccount,
                    signedInAccounts = signedInAccounts,
                    videoFiles = videoFiles,
                    isLoadingVideos = isLoadingVideos,
                    showAccountSelector = showAccountSelector,
                    errorMessage = errorMessage,
                    onToggleAccountSelector = { showAccountSelector = !showAccountSelector },
                    onAccountSelected = { accountEmail ->
                        authManager.switchToAccount(accountEmail)
                        showAccountSelector = false
                    },
                    onAddAccount = {
                        val authRequest = authManager.createAuthorizationRequest()
                        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
                        authLauncher.launch(authIntent)
                    },
                    onRemoveAccount = { accountEmail ->
                        authManager.removeAccount(accountEmail)
                    },
                    onSignOut = {
                        authManager.signOut()
                        showAccountSelector = false
                    },
                    onSignOutAll = {
                        authManager.signOutAll()
                        showAccountSelector = false
                    },
                    onVideoSelected = { videoFile ->
                        // ✅ Fixed: Pass all three required parameters
                        onVideoSelected(videoFile, videoFiles, authManager)
                    },
                    isSigningIn = isSigningIn
                )
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            authService.dispose()
        }
    }
}

@Composable
fun AccountSelectionScreen(
    accounts: Map<String, GoogleUserInfo>,
    onAccountSelected: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    isSigningIn: Boolean,
    errorMessage: String?
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Choose Account",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(accounts.entries.toList()) { (email, userInfo) ->
                AccountCard(
                    userInfo = userInfo,
                    onClick = { onAccountSelected(email) },
                    onRemove = { onRemoveAccount(email) }
                )
            }

            item {
                AddAccountCard(
                    onClick = onAddAccount,
                    isLoading = isSigningIn
                )
            }
        }

        errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            ErrorCard(error = error)
        }
    }
}

@Composable
fun AuthenticatedContent(
    currentAccount: GoogleUserInfo?,
    signedInAccounts: Map<String, GoogleUserInfo>,
    videoFiles: List<DriveVideoFile>,
    isLoadingVideos: Boolean,
    showAccountSelector: Boolean,
    errorMessage: String?,
    onToggleAccountSelector: () -> Unit,
    onAccountSelected: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onSignOut: () -> Unit,
    onSignOutAll: () -> Unit,
    onVideoSelected: (DriveVideoFile) -> Unit, // ✅ Fixed: Changed to single parameter
    isSigningIn: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Account header with dropdown
        AccountSwitcherHeader(
            currentAccount = currentAccount,
            showDropdown = showAccountSelector,
            onToggleDropdown = onToggleAccountSelector,
            accountCount = signedInAccounts.size
        )

        // Account selector dropdown
        AnimatedVisibility(
            visible = showAccountSelector,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            AccountSelectorDropdown(
                accounts = signedInAccounts,
                currentAccount = currentAccount,
                onAccountSelected = onAccountSelected,
                onAddAccount = onAddAccount,
                onRemoveAccount = onRemoveAccount,
                onSignOut = onSignOut,
                onSignOutAll = onSignOutAll,
                isSigningIn = isSigningIn
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Video content
        when {
            isLoadingVideos -> LoadingVideoContent()
            videoFiles.isEmpty() -> EmptyVideoContent(false)
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(videoFiles, key = { it.id }) { videoFile ->
                        VideoFileCard(
                            videoFile = videoFile,
                            onClick = { onVideoSelected(videoFile) } // ✅ Fixed: Single parameter call
                        )
                    }
                }
            }
        }

        errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            ErrorCard(error = error)
        }
    }
}

@Composable
fun AccountSwitcherHeader(
    currentAccount: GoogleUserInfo?,
    showDropdown: Boolean,
    onToggleDropdown: () -> Unit,
    accountCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleDropdown() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile picture
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (currentAccount?.picture != null) {
                    AsyncImage(
                        model = currentAccount.picture,
                        contentDescription = "Profile picture",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Default profile",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Account info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentAccount?.name ?: "Unknown User",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${accountCount} account${if (accountCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Dropdown arrow
            Icon(
                imageVector = if (showDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = if (showDropdown) "Hide accounts" else "Show accounts",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun VideoFileCard(
    videoFile: DriveVideoFile,
    onClick: () -> Unit
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
            // Thumbnail or video icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (videoFile.thumbnailLink != null) {
                    AsyncImage(
                        model = videoFile.thumbnailLink,
                        contentDescription = "Video thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.VideoFile,
                        contentDescription = "Video file",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
                    text = videoFile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // File size
                    videoFile.size?.let { size ->
                        Text(
                            text = formatFileSize(size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Modified date
                    videoFile.modifiedTime?.let { time ->
                        Text(
                            text = formatDate(time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // MIME type
                Text(
                    text = videoFile.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun LoadingVideoContent() {
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
                text = "Loading videos from Google Drive...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EmptyVideoContent(isSearching: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSearching) Icons.Default.SearchOff else Icons.Default.VideoLibrary,
            contentDescription = if (isSearching) "No search results" else "No videos",
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isSearching) "No videos found" else "No videos in your Google Drive",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isSearching) "Try a different search term" else "Upload some videos to your Google Drive to see them here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ErrorCard(error: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun SignInContent(
    isSigningIn: Boolean,
    errorMessage: String?,
    onSignInClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Cloud icon
        Icon(
            imageVector = Icons.Default.CloudQueue,
            contentDescription = "Google Drive",
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connect to Google Drive",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Sign in to access your Google Drive videos and stream them directly",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Sign in button
        Button(
            onClick = onSignInClick,
            enabled = !isSigningIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (isSigningIn) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Signing in...",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Sign in",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Sign in with Google",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Error message
        errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun AccountCard(
    userInfo: GoogleUserInfo,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile picture
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (userInfo.picture != null) {
                    AsyncImage(
                        model = userInfo.picture,
                        contentDescription = "Profile picture",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Default profile",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Account info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userInfo.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = userInfo.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Remove button
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove account",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun AddAccountCard(
    onClick: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading) { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Adding account...",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add account",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Add another account",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
fun AccountSelectorDropdown(
    accounts: Map<String, GoogleUserInfo>,
    currentAccount: GoogleUserInfo?,
    onAccountSelected: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onSignOut: () -> Unit,
    onSignOutAll: () -> Unit,
    isSigningIn: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        LazyColumn(
            modifier = Modifier.padding(8.dp)
        ) {
            // Account list
            items(accounts.entries.toList()) { (email, userInfo) ->
                AccountDropdownItem(
                    userInfo = userInfo,
                    isSelected = email == currentAccount?.email,
                    onAccountClick = { onAccountSelected(email) },
                    onRemoveClick = { onRemoveAccount(email) }
                )
            }

            // Add account button
            item {
                AddAccountDropdownItem(
                    onClick = onAddAccount,
                    isLoading = isSigningIn
                )
            }

            // Divider
            if (accounts.isNotEmpty()) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Sign out current account
            item {
                SignOutDropdownItem(
                    text = "Sign out current account",
                    onClick = onSignOut
                )
            }

            // Sign out all accounts
            if (accounts.size > 1) {
                item {
                    SignOutDropdownItem(
                        text = "Sign out all accounts",
                        onClick = onSignOutAll
                    )
                }
            }
        }
    }
}

@Composable
fun AccountDropdownItem(
    userInfo: GoogleUserInfo,
    isSelected: Boolean,
    onAccountClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAccountClick() }
            .padding(12.dp)
            .then(
                if (isSelected) {
                    Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Profile picture
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (userInfo.picture != null) {
                AsyncImage(
                    model = userInfo.picture,
                    contentDescription = "Profile picture",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Default profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Account info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = userInfo.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = userInfo.email,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Remove button
        IconButton(
            onClick = onRemoveClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove account",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun AddAccountDropdownItem(
    onClick: () -> Unit,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading) { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add account",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = if (isLoading) "Adding account..." else "Add another account",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun SignOutDropdownItem(
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.errorContainer,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ExitToApp,
                contentDescription = "Sign out",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

// Helper functions
private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${String.format("%.1f", size / 1024.0)} KB"
        size < 1024 * 1024 * 1024 -> "${String.format("%.1f", size / (1024.0 * 1024.0))} MB"
        else -> "${String.format("%.1f", size / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}

private fun formatDate(dateString: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val date = inputFormat.parse(dateString)
        outputFormat.format(date ?: Date())
    } catch (e: Exception) {
        "Unknown date"
    }
}
