// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.yunyin.music.core.ArtistInfo
import com.yunyin.music.core.NetResult
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * 相似歌手 — `/simi/artist` for the playing track.
 *
 * A plain list rather than cards with artwork: the endpoint returns a cover per artist, but wiring
 * the artwork loader into another overlay would mean one more parameter through the whole library
 * page, and the names are what the choice is actually made on. Tapping a row opens that artist's page,
 * which is where the covers live anyway.
 */
@Composable
fun SimilarArtistsScreen(
    loadArtists: suspend () -> NetResult<List<ArtistInfo>>,
    onPick: (artistId: Long, name: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var artists by remember { mutableStateOf<List<ArtistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val result = runCatching { loadArtists() }.getOrNull()) {
            is NetResult.Ok -> artists = result.value
            is NetResult.Err -> error = result.message
            null -> error = "相似歌手加载失败"
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
                text = "相似歌手",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = AppTheme.palette.label,
            )
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
            artists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有相似歌手", fontFamily = SFPro, color = AppTheme.palette.secondaryLabel)
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(artists, key = { it.id }) { artist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(artist.id, artist.name) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = artist.name,
                            fontFamily = SFPro,
                            fontSize = 16.sp,
                            color = AppTheme.palette.label,
                        )
                    }
                }
            }
        }
    }
}
