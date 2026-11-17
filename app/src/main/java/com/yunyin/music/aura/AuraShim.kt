// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
//
// AuraPlayer 移植层的「运行时替身」。
//
// AuraCatalogDetails.kt / AuraLibraryPages.kt 是从 AuraPlayer 逐行拷来的原件，只改了 package 与
// 内部 import。它们引用的数据模型、设计 token、布局对象、动作总线都属于 AuraPlayer 自己的运行时，
// 本文件提供同名同签名的替身，让原件能原样编译；真正的数据由下层的适配器（ArtistScreen）填充。

package com.yunyin.music.aura

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme

// ------------------------------------------------------------------ 数据模型

/** AuraPlayer 的歌曲模型（字段与原件引用一致）。 */
data class Song(
    val id: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = 0L,
    val artistId: Long = 0L,
    val duration: Long = 0L,
    val artwork: String = "",
    val year: Int = 0,
    val track: Int = 0,
    val volume: Int = 0,
    val playCount: Long = 0L,
    val source: String = "",
    val absolutePath: String = "",
    val isFile: Boolean = false,
    val cloud: Boolean = false,
    val albumArtist: String = "",
    val published: Long = 0L,
    val total: Int = 0,
    val uri: String = "",
) {
    val key: String get() = if (cloud) "cloud:$id" else id.toString()
}

/** 集合：专辑 / 艺术家 / 歌单都走这一个类型。 */
data class MusicCollection(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val songs: List<Song> = emptyList(),
    val artwork: String = "",
    val albumArtist: String = "",
    val published: Long = 0L,
    val total: Int = 0,
    val language: String = "",
)

data class CatalogAlbum(
    val id: Long,
    val title: String,
    val artwork: String,
    val artistId: Long = 0L,
    val artist: String = "",
    val count: Int = 0,
    val published: Long = 0L,
)

data class AuraCatalogInfo(
    val artist: String = "",
    val title: String = "",
    val albums: List<CatalogAlbum> = emptyList(),
    val published: Long = 0L,
    val company: String = "",
    val description: String = "",
)

data class CloudPlaylistContent(
    val catalog: AuraCatalogInfo? = null,
    val source: String = "",
    val total: Int = 0,
    val songs: List<Song> = emptyList(),
    val albums: List<CatalogAlbum> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val hasMore: Boolean = false,
    val albumsLoading: Boolean = false,
    val albumsFailed: Boolean = false,
    val albumsHasMore: Boolean = false,
)

data class LocalPlaylist(
    val id: String = "",
    val name: String = "",
    val songKeys: List<String> = emptyList(),
)

data class ResolvedArtistMedia(
    val logoUrl: String? = null,
    val artwork: String = "",
    val motionVideoPath: String? = null,
)

data class SongActionRequest(
    val song: Song,
    val backdrop: Any? = null,
    val onLongAction: (() -> Unit)? = null,
)

data class NowPlayingConfig(
    val albumAnimatedCover: Boolean = false,
    val artistAnimatedCover: Boolean = false,
    val artistWordmarkLogo: Boolean = false,
)

// --------------------------------------------------------------- 设计 token

data class AuraHeroTokens(
    val heroMixTint: Color = Color.Transparent,
    val surface: Color = Color.Transparent,
    val foreground: Color = Color.Black,
    val muted: Color = Color.Gray,
    val accent: Color = Color.Black,
    val onAccent: Color = Color.White,
    val cardFill: Color = Color.White.copy(alpha = 0.18f),
)

data class AuraExpressiveScheme(
    val surfaceContainerHigh: Color = Color.White,
    val primary: Color = Color.Black,
    val secondaryContainer: Color = Color.Transparent,
    val onSecondaryContainer: Color = Color.Black,
    val onSurface: Color = Color.Black,
    val onSurfaceVariant: Color = Color.Gray,
)

data class AuraOneUiPalette(
    val card: Long = 0xFFF4F4F4,
    val cardAlt: Long = 0xFFEDEDED,
    val chip: Long = 0xFFE8E8E8,
    val accent: Long = 0xFF1A73E8,
    val accentDeep: Long = 0xFF0B57D0,
    val text: Long = 0xFF1A1A1A,
    val subText: Long = 0xFF6B6B6B,
)

class AuraTone(val tone: Int) {
    fun tone(level: Int): Int = tone
}

data class AuraMonetPalette(
    val accent: Color = Color.Unspecified,
    val a2: AuraTone = AuraTone(0xFF303030.toInt()),
)

@Composable
fun rememberCatalogPlayerThemeTokens(
    artwork: String = "",
    config: NowPlayingConfig = NowPlayingConfig(),
    fallbackDarkSurface: Boolean = false,
    forceHeroSurface: Boolean = false,
): AuraHeroTokens {
    val tint = rememberArtworkColor(artwork)
    val base = if (tint == Color.Transparent) Color(0xFF2A2A2E) else tint
    // 页面底色：封面主色压暗（沉浸式肤色），不是固定的白或黑。
    val surface = Color(
        red = (base.red * 0.22f).coerceIn(0f, 1f),
        green = (base.green * 0.20f).coerceIn(0f, 1f),
        blue = (base.blue * 0.24f).coerceIn(0f, 1f),
        alpha = 1f,
    )
    return AuraHeroTokens(
        heroMixTint = base,
        surface = surface,
        foreground = Color.White,
        muted = Color.White.copy(alpha = 0.72f),
        accent = base,
        onAccent = Color.Black,
        cardFill = Color.White.copy(alpha = 0.14f),
    )
}

@Composable
fun rememberActiveExpressiveScheme(): AuraExpressiveScheme {
    val palette = AppTheme.palette
    return AuraExpressiveScheme(
        secondaryContainer = palette.label.copy(alpha = 0.10f),
        onSecondaryContainer = palette.label,
        onSurface = palette.label,
        onSurfaceVariant = palette.secondaryLabel,
    )
}

@Composable
fun rememberActiveOneUiPalette(): AuraOneUiPalette = AuraOneUiPalette()

@Composable
fun rememberPlayerMonetPalette(artwork: String = ""): AuraMonetPalette = AuraMonetPalette()

// ------------------------------------------------------------------ 布局/风格

enum class AppUiStyle { ONE, MATERIAL, HYPER }

data class AuraLayout(
    val width: Int = 1080,
    val height: Int = 2400,
    val tablet: Boolean = false,
    val gridColumns: Int = 2,
    val pageMargin: androidx.compose.ui.unit.Dp = 20.dp,
    val listColumns: Int = 1,
)

val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.ONE }
val LocalAuraLayout = staticCompositionLocalOf { AuraLayout() }
val LocalAdvancedMaterialEnabled = staticCompositionLocalOf { false }
val LocalToolbarCollapsed = staticCompositionLocalOf { 0f }
val LocalToolbarBackdrop = staticCompositionLocalOf<CouiBackdrop?> { null }
val LocalAuraCouiBackdrop = staticCompositionLocalOf<CouiBackdrop?> { null }

class SongActions {
    fun openActions(request: SongActionRequest) = Unit
    operator fun invoke(request: SongActionRequest) = openActions(request)
}

val LocalSongActions = staticCompositionLocalOf { SongActions() }

fun pageContentModifier(): Modifier = Modifier

// ---------------------------------------------------------------------- 文本

fun tr(key: String, default: String): String = default

object I18n {
    val language: String = "zh"
}


val AuraMoreHorizontalIcon: ImageVector = SfIcons.Ellipsis

data class AuraMemoryEntry(val key: String)

fun findCachedArtworkMemoryEntry(loader: Any?, uri: String): AuraMemoryEntry? = null

// ------------------------------------------------------------------ ViewModel

/** 只是让原件编译的替身：真正的数据由 [com.yunyin.music.ui.screens.ArtistScreen] 注入。 */
class AuraViewModel(
    songs: List<Song> = emptyList(),
    sortedSongs: List<Song> = emptyList(),
    downloadedSongs: List<Song> = emptyList(),
    current: Song? = null,
    account: Any? = null,
    playlists: List<LocalPlaylist> = emptyList(),
    val history: AuraHistory = AuraHistory(),
    val nowPlayingConfig: NowPlayingConfig = NowPlayingConfig(),
    private val onPlayCollectionAt: (List<Song>, Int) -> Unit = { _, _ -> },
    private val onPlaySongNow: (Song) -> Unit = { _ -> },
    private val onOpenAlbum: (Song) -> Unit = { _ -> },
    private val onOpenArtist: (Song) -> Unit = { _ -> },
    private val onLoadMoreAlbumPages: () -> Unit = {},
    private val onLoadMoreSongPages: () -> Unit = {},
    private val onRefresh: () -> Unit = {},
) {
    val songs: List<Song> = songs
    val sortedSongs: List<Song> = sortedSongs
    val downloadedSongs: List<Song> = downloadedSongs
    val current: Song? = current
    val account: Any? = account
    val playlists: List<LocalPlaylist> = playlists
    val hasPermission: Boolean = true
    val revision: Int = 0
    val language: String = "zh"
    val catalog: List<MusicCollection> = emptyList()

    fun hasPermission(permission: String): Boolean = true
    fun play(song: Song, queue: List<Song> = emptyList()) = onPlaySongNow(song)
    fun play(tracks: List<Song>, index: Int) = onPlayCollectionAt(tracks, index)
    fun playCollection(tracks: List<Song>, content: CloudPlaylistContent? = null) =
        onPlayCollectionAt(tracks, 0)
    fun playCollection(tracks: List<Song>, index: Int, content: CloudPlaylistContent? = null) =
        onPlayCollectionAt(tracks, index)
    fun openCollection(collection: MusicCollection) = Unit
    fun openCatalogAlbum(album: CatalogAlbum) = Unit
    fun openSongAlbum(song: Song) = onOpenAlbum(song)
    fun openSongArtist(song: Song) = onOpenArtist(song)
    fun openSongActions(song: Song) = Unit
    fun loadMoreArtistAlbums(content: CloudPlaylistContent) = onLoadMoreAlbumPages()
    fun loadMoreCloudSongsFor(content: CloudPlaylistContent) = onLoadMoreSongPages()
    fun refreshCloudSongs() = onRefresh()
    fun refreshDownloadedSongs() = onRefresh()
    fun refreshLibrary() = onRefresh()
    fun savePlaylists() = Unit
    fun peekCachedFile(song: Song): java.io.File? = null
    fun resolveArtistMedia(name: String): ResolvedArtistMedia = ResolvedArtistMedia()
}

// ------------------------------------------------------------- 组件替身（空壳）

@Composable
fun EmptyState(
    title: String,
    subtitle: String = "",
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        Text(subtitle)
        if (actionLabel != null) Text(actionLabel, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun GlassDialogButton(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: Any? = null,
    content: @Composable () -> Unit = {},
) = Unit

data class DropdownItem(val label: String, val onClick: () -> Unit = {})

data class DropdownEntry(val items: List<DropdownItem> = emptyList())

@Composable
fun GlassIconDropdownMenu(
    entry: DropdownEntry,
    backdrop: CouiBackdrop? = null,
    content: @Composable () -> Unit = {},
) = Unit

@Composable
fun GlassToolbarIconButton(
    icon: ImageVector,
    description: String = "",
    backdrop: Any? = null,
    collapsedFraction: Float = 1f,
    containerPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    surfaceAlpha: Float = 0.24f,
    onClick: () -> Unit = {},
) = Unit

@Composable
fun GlassToolbarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) = Unit

@Composable
fun MaterialExpressiveButton(
    text: String = "",
    onClick: () -> Unit = {},
    content: @Composable () -> Unit = {},
) = Unit

@Composable
fun OneUiButton(
    text: String = "",
    onClick: () -> Unit = {},
    content: @Composable () -> Unit = {},
) = Unit

@Composable
fun SeamlessCard(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 22.dp,
    insideMargin: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable () -> Unit = {},
) = Unit

@Composable
fun WindowDialog(
    visible: Boolean,
    title: String = "",
    titleTextAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Center,
    summary: String? = null,
    summaryTextAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Center,
    onDismissRequest: () -> Unit = {},
    content: @Composable () -> Unit = {},
) = Unit

@Composable
fun AuraCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) = Unit

@Composable
fun AuraTextField(
    value: String,
    onValueChange: (String) -> Unit = {},
    placeholder: String = "",
    label: String = "",
    singleLine: Boolean = false,
    modifier: Modifier = Modifier,
) = Unit

@Composable
fun Preference(title: String, onClick: () -> Unit) = Unit

@Composable
fun CatalogNowPlayingBackground(
    artwork: String = "",
    config: NowPlayingConfig = NowPlayingConfig(),
    fallbackDarkSurface: Boolean = false,
    forceHeroSurface: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tint = rememberArtworkColor(artwork)
    val base = if (tint == Color.Transparent) Color(0xFF2A2A2E) else tint
    androidx.compose.foundation.layout.Box(
        modifier
            .then(Modifier)
            .fillMaxSizeCompat()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to base.copy(alpha = 0.55f),
                    0.55f to base.copy(alpha = 0.22f),
                    1f to Color(0xFF101013),
                ),
            ),
    )
}

/** `fillMaxSize` 的小包装（避免在替身里引入整包 layout import）。 */
private fun Modifier.fillMaxSizeCompat(): Modifier = this.fillMaxSize()

@Composable
fun AnimatedMotionCoverVideo(
    videoPath: String?,
    playing: Boolean = false,
    visibleAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) = Unit

object AppleMusicMotionCoverManager {
    fun peekCachedFile(context: android.content.Context, song: Song): java.io.File? = null
    fun resolve(
        context: android.content.Context,
        song: Song,
        activePlayingPath: String? = null,
    ): java.io.File? = null
}

object AppleMusicArtistLogoManager {
    fun peekCachedArtistMotionPath(context: android.content.Context, artistName: String): String? = null
    fun resolveArtistMedia(
        context: android.content.Context,
        artistName: String,
        needLogo: Boolean = false,
        needArtwork: Boolean = false,
        needMotionVideo: Boolean = false,
        activePlayingPath: String? = null,
        activePlayingVideoPath: String? = null,
    ): ResolvedArtistMedia = ResolvedArtistMedia()
}

fun appleMusicTabIcon(index: Int): ImageVector = SfIcons.MusicNote



/** 原件引用的是 AuraPlayer 自己的 R；这里映射到我们已经拷进项目的同款矢量。 */
object R {
    object drawable {
        val ic_player_play = com.yunyin.music.R.drawable.aura_ic_play
        val ic_player_pause = com.yunyin.music.R.drawable.aura_ic_pause
        val ic_player_next = com.yunyin.music.R.drawable.aura_ic_next
        val ic_player_previous = com.yunyin.music.R.drawable.aura_ic_previous
        val ic_player_favorite = com.yunyin.music.R.drawable.aura_ic_favorite
        val ic_player_favorite_on = com.yunyin.music.R.drawable.aura_ic_favorite_on
        val ic_player_volume_full = com.yunyin.music.R.drawable.aura_ic_volume_full
        val ic_player_volume_muted = com.yunyin.music.R.drawable.aura_ic_volume_muted
        val ic_album_info = com.yunyin.music.R.drawable.aura_ic_album_info
    }
}


/** 原件里以函数形式调用动作总线的地方；转发到当前 [LocalSongActions]。 */
fun openSongActions(request: SongActionRequest) = Unit

/** miuix `ButtonDefaults` 的最小替身（原件只用到 buttonColorsPrimary）。 */
object ButtonDefaults {
    fun buttonColorsPrimary(): Any = Any()
}

/** coui 的背景类型直接用它们库里的。 */
typealias CouiBackdrop = com.itos.coui.backdrop.CouiBackdrop

// miuix 0.9.4（目前唯一可解析的版本）缺少 AuraPlayer 用到的这几个 Material3 风格配色名，
// 用扩展属性补上，原件里的 `MiuixTheme.colorScheme.xxx` 因此可以原样编译。
val top.yukonga.miuix.kmp.theme.Colors.surfaceContainerHigh: Color
    get() = surfaceVariant

val top.yukonga.miuix.kmp.theme.Colors.surfaceContainer: Color
    get() = surface

val top.yukonga.miuix.kmp.theme.Colors.surfaceContainerLow: Color
    get() = background


/** 播放历史 + 本地歌单（原件用 model.history.revision / playlists() / savePlaylists()）。 */
class AuraHistory {
    val revision: Int = 0
    fun playlists(): List<LocalPlaylist> = emptyList()
    fun savePlaylists(list: List<LocalPlaylist>) = Unit
}




// ------------------------------------------------------- 封面取色（背景 / 前景）

private val artworkColorCache = java.util.concurrent.ConcurrentHashMap<String, Color>()

/** 用 Coil 取回封面并算主色（AuraPlayer 的 rememberPlayerMonetPalette 做的是同一件事）。 */
@Composable
fun rememberArtworkColor(url: String): Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cached = artworkColorCache[url]
    if (cached != null) return cached
    val state = androidx.compose.runtime.produceState(
        initialValue = Color.Transparent,
        url,
    ) {
        if (url.isBlank()) {
            value = Color.Transparent
            return@produceState
        }
        val color: Color = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // 直接用 OkHttp 取封面字节再解码：Coil3 的 Image 类型在不同版本间不一致，
                // Palette 只需要一张 Bitmap。
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Color.Transparent
                    } else {
                        val bitmap = android.graphics.BitmapFactory
                            .decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap == null) {
                            Color.Transparent
                        } else {
                            val palette = androidx.palette.graphics.Palette.from(bitmap).generate()
                            val swatch = palette.vibrantSwatch
                                ?: palette.dominantSwatch
                                ?: palette.mutedSwatch
                            if (swatch != null) Color(swatch.rgb) else Color.Transparent
                        }
                    }
                }
            }.getOrDefault(Color.Transparent)
        }
        artworkColorCache[url] = color
        value = color
    }
    return state.value
}
