// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.yunyin.music.YunYinApp

/**
 * The interface font: SF Pro when the licence-restricted file is present, the platform's own
 * sans-serif family otherwise.
 *
 * The repository cannot redistribute SF Pro (Apple's licence), so `res/font/sf_pro.ttf` is
 * gitignored. Referencing `R.font.sf_pro` here made a clean checkout fail to *compile* — the R class
 * simply has no such field without the file — which is an open-source blocker, not just a CI
 * annoyance. The resource is therefore resolved **at runtime** by name: a missing font is a runtime
 * capability, so the app builds and runs on a clean checkout with the system family.
 *
 * The typography scale, weights and line heights are unchanged either way; only the family's
 * resolution differs. When the file is present, the variable font is mapped across the same weight
 * axis as before, so the look is identical.
 *
 * Lazy and exception-safe: if the process has no [YunYinApp] yet (previews, plain JVM tests) or the
 * lookup fails, it falls back rather than throwing.
 */
@OptIn(ExperimentalTextApi::class)
val SFPro: FontFamily by lazy(LazyThreadSafetyMode.PUBLICATION) { loadSfPro() }

/** One entry of the weight axis that used to be a compile-time `Font(R.font.sf_pro, …)`. */
private data class SfProWeight(val weight: FontWeight, val axis: Int)

private val SF_PRO_WEIGHTS = listOf(
    SfProWeight(FontWeight.Light, 300),
    SfProWeight(FontWeight.Normal, 400),
    SfProWeight(FontWeight.Medium, 500),
    SfProWeight(FontWeight.SemiBold, 600),
    SfProWeight(FontWeight.Bold, 700),
    SfProWeight(FontWeight.ExtraBold, 800),
    SfProWeight(FontWeight.Black, 900),
)

@OptIn(ExperimentalTextApi::class)
private fun loadSfPro(): FontFamily {
    val context = runCatching { YunYinApp.instance.applicationContext }.getOrNull()
        ?: return FontFamily.Default
    return runCatching {
        val id = context.resources.getIdentifier("sf_pro", "font", context.packageName)
        if (id == 0) {
            FontFamily.Default
        } else {
            FontFamily(
                *SF_PRO_WEIGHTS.map { entry ->
                    Font(
                        id,
                        weight = entry.weight,
                        variationSettings = FontVariation.Settings(FontVariation.weight(entry.axis)),
                    )
                }.toTypedArray(),
            )
        }
    }.getOrDefault(FontFamily.Default)
}

/**
 * Typography scale mirroring iOS Dynamic Type sizes.
 *
 * Sizes match the HIG's default (Large) content size category: a 34pt large title,
 * 22pt title, 17pt body, 15pt subhead, 13pt footnote, 11pt caption.
 */
@Composable
fun appleTypography(): Typography = Typography(
    // Large title — navigation-level headings ("精选歌单", "每日推荐")
    displayLarge = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.37.sp),
    // Section title
    displayMedium = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.36.sp),
    displaySmall = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.35.sp),
    // Titles
    headlineLarge = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = -0.26.sp),
    headlineMedium = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = -0.45.sp),
    headlineSmall = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = -0.41.sp),
    titleLarge = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = -0.41.sp),
    titleMedium = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = -0.32.sp),
    titleSmall = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = -0.24.sp),
    // Body
    bodyLarge = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = -0.41.sp),
    bodyMedium = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = -0.24.sp),
    bodySmall = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = -0.08.sp),
    // Labels / footnotes
    labelLarge = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = -0.24.sp),
    labelMedium = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = -0.08.sp),
    labelSmall = TextStyle(fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.06.sp),
)
