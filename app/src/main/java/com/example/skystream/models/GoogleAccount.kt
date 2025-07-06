package com.example.skystream.models

data class GoogleAccount(
    val id: String,
    val email: String,
    val displayName: String?,
    val photoUrl: String?,
    val idToken: String? = null
)
