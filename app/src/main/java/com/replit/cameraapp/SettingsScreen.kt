package com.replit.cameraapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Samsung One UI-style accent blue used for active toggles and highlights throughout the app. */
val SamsungBlue = Color(0xFF1A73FF)

private val RowBackground = Color(0xFF1C1C1E)
private val SecondaryText = Color(0xFF9A9A9E)

/**
 * The in-app settings page opened from the camera screen's gear icon. Styled after Samsung's
 * One UI settings: pure black background, rounded dark-gray list rows, and a blue accent on
 * anything that's toggled on.
 */
@Composable
fun SettingsScreen(
    scanDocumentsEnabled: Boolean,
    onScanDocumentsChanged: (Boolean) -> Unit,
    watermarkEnabled: Boolean,
    onWatermarkChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Text(
            text = "CAMERA",
            color = SecondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        SettingsToggleRow(
            icon = Icons.Filled.Description,
            title = "Scan documents",
            subtitle = "Recognize documents and papers, and straighten them automatically when captured",
            checked = scanDocumentsEnabled,
            onCheckedChange = onScanDocumentsChanged
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingsToggleRow(
            icon = Icons.Filled.BrandingWatermark,
            title = "Watermark",
            subtitle = "Stamp the app name and date onto every photo you capture",
            checked = watermarkEnabled,
            onCheckedChange = onWatermarkChanged
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RowBackground)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, color = SecondaryText, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SamsungBlue,
                checkedBorderColor = SamsungBlue,
                uncheckedThumbColor = Color(0xFFBDBDBD),
                uncheckedTrackColor = Color(0xFF3A3A3C),
                uncheckedBorderColor = Color(0xFF3A3A3C)
            )
        )
    }
}
