package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Settings.
 *
 * Only user-facing choices appear here: the audio quality they want, plus maintenance actions.
 * Server/network configuration is intentionally absent — the app ships with a working service
 * endpoint, and exposing addresses, proxies or IP overrides asks the user to reason about
 * infrastructure they have no reason to know about.
 */
@Composable
fun SettingsScreen(
    quality: String,
    onQualityChange: (String) -> Unit,
    onClearLyricsCache: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.palette.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = AppTheme.palette.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "设置",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = AppTheme.palette.label,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SectionLabel("音质")
            QualityOption("standard", "标准", quality, onQualityChange)
            QualityOption("higher", "较高", quality, onQualityChange)
            QualityOption("exhigh", "极高", quality, onQualityChange)
            QualityOption("lossless", "无损", quality, onQualityChange)
            QualityOption("hires", "Hi-Res", quality, onQualityChange)

            Spacer(Modifier.height(28.dp))

            SectionLabel("存储")
            ActionRow(text = "清除歌词缓存", onClick = onClearLyricsCache)

            Spacer(Modifier.height(28.dp))

            SectionLabel("关于")
            // Read from BuildConfig rather than a literal: the previous hardcoded "1.0.0" was a
            // second source of truth for the version and drifted from the manifest the moment the
            // version was bumped.
            InfoRow(text = "版本", value = com.yunyin.music.BuildConfig.VERSION_NAME)
            InfoRow(text = "歌词", value = "AMLL 逐字歌词")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.palette.secondaryLabel,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun QualityOption(
    value: String,
    label: String,
    current: String,
    onChange: (String) -> Unit,
) {
    val selected = value == current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(10.dp))
            .clickable { onChange(value) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontFamily = SFPro,
            fontSize = 16.sp,
            color = AppTheme.palette.label,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                SfIcons.Checkmark,
                contentDescription = null,
                tint = AppTheme.palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontFamily = SFPro,
            fontSize = 16.sp,
            color = AppTheme.palette.accent,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InfoRow(text: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontFamily = SFPro,
            fontSize = 16.sp,
            color = AppTheme.palette.label,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontFamily = SFPro,
            fontSize = 15.sp,
            color = AppTheme.palette.secondaryLabel,
        )
    }
}
