// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yunyin.music.data.net.PagingTriggerRules

/**
 * Runs [onLoadMore] when a paginated list scrolls near its end.
 *
 * The trigger is read in a [derivedStateOf] over the list's layout info, and the effect only re-runs
 * when the boolean flips, so a fling through the last rows queues at most one request. The guards
 * themselves live in [PagingTriggerRules] and are shared by every screen.
 *
 * `totalItemCount` includes non-data items (headers, footers) on purpose: that is the list's real end,
 * and the last visible item's index is measured against it. A list that fits on screen reports its
 * final item as visible, which triggers the "viewport not filled" load.
 */
@Composable
fun PagingAutoLoader(
    lastVisibleItemIndex: () -> Int,
    totalItemCount: () -> Int,
    hasMore: Boolean,
    loadingMore: Boolean,
    appendError: Boolean,
    hasItems: Boolean,
    onLoadMore: () -> Unit,
) {
    // Rebuild only when a guard changes; the index/count lambdas read the list state themselves.
    val shouldLoad by remember(hasMore, loadingMore, appendError, hasItems) {
        derivedStateOf {
            PagingTriggerRules.shouldLoad(
                lastVisibleIndex = lastVisibleItemIndex(),
                totalItemCount = totalItemCount(),
                hasMore = hasMore,
                loadingMore = loadingMore,
                appendError = appendError,
                hasItems = hasItems,
            )
        }
    }
    // A page that returns no new items while still reporting more must not loop: only enqueue when
    // the list length has actually changed since the previous request.
    var lastTriggeredItemCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(shouldLoad) {
        if (shouldLoad) {
            val count = totalItemCount()
            if (count != lastTriggeredItemCount) {
                lastTriggeredItemCount = count
                onLoadMore()
            }
        }
    }
}

/**
 * Footer-sentinel variant for a section inside a larger LazyColumn (the artist page's lists):
 * "near the end of that section" is equivalent to its paging footer becoming visible.
 */
@Composable
fun PagingAutoLoaderAtFooter(
    listState: LazyListState,
    footerKey: Any,
    hasMore: Boolean,
    loadingMore: Boolean,
    appendError: Boolean,
    hasItems: Boolean,
    onLoadMore: () -> Unit,
) {
    val footerVisible by remember(listState, footerKey) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == footerKey } }
    }
    val shouldLoad by remember(footerVisible, hasMore, loadingMore, appendError, hasItems) {
        derivedStateOf {
            PagingTriggerRules.shouldLoadAtFooter(
                footerVisible = footerVisible,
                hasMore = hasMore,
                loadingMore = loadingMore,
                appendError = appendError,
                hasItems = hasItems,
            )
        }
    }
    var lastTriggeredItemCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(shouldLoad) {
        if (shouldLoad) {
            val count = listState.layoutInfo.totalItemsCount
            if (count != lastTriggeredItemCount) {
                lastTriggeredItemCount = count
                onLoadMore()
            }
        }
    }
}
