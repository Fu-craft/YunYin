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
import com.yunyin.music.R

/**
 * SF Pro, bundled and mapped across the weight axis.
 *
 * iOS expresses hierarchy with size and weight on a single family rather than
 * distinct typefaces, so every role resolves to SF Pro.
 */
@OptIn(ExperimentalTextApi::class)
val SFPro: FontFamily = FontFamily(
    Font(R.font.sf_pro, weight = FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.sf_pro, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.sf_pro, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.sf_pro, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.sf_pro, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.sf_pro, weight = FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.sf_pro, weight = FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

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
