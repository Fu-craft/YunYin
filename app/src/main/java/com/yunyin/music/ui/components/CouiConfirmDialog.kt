// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionColors
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog as CouiDialog
import com.itos.coui.theme.CouiTheme
import com.yunyin.music.ui.style.CurrentUiStyle
import com.yunyin.music.ui.style.UiStyle
import com.yunyin.music.ui.theme.AppTheme

/**
 * 确认弹窗,两套实现二选一。
 *
 * COUI 的 `Dialog` 不需要背板采样源(签名里只有 colors/shape),所以它可以和本项目的玻璃背景共存——
 * 这是"菜单/搜索栏要迁移背板、对话框不需要"的那一半。
 *
 * 经典版保持原来的 material3 `AlertDialog`:危险操作用强调色文字,主按钮不填充。
 * 凝光版用 COUI 的圆角卡片(45dp 半径)+ 主/次按钮:主按钮填充强调色,次按钮中性。
 */
@Composable
fun YunYinConfirmDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String = "取消",
    /** true 时确认按钮按"危险操作"着色(删除、清空)。 */
    danger: Boolean = true,
) {
    if (CurrentUiStyle.value == UiStyle.Coui) {
        val scheme = CouiTheme.colorScheme
        val accent = if (danger) scheme.accent else scheme.accent
        CouiDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            message = message,
            placement = CouiDialogPlacement.Center,
            confirmAction = CouiDialogAction(
                text = confirmLabel,
                onClick = onConfirm,
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogActionColors(
                    surface = accent,
                    content = scheme.onAccent,
                    disabledSurface = accent.copy(alpha = 0.35f),
                    disabledContent = scheme.onAccent.copy(alpha = 0.6f),
                ),
            ),
            dismissAction = CouiDialogAction(
                text = dismissLabel,
                onClick = onDismissRequest,
            ),
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title, fontFamily = com.yunyin.music.ui.theme.SFPro) },
            text = { Text(message, fontFamily = com.yunyin.music.ui.theme.SFPro) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        confirmLabel,
                        color = if (danger) AppTheme.palette.accent else Color.Unspecified,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(dismissLabel) }
            },
        )
    }
}
