// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面风格。
 *
 * 两套完全独立的实现并存:一套是原有的 miuix(经典),一套是 COUI(凝光)。用户在选择里切换,
 * 切换的是"用哪一份 Composable",不是给同一份代码换颜色——所以两套的字距、留白、动效都可以各自
 * 忠于自己的规范,不会互相妥协。
 */
enum class UiStyle(val storageKey: String, val label: String, val summary: String) {
    Classic("classic", "经典", "Miuix 组件,信息密度更高"),
    Coui("coui", "凝光", "COUI 组件,ColorOS 17 凝光材质"),
    ;

    companion object {
        fun fromKey(key: String?): UiStyle = entries.firstOrNull { it.storageKey == key } ?: Classic
    }
}

/**
 * 当前风格。默认经典:升级上来的用户看到的界面不会变,只有主动去设置里切换才会换。
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.Classic }

/**
 * 当前风格的全局读数。
 *
 * 共享组件(如 [com.yunyin.music.ui.components.SectionHeader])散落在各屏,逐个透传 [LocalUiStyle]
 * 要改几十个调用点;这里用一个可观察的全局值,由 MainActivity 在启动与切换时写入。它只决定"用哪一份
 * 实现",不携带任何页面状态。
 */
object CurrentUiStyle {
    var value by mutableStateOf(UiStyle.Classic)
}