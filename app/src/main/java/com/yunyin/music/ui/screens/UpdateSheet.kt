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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.data.update.AvailableUpdate
import com.yunyin.music.data.update.UpdateUiState
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * "A new version is available" — and the state of installing it.
 *
 * Non-dismissible while a download runs, because there is no way to cancel one and a sheet that swallowed
 * the user's tap without explanation would read as a hang.
 *
 * The buttons reflect what is actually possible: when the release carries an APK, the primary action
 * downloads and installs it; when it does not, the primary action opens the release page instead. That
 * choice is made here rather than hidden, because "更新" that silently does nothing is the failure mode
 * this avoids.
 */
@Composable
fun UpdateSheet(
    state: UpdateUiState,
    onInstall: (AvailableUpdate) -> Unit,
    onOpenPage: (AvailableUpdate) -> Unit,
    onSkip: (AvailableUpdate) -> Unit,
    onDismiss: () -> Unit,
) {
    // Which states actually present the sheet:
    //  - Available      — a fresh find, offered.
    //  - Downloading    — progress to show, and no way to cancel.
    //  - Failed         — the user acted, so the outcome (and the fallback) must be visible.
    // Idle and AvailableSkipped render nothing. In particular a *skipped* version must not keep the sheet
    // up: that is what "跳过" means, and a sheet that stayed open made the tap look broken. The row in
    // Settings still shows "有新版本", so the offer stays reachable deliberately.
    val update = when (state) {
        is UpdateUiState.Available -> state.update
        is UpdateUiState.Downloading -> state.update
        is UpdateUiState.Failed -> state.update
        else -> null
    } ?: return

    // Held as a nullable value rather than re-tested with a cast further down: the compiler cannot carry
    // the `is` check across the composable's branches, and an `as` there is a warning plus a crash risk if
    // the branch is ever reordered.
    val downloadProgress = (state as? UpdateUiState.Downloading)?.progress
    val downloading = downloadProgress != null

    Dialog(
        // While downloading there is nothing to dismiss to: the work is already in flight and the app
        // shows progress inside the sheet, so the scrim must not act as a cancel button.
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(indication = null, interactionSource = null) { if (!downloading) onDismiss() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                    .background(AppTheme.palette.background)
                    .clickable(enabled = false, indication = null, interactionSource = null) { }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "发现新版本",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = AppTheme.palette.label,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "当前 ${com.yunyin.music.BuildConfig.VERSION_NAME} → ${update.versionName}",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )

                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    // Bounded, because release notes can be arbitrarily long and a sheet that grows past
                    // the screen would push its own buttons off the bottom.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(ContinuousRoundedRectangle(12.dp))
                            .background(AppTheme.palette.secondaryBackground)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            text = update.notes,
                            fontFamily = SFPro,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = AppTheme.palette.label,
                        )
                    }
                }

                if (downloadProgress != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (downloadProgress in 0..100) "正在下载… $downloadProgress%" else "正在下载…",
                        fontFamily = SFPro,
                        fontSize = 13.sp,
                        color = AppTheme.palette.secondaryLabel,
                    )
                    Spacer(Modifier.height(8.dp))
                    // An indeterminate bar when the server sent no content length, rather than a bar
                    // frozen at 0% that looks like a stall.
                    DownloadBar(downloadProgress)
                }

                if (state is UpdateUiState.Failed) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = state.message,
                        fontFamily = SFPro,
                        fontSize = 13.sp,
                        color = Color(0xFFE5484D),
                    )
                }

                Spacer(Modifier.height(18.dp))
                val apkAvailable = update.apk != null
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        UpdateButton(
                            label = if (downloading) "下载中…" else if (apkAvailable) "下载并安装" else "打开下载页",
                            filled = true,
                            enabled = !downloading,
                            onClick = {
                                if (apkAvailable) onInstall(update) else onOpenPage(update)
                            },
                        )
                    }
                    // A secondary route is always offered: the in-app install can fail for reasons that
                    // have nothing to do with the download (the ROM refused the installer, unknown sources
                    // are off), and the release page always works.
                    if (apkAvailable) {
                        Box(Modifier.weight(1f)) {
                            UpdateButton(
                                label = "浏览器打开",
                                filled = false,
                                enabled = !downloading,
                                onClick = { onOpenPage(update) },
                            )
                        }
                    }
                }

                if (!downloading) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "跳过这个版本",
                        fontFamily = SFPro,
                        fontSize = 14.sp,
                        color = AppTheme.palette.secondaryLabel,
                        modifier = Modifier
                            // Fills the row so the target is the whole width, not just the four glyphs.
                            .fillMaxWidth()
                            .clip(ContinuousRoundedRectangle(10.dp))
                            .clickable { onSkip(update) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** A thin progress bar; determinate when [progress] is a percentage, animated-looking otherwise. */
@Composable
private fun DownloadBar(progress: Int) {
    val fraction = if (progress in 0..100) progress / 100f else 0.35f
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                .background(AppTheme.palette.accent),
        )
    }
}

@Composable
private fun UpdateButton(
    label: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = AppTheme.palette
    val base = if (filled) palette.accent else palette.secondaryBackground
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(if (enabled) base else base.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = if (filled) Color.White else palette.label,
        )
    }
}
