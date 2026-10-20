package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.components.SheetPlacement
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.SFPro

/**
 * 从链接导入：paste a URL, download, and run it through the same import pipeline as a local file.
 *
 * ## Why this file is deliberately conservative
 *
 * The device build crashed on the *first device test* of the previous version of this sheet. That version was the
 * only sheet in the app that constructed a `FocusRequester` and called `requestFocus()` from a `LaunchedEffect`
 * while the sheet was still animating in — a focus request against a node that is not attached yet. Every existing
 * sheet (`ProfileEditSheet`, `QueueSheet`, `PlayerStyleSheet`, `UpdateSheet`) opens without any focus request, and
 * the other full-width sheets all pass the same explicit `DialogProperties` used here. So this version uses exactly
 * that known-good shape: no focus request, no IME action that can fire an import, and the same
 * `usePlatformDefaultWidth = false` / `decorFitsSystemWindows = false` properties as the other sheets. The field is
 * still the first touch target and the system paste menu works; autofocus can only come back after a device confirms
 * the sheet opens reliably.
 *
 * Dismissal also follows the known-good pattern exactly: buttons call `close()` and the host removes the sheet from
 * `onDismissed` — never both immediately, which would dispose a Dialog while its exit animation is running.
 *
 * The dialog does not describe the validation rules: those are enforced by the host and reported in specific
 * language in `message` (and in the error log).
 */
@Composable
fun LxSourceUrlSheet(
    url: String,
    importing: Boolean,
    message: String?,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        // `onDismissed` is the only place the host removes the sheet; the buttons below only call close().
        onDismissed = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Center,
        scrim = false,
        // Back press / scrim dismissal is off while an import is in flight: the URL must not be lost mid-fetch.
        dismissible = !importing,
        sheetModifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) { close ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "从链接导入",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = AppTheme.palette.label,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(40.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .clickable(
                        enabled = !importing,
                        indication = rememberControlRipple(bounded = false),
                        interactionSource = null,
                    ) { if (!importing) close() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Xmark,
                    contentDescription = "关闭",
                    tint = AppTheme.palette.secondaryLabel,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "粘贴自定义音源脚本的 http(s) 地址，下载后会像本地文件一样校验。来源会被保存，可随时点「更新」重新拉取。",
            fontFamily = SFPro,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = AppTheme.palette.secondaryLabel,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "GitHub 原始地址打不开时会自动尝试 jsDelivr 镜像；也可以直接粘贴镜像地址，例如 https://cdn.jsdelivr.net/gh/…",
            fontFamily = SFPro,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = AppTheme.palette.tertiaryLabel,
        )

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedRectangle(AppleShapes.control))
                .background(AppTheme.palette.secondaryBackground)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (url.isEmpty()) {
                    Text(
                        text = "https://…/source.js",
                        fontFamily = SFPro,
                        fontSize = 15.sp,
                        color = AppTheme.palette.tertiaryLabel,
                    )
                }
                BasicTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    singleLine = true,
                    enabled = !importing,
                    textStyle = TextStyle(
                        fontFamily = SFPro,
                        fontSize = 15.sp,
                        color = AppTheme.palette.label,
                    ),
                    cursorBrush = SolidColor(AppTheme.palette.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (url.isNotEmpty() && !importing) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .clickable { onUrlChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        SfIcons.Xmark,
                        contentDescription = "清空",
                        tint = AppTheme.palette.tertiaryLabel,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                fontFamily = SFPro,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "取消",
                fontFamily = SFPro,
                fontSize = 15.sp,
                color = AppTheme.palette.secondaryLabel,
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .clickable(enabled = !importing) { if (!importing) close() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .background(AppTheme.palette.accent.copy(alpha = if (importing) 0.5f else 1f))
                    .clickable(enabled = !importing) {
                        // The host runs the import in its own guarded coroutine; a throw here would be an
                        // exception escaping a click handler, so the call itself is bounded too.
                        if (!importing) runCatching { onImport() }
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        color = AppTheme.palette.background,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        text = "导入",
                        fontFamily = SFPro,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = AppTheme.palette.background,
                    )
                }
            }
        }
    }
}
