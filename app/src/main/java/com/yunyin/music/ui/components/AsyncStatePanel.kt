// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yunyin.music.ui.theme.AppTheme

/** Shared full-page loading/error/empty state with a real 48dp retry target. */
@Composable
fun AsyncStatePanel(
    modifier: Modifier = Modifier,
    message: String? = null,
    loading: Boolean = false,
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
) {
    val status = when {
        loading -> "正在加载"
        !message.isNullOrBlank() -> message
        else -> "暂无内容"
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = status
            }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        if (loading) {
            CircularProgressIndicator(color = AppTheme.palette.accent)
        } else {
            Text(
                text = status,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = AppTheme.palette.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Text(
                    text = retryLabel,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = AppTheme.palette.accent,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClick = onRetry)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}
