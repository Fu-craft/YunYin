package com.yunyin.music.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.yunyin.music.ui.icons.MaterialSymbols

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
     * The tier's marker, from **Google Material Symbols** (Apache-2.0) rather than hand-drawn.
     *
     * Three kinds of thing are being distinguished, so three kinds of glyph are used:
     *
     *  - the lossy tiers differ only in degree, so they share the ascending-bars family and the bar
     *    count carries the level — the one encoding where "more" needs no legend;
     *  - 无损 is not "higher than 极高", it is a different format, so it gets a **gem** badge rather
     *    than a fourth bar, which would imply the same kind of thing at a higher number;
     *  - Hi-Res is a resolution claim, which is exactly what the **HD badge** says.
     *
     * Imported by `tools/import_material_symbols.py` from the official SVGs; see [MaterialSymbols].
     */
    val icon: ImageVector
        get() = when (this) {
            Standard -> MaterialSymbols.SignalCellularAlt1Bar
            Higher -> MaterialSymbols.SignalCellularAlt2Bar
            ExHigh -> MaterialSymbols.SignalCellularAlt
            Lossless -> MaterialSymbols.Diamond
            HiRes -> MaterialSymbols.Hd
        }

    companion object {
        /** The tier for [level], falling back to [ExHigh] for anything unrecognised. */
        fun from(level: String?): AudioQuality =
            entries.firstOrNull { it.level == level } ?: ExHigh
    }
}
