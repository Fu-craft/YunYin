package com.yunyin.music.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.yunyin.music.ui.icons.SfIcons

/**
 * The audio-quality tiers the app can request.
 *
 * One definition, used by the player's chip and by the sheet that changes it. Previously the label
 * mapping lived in `MainActivity` while the list of selectable levels lived in the Settings screen —
 * two lists of the same thing, which is how a level can quietly exist in one and not the other. The
 * level strings are what `/song/url/v1` is given, so they must match the service exactly.
 *
 * @param level the value sent to the API.
 * @param label how the player names it, and the row's own name — the same string in both places, so
 *        the chip cannot disagree with what the sheet just selected.
 * @param detail the row's second line: what the tier actually is.
 */
enum class AudioQuality(
    val level: String,
    val label: String,
    val detail: String,
) {
    Standard("standard", "标准音质", "普通音质，流量占用最小"),
    Higher("higher", "较高音质", "比特率高于标准"),
    ExHigh("exhigh", "极高音质", "比特率高于较高音质"),
    Lossless("lossless", "无损音质", "FLAC 无损，保留原始细节"),
    HiRes("hires", "Hi-Res", "高解析度，超出无损规格"),
    ;

    /**
     * The tier's marker.
     *
     * The three lossy tiers share a bar-count shape, because they differ only in degree; the two
     * lossless tiers get a badge, because they are a different kind of thing rather than a fourth and
     * fifth bar. That distinction is the same one NetEase draws with its badges, and it is why the
     * icons are not simply 1–5 bars.
     */
    val icon: ImageVector
        get() = when (this) {
            Standard -> SfIcons.qualityBars(1)
            Higher -> SfIcons.qualityBars(2)
            ExHigh -> SfIcons.qualityBars(3)
            Lossless -> SfIcons.Diamond
            HiRes -> SfIcons.DiamondDouble
        }

    companion object {
        /** The tier for [level], falling back to [ExHigh] for anything unrecognised. */
        fun from(level: String?): AudioQuality =
            entries.firstOrNull { it.level == level } ?: ExHigh
    }
}
