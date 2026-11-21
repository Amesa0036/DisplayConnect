package com.example.displayconnect.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.displayconnect.navigation.RawCaptureNotice

/** The same notice is used on both start screens and the private record card. */
@Composable
fun RawCaptureStatus(notice: RawCaptureNotice) {
    if(notice.text.isNotBlank()) Text(notice.text,style=MaterialTheme.typography.bodySmall)
}
