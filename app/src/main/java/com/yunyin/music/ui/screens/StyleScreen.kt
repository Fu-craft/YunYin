// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.NetResult
import com.yunyin.music.data.net.NeteaseClient
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * 曲风浏览: the two-level tag tree from `/style/list`.
 *
 * Deliberately not routed through the view model: the page owns exactly one request, has no state
 * that outlives it, and returns either "a tag was picked" or "go back". A screen-local `LaunchedEffect`
 * keeps it honest about that, and the results open in the existing collection screen
 * ([AppViewModel.openStyleSongs]).
 */
@Composable
fun StyleScreen(
    loadTags: suspend () -> NetResult<List<NeteaseClient.StyleTag>>,
    onPick: (tagId: Int, name: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tags by remember { mutableStateOf<List<NeteaseClient.StyleTag>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val result = runCatching { loadTags() }.getOrNull()) {
            is NetResult.Ok -> tags = result.value
            is NetResult.Err -> error = "曲风加载失败，请重试"
            null -> error = "曲风加载失败，请重试"
        }
        loading = false
    }

    Column(
        modifier
            .fillMaxSize()
            .background(AppTheme.palette.background)
            .padding(top = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "曲风",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = AppTheme.palette.label,
            )
            Spacer(Modifier.height(0.dp))
            Box(Modifier.fillMaxWidth()) {
                Text(
                    text = "返回",
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable(onClick = onBack)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    fontFamily = SFPro,
                    fontSize = 15.sp,
                    color = AppTheme.palette.accent,
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppTheme.palette.accent)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error.orEmpty(), fontFamily = SFPro, color = AppTheme.palette.secondaryLabel)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(tags, key = { "style-${it.id}" }) { tag ->
                    StyleTagGroup(tag) { id, name -> onPick(id, name) }
                }
            }
        }
    }
}

@Composable
private fun StyleTagGroup(
    tag: NeteaseClient.StyleTag,
    onPick: (Int, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = tag.name,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            color = AppTheme.palette.label,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(tag.id, tag.name) }
                .padding(vertical = 6.dp),
        )
        if (tag.children.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tag.children.take(MAX_CHILDREN_PER_ROW).forEach { child ->
                    StyleChip(child.name) { onPick(child.id, child.name) }
                }
            }
            if (tag.children.size > MAX_CHILDREN_PER_ROW) {
                // A single wrapped row is what the reference shows; the rest stay reachable by
                // tapping the parent tag, so nothing is hidden behind a second screen.
                Text(
                    text = "更多…",
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.secondaryLabel,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { onPick(tag.id, tag.name) },
                )
            }
        }
    }
}

@Composable
private fun StyleChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(AppTheme.palette.secondaryBackground, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.label,
        )
    }
}

private const val MAX_CHILDREN_PER_ROW = 4
