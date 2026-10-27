// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Last animated-artwork lookup, surfaced in Settings so "the toggle is on but nothing moves" can be
 * told apart on the device itself: no request at all (build has no endpoint), a miss (the album has
 * no animated artwork), a network/5xx failure, or a hit whose stream the player then rejected.
 *
 * Ephemeral by design: it describes the current session, not a preference.
 */
object AnimatedArtworkDiagnostics {

    private val _status = MutableStateFlow("尚未查询")
    val status: StateFlow<String> = _status

    /** Short, user-readable line; the raw diagnostic still goes to the LX log. */
    fun record(diagnostic: String) {
        val text = diagnostic.removePrefix("stage=animated-artwork ").trim()
        _status.value = when {
            text.startsWith("search hit") -> "已命中，正在准备视频"
            text.startsWith("gate ") -> when {
                text.contains("transition=true") -> "封面↔歌词切换中（transition 卡住）· $text"
                !text.contains("url=true") -> "已命中但未拿到视频地址（url=null）· $text"
                !text.contains("enabled=true") -> "视频层被开关关闭 · $text"
                !text.contains("style=classic") -> "视频层被播放器样式拦截 · $text"
                !text.contains("repo=true") -> "视频源未初始化 · $text"
                else -> "视频层条件齐备 · $text"
            }
            text.startsWith("compose") -> "已装载视频层，等待第一帧"
            text.startsWith("skipped reason=style-") ->
                "当前是黑胶/非经典样式：动态封面只在「经典封面」下播放"
            text.startsWith("skipped reason=transitioning") -> "封面↔歌词切换中，视频层稍后恢复"
            text.startsWith("cache hit value=true") -> "已命中（缓存）"
            text.startsWith("cache hit value=false") -> "未命中（缓存：该专辑没有动态封面）"
            text.startsWith("playing") -> "已命中并开始播放"
            text.startsWith("search miss reason=404") -> "未命中：该专辑没有动态封面（404）"
            text.startsWith("search miss reason=parse") -> "未命中：接口返回无法解析（可能是接口变更）"
            text.startsWith("search busy") -> "接口繁忙（5xx），下次播放会重试"
            text.startsWith("search error") -> "查询失败：$text"
            text.startsWith("play error") -> "已命中但播放失败：$text"
            text.startsWith("fallback") -> "已回落到静态封面：$text"
            else -> text
        }
    }

    fun reset() {
        _status.value = "尚未查询"
    }
}
