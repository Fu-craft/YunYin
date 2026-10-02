package com.yunyin.music.ui

/**
 * Which player presentation the user has chosen.
 *
 * The two are the same screen with a different centrepiece: [Classic] draws the album cover as a rounded
 * square, [Vinyl] draws it as a rotating record. Everything around it — title, artist, progress, transport
 * controls, the lyrics page — is identical, which is why this is a *style* and not a second player.
 *
 * Stored by [level] as a string, matching how [AudioQuality] persists its tier: the value in
 * `SettingsStore` is stable across renames of the enum constant, and a value this build does not recognise
 * falls back to the default rather than failing to build a player.
 */
enum class PlayerStyle(
    val level: String,
    val label: String,
    val detail: String,
) {
    Classic("classic", "经典封面", "圆角方形封面，Apple Music 风格"),
    Vinyl("vinyl", "黑胶唱片", "唱片随播放旋转，暂停即停"),
    ;

    companion object {
        /** The style for [level], falling back to [Classic] for anything unrecognised. */
        fun from(level: String?): PlayerStyle =
            entries.firstOrNull { it.level == level } ?: Classic
    }
}
