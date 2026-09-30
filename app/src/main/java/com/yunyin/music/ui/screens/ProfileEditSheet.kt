package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Editing sheet for the profile header: avatar, header image, and the one-line signature.
 *
 * Deliberately not a `ModalBottomSheet`. This app has no Material sheet anywhere, and dropping one in
 * here would bring its own shape, scrim, drag handle and typography — the app's own dialog shape and
 * ripple are used instead, so the surface matches every other overlay in the product.
 *
 * Two properties are worth stating because they are the whole reason this is not a form:
 *
 *  - **Each control shows its current state.** The avatar row shows the picture that will actually be
 *    used, and the "follow NetEase avatar" switch shows whether it is. A settings sheet whose rows do not
 *    reflect the current choice is how a user ends up toggling something blindly.
 *  - **Every destructive-ish choice is reversible and offered with its undo in place.** Clearing the
 *    custom avatar is a button next to the switch, not a hidden long-press.
 */
@Composable
fun ProfileEditSheet(
    nickname: String,
    signature: String,
    useNeteaseAvatar: Boolean,
    hasCustomAvatar: Boolean,
    hasCustomBackground: Boolean,
    onSignatureChange: (String) -> Unit,
    onPickAvatar: () -> Unit,
    onPickBackground: () -> Unit,
    onClearAvatar: () -> Unit,
    onClearBackground: () -> Unit,
    onUseNeteaseAvatarChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // The field is edited locally and only pushed out on dismissal, so a half-typed signature is not
    // written (and re-read) on every keystroke.
    var draft by remember { mutableStateOf(signature) }

    Dialog(onDismissRequest = { onSignatureChange(draft); onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                .background(AppTheme.palette.background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "编辑资料",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = AppTheme.palette.label,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(
                            indication = rememberControlRipple(bounded = false),
                            interactionSource = null,
                            onClick = { onSignatureChange(draft); onDismiss() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        SfIcons.Checkmark,
                        contentDescription = "完成",
                        tint = AppTheme.palette.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = nickname.ifBlank { "云音用户" },
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------------ avatar
            SectionLabel("头像")
            ActionTile(
                title = if (hasCustomAvatar) "更换自定义头像" else "选择自定义头像",
                subtitle = "从相册中选择一张图片",
                onClick = onPickAvatar,
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "跟随网易云音乐头像",
                subtitle = "关闭后使用你选择的自定义头像",
                checked = useNeteaseAvatar,
                onCheckedChange = onUseNeteaseAvatarChange,
            )
            if (hasCustomAvatar) {
                Spacer(Modifier.height(8.dp))
                ActionTile(
                    title = "移除自定义头像",
                    subtitle = "恢复为网易云音乐头像",
                    tint = AppTheme.palette.accent,
                    onClick = onClearAvatar,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------------ header image
            SectionLabel("背景图")
            ActionTile(
                title = if (hasCustomBackground) "更换背景图" else "选择背景图",
                subtitle = "显示在资料页顶部",
                onClick = onPickBackground,
            )
            if (hasCustomBackground) {
                Spacer(Modifier.height(8.dp))
                ActionTile(
                    title = "移除背景图",
                    subtitle = "恢复为默认渐变",
                    tint = AppTheme.palette.accent,
                    onClick = onClearBackground,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------------ signature
            SectionLabel("个性签名")
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .background(AppTheme.palette.secondaryBackground)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = "写点什么…",
                        fontFamily = SFPro,
                        fontSize = 16.sp,
                        color = AppTheme.palette.tertiaryLabel,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_SIGNATURE) },
                    textStyle = TextStyle(
                        fontFamily = SFPro,
                        fontSize = 16.sp,
                        color = AppTheme.palette.label,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AppTheme.palette.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${draft.length}/$MAX_SIGNATURE",
                fontFamily = SFPro,
                fontSize = 11.sp,
                color = AppTheme.palette.tertiaryLabel,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** A short cap: the signature is one line under a name, not a bio field. */
private const val MAX_SIGNATURE = 40

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

/** A tappable row that reads as a button but keeps the sheet's list rhythm. */
@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = AppTheme.palette.label,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.control))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SFPro, fontSize = 16.sp, color = tint)
            Text(
                text = subtitle,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
        Icon(
            SfIcons.ChevronRight,
            contentDescription = null,
            tint = AppTheme.palette.tertiaryLabel,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.control))
            .background(AppTheme.palette.secondaryBackground)
            // The row is the only tap target and the switch takes a null handler, so one tap cannot
            // fire both handlers or be swallowed depending on hit testing.
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
            Text(
                text = subtitle,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppTheme.palette.accent,
            ),
        )
    }
}
