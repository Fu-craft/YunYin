// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
//
// AuraPlayer 移植层的运行时替身。
//
// AuraCatalogDetails.kt / AuraLibraryPages.kt / AuraAppleMusic*.kt 是从 AuraPlayer 逐行拷来的原件，
// 只改了 package 与内部 import。它们引用的数据模型、设计 token、布局对象、动作总线属于 AuraPlayer
// 自己的运行时，本文件给出同名同签名的替身，让原件原样编译；数据由 ArtistScreen 适配进来。

package com.yunyin.music.aura

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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

/** 播放历史 + 本地歌单（原件用 model.history.revision / playlists() / savePlaylists()）。 */
class AuraHistory {
    val revision: Int = 0
    fun playlists(): List<LocalPlaylist> = emptyList()
    fun savePlaylists(list: List<LocalPlaylist>) = Unit
}

// --------------------------------------------------------------- 设计 token

typealias AuraMonetPalette = com.materialkolor.palettes.CorePalette

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

@Composable
fun rememberActiveOneUiPalette(): AuraOneUiPalette = AuraOneUiPalette()

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
fun rememberPlayerMonetPalette(artwork: String = ""): AuraMonetPalette {
    val seed = rememberArtworkSeed(artwork)
    return androidx.compose.runtime.remember(seed) { com.materialkolor.palettes.CorePalette.of(seed) }
}

/** AuraPlayer `rememberCatalogPlayerThemeTokens`, using the same CorePalette tone values. */
@Composable
fun rememberCatalogPlayerThemeTokens(
    artwork: String = "",
    config: NowPlayingConfig = NowPlayingConfig(),
    fallbackDarkSurface: Boolean = false,
    forceHeroSurface: Boolean = false,
): AuraHeroTokens {
    val palette = rememberPlayerMonetPalette(artwork)
    return androidx.compose.runtime.remember(palette, fallbackDarkSurface, forceHeroSurface) {
        if (!forceHeroSurface && !fallbackDarkSurface) {
            val foreground = Color(palette.a1.tone(10))
            AuraHeroTokens(
                surface = Color(palette.a2.tone(90)),
                foreground = foreground,
                muted = foreground.copy(alpha = 0.66f),
                accent = Color(palette.a1.tone(25)),
                onAccent = Color.White,
                cardFill = foreground.copy(alpha = 0.10f),
                heroMixTint = Color(palette.a1.tone(82)),
            )
        } else {
            AuraHeroTokens(
                surface = Color(palette.a2.tone(14)),
                foreground = Color.White,
                muted = Color.White.copy(alpha = 0.70f),
                accent = Color(palette.a1.tone(80)),
                onAccent = Color(palette.n1.tone(12)),
                cardFill = Color.White.copy(alpha = 0.11f),
                heroMixTint = androidx.compose.ui.graphics.lerp(
                    Color(palette.a1.tone(30)),
                    Color(palette.a3.tone(24)),
                    0.35f,
                ),
            )
        }
    }
}

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

/** coui 的背景类型直接用它们库里的。 */
typealias CouiBackdrop = com.itos.coui.backdrop.CouiBackdrop

class SongActions {
    fun openActions(request: SongActionRequest) = Unit
    operator fun invoke(request: SongActionRequest) = openActions(request)
}

val LocalSongActions = staticCompositionLocalOf { SongActions() }

fun openSongActions(request: SongActionRequest) = Unit

fun pageContentModifier(): Modifier = Modifier

// ---------------------------------------------------------------------- 文本

fun tr(key: String, default: String): String = default

object I18n {
    val language: String = "zh"
}

val AuraMoreHorizontalIcon: ImageVector = SfIcons.Ellipsis

fun appleMusicTabIcon(index: Int): ImageVector = SfIcons.MusicNote

data class AuraMemoryEntry(val key: String)

fun findCachedArtworkMemoryEntry(loader: Any?, uri: String): AuraMemoryEntry? = null

// ------------------------------------------------------------------ ViewModel

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

object ButtonDefaults {
    fun buttonColorsPrimary(): Any = Any()
}

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
    containerPadding: PaddingValues = PaddingValues(0.dp),
    surfaceAlpha: Float = 0.24f,
    onClick: () -> Unit = {},
) = Unit

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
    insideMargin: PaddingValues = PaddingValues(16.dp),
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
fun AnimatedMotionCoverVideo(
    videoPath: String?,
    playing: Boolean = false,
    visibleAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) = Unit

/** 页面背景：封面主色 → 深底 的竖向渐变（AuraPlayer 的沉浸式背景）。 */
@Composable
fun CatalogNowPlayingBackground(
    artwork: String = "",
    config: NowPlayingConfig = NowPlayingConfig(),
    fallbackDarkSurface: Boolean = false,
    forceHeroSurface: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tokens = rememberCatalogPlayerThemeTokens(
        artwork = artwork,
        config = config,
        fallbackDarkSurface = fallbackDarkSurface,
        forceHeroSurface = forceHeroSurface,
    )
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize().background(tokens.surface))
}

// ------------------------------------------------------- 封面取色（背景 / 前景）

/** 由 ArtistScreen 注入：复用 UI 已经下载并缓存好的封面位图，避免为取色再下一次网络。 */
object AuraColorSource {
    var loadBitmap: (suspend (String, Int) -> android.graphics.Bitmap?)? = null
}

private const val DEFAULT_ARTWORK_SEED = 0xFF6750A4.toInt()
private const val ARTWORK_SEED_CACHE_LIMIT = 512
private val artworkSeedCache = LinkedHashMap<String, Int>(64, 0.75f, true)

/** AuraPlayer's exact 64x64 sampling + Celebi quantizer + Material score seed selection. */
private fun coverMonetSeed(bitmap: android.graphics.Bitmap?): Int {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_ARTWORK_SEED
    val pixels = ArrayList<Int>(4096)
    for (y in 0 until 64) for (x in 0 until 64) {
        val pixel = bitmap.getPixel(x * bitmap.width / 64, y * bitmap.height / 64)
        if (android.graphics.Color.alpha(pixel) >= 128) pixels.add(pixel or 0xFF000000.toInt())
    }
    if (pixels.isEmpty()) return DEFAULT_ARTWORK_SEED
    return com.materialkolor.score.Score.score(
        com.materialkolor.quantize.QuantizerCelebi.quantize(pixels.toIntArray(), 128),
        1,
        DEFAULT_ARTWORK_SEED,
        true,
    ).firstOrNull() ?: DEFAULT_ARTWORK_SEED
}

private fun cachedArtworkSeed(url: String): Int? = synchronized(artworkSeedCache) { artworkSeedCache[url] }

private fun recordArtworkSeed(url: String, seed: Int) {
    if (url.isBlank()) return
    synchronized(artworkSeedCache) {
        artworkSeedCache[url] = seed
        while (artworkSeedCache.size > ARTWORK_SEED_CACHE_LIMIT) {
            artworkSeedCache.remove(artworkSeedCache.entries.firstOrNull()?.key ?: break)
        }
    }
}

@Composable
fun rememberArtworkSeed(url: String): Int {
    val initial = androidx.compose.runtime.remember(url) { cachedArtworkSeed(url) ?: DEFAULT_ARTWORK_SEED }
    val state = androidx.compose.runtime.produceState(initialValue = initial, url) {
        if (url.isBlank()) return@produceState
        cachedArtworkSeed(url)?.let {
            value = it
            return@produceState
        }
        val seed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val loader = AuraColorSource.loadBitmap
                coverMonetSeed(loader?.invoke(url, 128))
            }.getOrDefault(DEFAULT_ARTWORK_SEED)
        }
        recordArtworkSeed(url, seed)
        value = seed
    }
    return state.value
}

/** Compatibility for the vendored page; the value is AuraPlayer's `a1.tone(40)`. */
@Composable
fun rememberArtworkColor(url: String): Color = Color(rememberPlayerMonetPalette(url).a1.tone(40))

suspend fun prewarmArtworkColor(url: String) {
    if (url.isBlank() || cachedArtworkSeed(url) != null) return
    val loader = AuraColorSource.loadBitmap ?: return
    val seed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { coverMonetSeed(loader(url, 128)) }.getOrDefault(DEFAULT_ARTWORK_SEED)
    }
    recordArtworkSeed(url, seed)
}

// ---------------------------------------------------------------------- 资源

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

// miuix 0.9.4 缺少 AuraPlayer 用到的这几个 Material3 风格配色名，用扩展属性补上。
val top.yukonga.miuix.kmp.theme.Colors.surfaceContainerHigh: Color
    get() = surfaceVariant

val top.yukonga.miuix.kmp.theme.Colors.surfaceContainer: Color
    get() = surface

val top.yukonga.miuix.kmp.theme.Colors.surfaceContainerLow: Color
    get() = background
