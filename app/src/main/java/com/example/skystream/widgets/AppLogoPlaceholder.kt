package com.example.skystream.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.skystream.R

@Composable
fun AppLogoPlaceholder() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(13.dp)) // Apply clip FIRST
            .background(
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.sky_stream_logo),
            contentDescription = "Sky Stream Logo",
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp)), // Also clip the image itself
            contentScale = ContentScale.Crop // Use Crop instead of Fit
        )
    }
}
