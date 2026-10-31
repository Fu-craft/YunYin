// Host shims for the ported ribbon (GNU AGPL-3.0).
//
// Signature taken from the reference's ui/component/RefreshRateControl.kt:
//   internal fun applyViewRefreshRate(view: View, preference: RefreshRatePreference)
// It is a plain function, not a View extension — guessing that wrong was what produced the
// "receiver type mismatch" at the ribbon's AndroidView call site.
package com.yunyin.music.ui.components.shelf

enum class RefreshRatePreference { Auto, High, Standard }

/** We do not switch panel refresh rates; the display's own rate is used. */
fun applyViewRefreshRate(view: android.view.View, preference: RefreshRatePreference) = Unit

val LocalMusicBottomInset = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.ui.unit.Dp(0f) }

val LocalMusicPageActive = androidx.compose.runtime.staticCompositionLocalOf { true }

val LocalRefreshRatePreference = androidx.compose.runtime.staticCompositionLocalOf { RefreshRatePreference.Auto }