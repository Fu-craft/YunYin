// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 云音乐（Netease）的点赞与评论图标，路径取自官方 APK 的矢量资源：
 *
 * - 心形：`res/drawable/dolphin_outline_like.xml`
 * - 气泡：`res/drawable/dolphin_outline_comment.xml`（含两条对话线）
 *
 * 视口 24×24；线框用 1.8 dp 描边、圆头圆角，和官方的线框风格一致。点赞后调用方改用
 * [heartFill]（同一条路径填充），形状不变，所以按下时不会有图形跳变。
 */
object NetEaseIcons {

    private const val HEART =
        "M7.2,17.6C4.3,15 2.9,12.6 2.9,9.7C2.9,6.2 5.4,3.5 8.4,3.5C9.6,3.5 10.7,3.9 11.7,4.7" +
            "L12,5L12.3,4.7C13.2,3.9 14.4,3.5 15.6,3.5C18.6,3.5 21.1,6.2 21.1,9.7C21.1,12.6 19.8,15 " +
            "16.8,17.6C15.8,18.5 14.7,19.3 13.6,20.1C13.2,20.3 12.3,21 12.2,21C12.1,21.1 12,21.1 " +
            "11.9,21C11.8,20.9 10.8,20.3 10.5,20.1C9.3,19.4 8.2,18.5 7.2,17.6Z"

    private const val BUBBLE =
        "M3.581,20.185L3.59,20.166L3.598,20.146C3.676,19.938 3.882,19.495 4.239,18.775C4.442,18.365 " +
            "4.56,17.983 4.528,17.571C4.497,17.169 4.329,16.806 4.105,16.427C3.318,15.095 2.9,13.538 " +
            "2.9,11.899C2.9,6.873 6.974,2.799 12,2.799C17.026,2.799 21.1,6.873 21.1,11.899C21.1,16.82 " +
            "17.188,20.846 12.268,20.995L4.138,20.999C4.138,20.999 4.138,20.999 4.137,20.999C3.807,20.999 " +
            "3.54,20.731 3.54,20.402C3.54,20.305 3.563,20.222 3.581,20.185Z"

    private const val BUBBLE_LINE_TOP = "M7.5,9.499L16.497,9.509"
    private const val BUBBLE_LINE_BOTTOM = "M7.5,14.296H14.707"

    val heartLine: ImageVector = strokeIcon("NetEaseHeartLine", listOf(HEART))
    val heartFill: ImageVector = fillIcon("NetEaseHeartFill", HEART)
    val comment: ImageVector =
        strokeIcon("NetEaseComment", listOf(BUBBLE, BUBBLE_LINE_TOP, BUBBLE_LINE_BOTTOM))

    private fun strokeIcon(name: String, paths: List<String>): ImageVector {
        val builder = builder(name)
        paths.forEach { data ->
            builder.addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private fun fillIcon(name: String, data: String): ImageVector {
        val builder = builder(name)
        builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = SolidColor(Color.Black),
        )
        return builder.build()
    }

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
}
