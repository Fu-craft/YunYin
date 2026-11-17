package com.yunyin.music.aura

import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.yunyin.music.aura.*
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val ArtistTopSongMinCardWidthDp = 220

internal fun artistTopSongCardWidth(viewportWidth: Dp, margin: Dp, columnCount: Int): Dp {
    val contentWidth = (viewportWidth - margin * 2).coerceAtLeast(ArtistTopSongMinCardWidthDp.dp)
    if (columnCount <= 1) return contentWidth
    return if (viewportWidth >= 600.dp) {
        ((contentWidth - 12.dp) / 2f).coerceIn(280.dp, 460.dp)
    } else {
        (contentWidth - 20.dp).coerceAtLeast(ArtistTopSongMinCardWidthDp.dp)
    }
}

internal fun formatArtistSongAlbumYear(song: Song): String =
    listOf(song.album, song.year.takeIf { it > 0 }?.toString().orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" · ")

internal fun MusicCollection.isCatalog() = key.startsWith("album:") || key.startsWith("artist:") || key.startsWith("cloud:album:") || key.startsWith("cloud:artist:")
internal fun MusicCollection.isArtist() = key.startsWith("artist:") || key.startsWith("cloud:artist:")
private const val ArtistHeroHeightScale = 1.25f * .90f
private const val ArtistHeroTransitionScale = .35f * .85f / ArtistHeroHeightScale
internal val AlbumTrackRowHeight = 74.dp * .88f * .92f * .93f
internal val AlbumTrackRowVerticalPadding = (AlbumTrackRowHeight - 44.dp) / 2

internal fun resolveAlbumArtist(
    catalogArtist: String?,
    collectionSubtitle: String,
    tracks: List<Song>,
): String = catalogArtist?.trim()?.takeIf(String::isNotEmpty)
    ?: tracks.firstNotNullOfOrNull { it.albumArtist.trim().takeIf(String::isNotEmpty) }
    ?: collectionSubtitle.trim().takeIf(String::isNotEmpty)
    ?: tracks.firstOrNull()?.artist?.trim().orEmpty()

internal fun shouldShowAlbumTrackArtist(song: Song, resolvedAlbumArtist: String): Boolean {
    val trackArtist = song.artist.trim()
    if (trackArtist.isEmpty()) return false
    val albumArtist = song.albumArtist.trim().ifEmpty { resolvedAlbumArtist.trim() }
    if (albumArtist.isEmpty()) return false
    return !trackArtist.equals(albumArtist, ignoreCase = true)
}

internal fun formatAlbumTotalDuration(
    tracks: List<Song>,
    language: String = I18n.language,
    hourUnit: String = tr("hours", "小时"),
    minuteUnit: String = tr("minutes", "分钟"),
): String? {
    val totalMs = tracks.sumOf { it.duration.coerceAtLeast(0L) }
    if (totalMs <= 0L) return null
    val totalMinutes = ((totalMs + 30_000L) / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val joiner = if (language == "en") " " else ""
    return when {
        hours > 0L && minutes > 0L -> "$hours$joiner$hourUnit$joiner$minutes$joiner$minuteUnit"
        hours > 0L -> "$hours$joiner$hourUnit"
        else -> "$minutes$joiner$minuteUnit"
    }
}

internal fun formatAlbumReleaseSummary(dateLabel: String?, durationLabel: String?): String? =
    listOfNotNull(dateLabel?.takeIf(String::isNotBlank), durationLabel?.takeIf(String::isNotBlank))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

internal fun representativeAlbumMotionSong(
    collection: MusicCollection,
    tracks: List<Song>,
    albumArtist: String,
): Song? {
    val first = tracks.firstOrNull()
    val effectiveAlbum = first?.album?.trim()?.takeIf(String::isNotEmpty) ?: collection.title.trim()
    val effectiveArtist = albumArtist.trim()
        .ifEmpty { first?.albumArtist?.trim().orEmpty() }
        .ifEmpty { first?.artist?.trim().orEmpty() }
    if (effectiveAlbum.isEmpty() || effectiveArtist.isEmpty()) return null
    return first?.copy(
        album = effectiveAlbum,
        albumArtist = effectiveArtist,
    ) ?: Song(
        id = 0L,
        title = effectiveAlbum,
        artist = effectiveArtist,
        album = effectiveAlbum,
        albumId = 0L,
        artistId = 0L,
        uri = "",
        artwork = collection.artwork,
        albumArtist = effectiveArtist,
        cloud = collection.key.startsWith("cloud:"),
    )
}

internal fun shouldShowImmersiveAlbumHero(
    isArtist: Boolean,
    albumAnimatedCoverEnabled: Boolean,
    motionVideoPath: String?,
): Boolean = !isArtist && albumAnimatedCoverEnabled && !motionVideoPath.isNullOrBlank()

@Composable
internal fun rememberCatalogMotionCoverPath(
    collection: MusicCollection?,
    tracks: List<Song>,
    enabled: Boolean,
): String? {
    val context = LocalContext.current
    val isArtist = collection?.isArtist() == true
    val albumArtist = remember(collection?.key, collection?.subtitle, tracks) {
        if (collection == null) "" else resolveAlbumArtist(null, collection.subtitle, tracks)
    }
    val probeSong = remember(collection?.key, tracks, albumArtist, enabled) {
        if (collection == null || isArtist || !enabled) null
        else representativeAlbumMotionSong(collection, tracks, albumArtist)
    }
    var motionFile by remember(probeSong?.key, probeSong?.album, probeSong?.albumArtist, enabled) {
        mutableStateOf(
            if (enabled && probeSong != null) {
                AppleMusicMotionCoverManager.peekCachedFile(context, probeSong)
            } else {
                null
            },
        )
    }
    LaunchedEffect(probeSong?.key, probeSong?.album, probeSong?.albumArtist, enabled) {
        val target = probeSong
        if (!enabled || target == null) {
            motionFile = null
            return@LaunchedEffect
        }
        val cached = AppleMusicMotionCoverManager.peekCachedFile(context, target)
        if (cached != null) {
            motionFile = cached
            return@LaunchedEffect
        }
        motionFile = null
        motionFile = AppleMusicMotionCoverManager.resolve(
            context = context,
            song = target,
            activePlayingPath = motionFile?.absolutePath,
        )
    }
    return if (enabled && !isArtist) {
        motionFile?.takeIf { it.isFile && it.length() > 0L }?.absolutePath
    } else {
        null
    }
}

@Composable
internal fun rememberCatalogArtistMedia(
    collection: MusicCollection?,
    needLogo: Boolean,
    needArtwork: Boolean,
    needMotionVideo: Boolean,
): ResolvedArtistMedia {
    val context = LocalContext.current
    val isArtist = collection?.isArtist() == true
    val artistName = collection?.title.orEmpty()
    val initialMotion = remember(isArtist, artistName, needMotionVideo) {
        if (isArtist && needMotionVideo && artistName.isNotBlank()) {
            AppleMusicArtistLogoManager.peekCachedArtistMotionPath(context, artistName)
        } else {
            null
        }
    }
    var media by remember(isArtist, collection?.key, artistName, needLogo, needArtwork, needMotionVideo) {
        mutableStateOf(ResolvedArtistMedia(motionVideoPath = initialMotion))
    }
    LaunchedEffect(isArtist, collection?.key, artistName, needLogo, needArtwork, needMotionVideo) {
        if (!isArtist || artistName.isBlank() || (!needLogo && !needArtwork && !needMotionVideo)) {
            media = ResolvedArtistMedia()
            return@LaunchedEffect
        }
        val activePath = media.motionVideoPath ?: initialMotion
        media = AppleMusicArtistLogoManager.resolveArtistMedia(
            context = context,
            artistName = artistName,
            needLogo = needLogo,
            needArtwork = needArtwork,
            needMotionVideo = needMotionVideo,
            activePlayingVideoPath = activePath,
        )
    }
    return media
}

@Composable
internal fun CatalogBackground(
    collection: MusicCollection?,
    modifier: Modifier = Modifier,
    nowPlayingConfig: NowPlayingConfig = NowPlayingConfig(),
    immersiveAlbum: Boolean = false,
) {
    collection ?: return
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    CatalogNowPlayingBackground(
        artwork = collection.artwork,
        config = nowPlayingConfig,
        fallbackDarkSurface = dark,
        forceHeroSurface = collection.isArtist() || immersiveAlbum,
        modifier = modifier,
    )
}

@Composable
internal fun AlbumInfoBackground(collection: MusicCollection, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = rememberPlayerMonetPalette(collection.artwork)
    val base = Color(palette.a2.tone(14))
    if (!LocalAdvancedMaterialEnabled.current) {
        Box(modifier.background(base))
        return
    }
    val bgRequest = remember(collection.artwork, context) {
        if (collection.artwork.isBlank()) null
        else {
            val placeholderKey = findCachedArtworkMemoryEntry(SingletonImageLoader.get(context), collection.artwork)?.key
            ImageRequest.Builder(context)
                .data(collection.artwork)
                .size(512)
                .precision(Precision.INEXACT)
                .apply { if (placeholderKey != null) placeholderMemoryCacheKey(placeholderKey) }
                .build()
        }
    }
    Box(modifier.clipToBounds().background(base)) {
        if (bgRequest != null) AsyncImage(
            model = bgRequest,
            contentDescription = null,
            modifier = Modifier.matchParentSize()
                .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                .blur(48.dp),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.matchParentSize().background(base.copy(alpha = .72f)))
    }
}

@Composable
internal fun CatalogDetailPage(
    model: AuraViewModel,
    padding: PaddingValues,
    collection: MusicCollection,
    cloud: CloudPlaylistContent?,
    destinationId: Long = 0L,
    open: (String) -> Unit = {},
    nowPlayingConfig: NowPlayingConfig = NowPlayingConfig(),
    motionVideoPath: String? = null,
    artistLogoUrl: String? = null,
    artistMotionVideoPath: String? = null,
) {
    val artist = collection.isArtist()
    val immersiveAlbum = shouldShowImmersiveAlbumHero(artist, nowPlayingConfig.albumAnimatedCover, motionVideoPath)
    val hasImmersiveHero = artist || immersiveAlbum
    val openSongActions = LocalSongActions.current
    val songActionsBackdrop = LocalToolbarBackdrop.current
    val tracks = cloud?.songs ?: collection.songs
    val info = cloud?.catalog
    val albumArtist = remember(info?.artist, collection.subtitle, tracks) {
        resolveAlbumArtist(info?.artist, collection.subtitle, tracks)
    }
    val systemDark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val tokens = rememberCatalogPlayerThemeTokens(
        artwork = collection.artwork,
        config = nowPlayingConfig,
        fallbackDarkSurface = systemDark,
        forceHeroSurface = hasImmersiveHero,
    )
    val foreground = tokens.foreground
    val muted = tokens.muted
    val accent = tokens.accent
    val onAccent = tokens.onAccent
    val heroCardFill = tokens.cardFill
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val margin = if (hasImmersiveHero) LocalAuraLayout.current.pageMargin else (configuration.screenWidthDp * .058f * 1.15f).dp
    val related = info?.albums ?: model.songs.filter { song -> tracks.any { it.artistId == song.artistId } }
        .groupBy { it.albumId }.values.map { album -> val first = album.first()
            CatalogAlbum(first.albumId, first.album, first.artwork, first.artistId, first.artist, count = album.size) }
        .filterNot { !artist && it.id == collection.key.substringAfterLast(':').toLongOrNull() }
    var descriptionOpen by remember(collection.key) { mutableStateOf(false) }
    val context = LocalContext.current
    val favoritePrefs = remember { context.getSharedPreferences("aura.catalog.favorites", android.content.Context.MODE_PRIVATE) }
    var artistFavorite by remember(collection.key) { mutableStateOf(favoritePrefs.getBoolean(collection.key, false)) }
    fun openAlbum(album: CatalogAlbum) { model.openSongAlbum(Song(0, "", album.artist, album.title, album.id, album.artistId, uri = "", artwork = album.artwork, cloud = collection.key.startsWith("cloud:"))) }
    LazyColumn(pageContentModifier(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (artist) item("hero") {
            val heroHeight = (LocalAuraLayout.current.width.dp / .95f).coerceIn(360.dp, 500.dp) * ArtistHeroHeightScale
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                val heroBackdrop = rememberMiuixLayerBackdrop { drawContent() }
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black,
                                    .58f to Color.Black,
                                    .86f to Color.Black.copy(alpha = .36f),
                                    1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    Box(Modifier.matchParentSize().miuixLayerBackdrop(heroBackdrop)) {
                        if (collection.artwork.isNotBlank()) AsyncImage(collection.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (nowPlayingConfig.artistAnimatedCover && !artistMotionVideoPath.isNullOrBlank()) {
                            AnimatedMotionCoverVideo(
                                videoPath = artistMotionVideoPath,
                                playing = true,
                                visibleAlpha = 1f,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    // Keep the upper hero crisp; apply MIUIX bottom progressive blur + NowPlaying gradient mix.
                    if (LocalAdvancedMaterialEnabled.current) {
                        Box(Modifier.matchParentSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .progressiveTextureBlur(
                                backdrop = heroBackdrop,
                                shape = RectangleShape,
                                blurRadius = 3.8f,
                                gradient = ProgressiveBlur.Bottom,
                            ).drawWithContent {
                                drawContent()
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        .52f to Color.Transparent,
                                        .92f to Color.White,
                                        1f to Color.White,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            })
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    .46f to Color.Transparent,
                                    .72f to tokens.heroMixTint.copy(alpha = .34f),
                                    .96f to tokens.surface.copy(alpha = .74f),
                                    1f to tokens.surface.copy(alpha = .92f),
                                ),
                            ),
                    )
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = margin, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val effectiveLogoUrl = if (nowPlayingConfig.artistWordmarkLogo) artistLogoUrl else null
                    var logoLoaded by remember(effectiveLogoUrl) { mutableStateOf(false) }
                    var logoFailed by remember(effectiveLogoUrl) { mutableStateOf(false) }
                    val showLogo = !effectiveLogoUrl.isNullOrBlank() && !logoFailed
                    Box(contentAlignment = Alignment.Center) {
                        if (!showLogo || !logoLoaded) {
                            Text(collection.title, color = foreground, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, maxLines = 2)
                        }
                        if (showLogo) {
                            AsyncImage(
                                model = effectiveLogoUrl,
                                contentDescription = collection.title,
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .heightIn(max = 76.dp)
                                    .alpha(if (logoLoaded) 1f else 0f),
                                contentScale = ContentScale.Fit,
                                onSuccess = { logoLoaded = true },
                                onError = { logoFailed = true },
                            )
                        }
                    }
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        IconButton({ descriptionOpen = true }, Modifier.size(42.dp).background(heroCardFill, CircleShape)) { Text("i", color = foreground) }
                        IconButton({ model.playCollection(tracks, content = cloud) }, Modifier.size(60.dp).background(accent, CircleShape), enabled = tracks.isNotEmpty()) {
                            Image(painterResource(R.drawable.ic_player_play), tr("play", "播放"), Modifier.size(32.dp), colorFilter = ColorFilter.tint(onAccent))
                        }
                        IconButton({ artistFavorite = !artistFavorite; favoritePrefs.edit().putBoolean(collection.key, artistFavorite).apply() },
                            Modifier.size(42.dp).background(heroCardFill, CircleShape)) {
                            Image(painterResource(if (artistFavorite) R.drawable.ic_player_favorite_on else R.drawable.ic_player_favorite),
                                tr("favorite_artist", "收藏艺术家"), Modifier.size(24.dp), colorFilter = ColorFilter.tint(foreground))
                        }
                    }
                }
            }
        } else if (immersiveAlbum && motionVideoPath != null) item("album_immersive_hero") {
            val heroHeight = (LocalAuraLayout.current.width.dp * 1.12f).coerceIn(360.dp, 540.dp)
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                val heroBackdrop = rememberMiuixLayerBackdrop { drawContent() }
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black,
                                    .58f to Color.Black,
                                    .88f to Color.Black.copy(alpha = .32f),
                                    1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    Box(Modifier.matchParentSize().miuixLayerBackdrop(heroBackdrop)) {
                        if (collection.artwork.isNotBlank()) AsyncImage(collection.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        AnimatedMotionCoverVideo(
                            videoPath = motionVideoPath,
                            playing = true,
                            visibleAlpha = 1f,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (LocalAdvancedMaterialEnabled.current) {
                        Box(Modifier.matchParentSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .progressiveTextureBlur(
                                backdrop = heroBackdrop,
                                shape = RectangleShape,
                                blurRadius = 3.8f,
                                gradient = ProgressiveBlur.Bottom,
                            ).drawWithContent {
                                drawContent()
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        .52f to Color.Transparent,
                                        .92f to Color.White,
                                        1f to Color.White,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            })
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    .46f to Color.Transparent,
                                    .72f to tokens.heroMixTint.copy(alpha = .36f),
                                    .96f to tokens.surface.copy(alpha = .76f),
                                    1f to tokens.surface.copy(alpha = .92f),
                                ),
                            ),
                    )
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = margin, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(collection.title, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, color = foreground, maxLines = 2)
                        Text(albumArtist, color = muted,
                            modifier = Modifier.padding(top = 4.dp).clickable { tracks.firstOrNull()?.let(model::openSongArtist) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(42.dp).clip(CircleShape).clickable { if (destinationId > 0) open("albumInfo:$destinationId") }
                        .background(heroCardFill), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_album_info), tr("album_details", "专辑详情"), Modifier.size(26.dp),
                            colorFilter = ColorFilter.tint(foreground))
                    }
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.height(42.dp).clip(RoundedCornerShape(999.dp)).clickable(enabled = tracks.isNotEmpty()) {
                        model.playCollection(tracks, content = cloud)
                    }.background(accent).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_player_play), tr("play", "播放"), Modifier.size(18.dp),
                                colorFilter = ColorFilter.tint(onAccent))
                            Spacer(Modifier.width(5.dp))
                            Text(tr("play", "播放"), color = onAccent)
                        }
                    }
                }
            }
        } else item("album_header") {
            Column(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding(), start = margin, end = margin)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Artwork(collection.artwork, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f)) }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(collection.title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = foreground, maxLines = 2)
                        Text(albumArtist, color = muted,
                            modifier = Modifier.padding(top = 4.dp).clickable { tracks.firstOrNull()?.let(model::openSongArtist) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(42.dp).clip(CircleShape).clickable { if (destinationId > 0) open("albumInfo:$destinationId") }
                        .background(foreground.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_album_info), tr("album_details", "专辑详情"), Modifier.size(26.dp),
                            colorFilter = ColorFilter.tint(foreground))
                    }
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.height(42.dp).clip(RoundedCornerShape(999.dp)).clickable(enabled = tracks.isNotEmpty()) {
                        model.playCollection(tracks, content = cloud)
                    }.background(accent).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_player_play), tr("play", "播放"), Modifier.size(18.dp),
                                colorFilter = ColorFilter.tint(onAccent))
                            Spacer(Modifier.width(5.dp))
                            Text(tr("play", "播放"), color = onAccent)
                        }
                    }
                }
            }
        }
        if (artist && related.isNotEmpty()) item("new_release") {
            val release = related.maxByOrNull { it.published }!!
            Row(Modifier.padding(horizontal = margin, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(heroCardFill).clickable { openAlbum(release) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(release.artwork, Modifier.size(74.dp), cornerRadius = 20.dp - 12.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(if (release.published > 0) dateText(release.published) else tr("latest_release", "最新发行"), color = muted, fontSize = 11.sp)
                    Text(release.title, color = foreground, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (release.count > 0) Text("${release.count} ${tr("song_count", "首歌曲")}", color = muted, fontSize = 11.sp)
                }
                Text("+", color = foreground, fontSize = 24.sp)
            }
        }
        if (artist) item("top_songs") {
            CatalogSectionLink(tr("top_songs", "热门歌曲"), margin, foreground) { open("artistSongs:$destinationId") }
            val popular = tracks.take(15)
            val columnCount = if (popular.size < 5) minOf(1, popular.size) else 3
            val rowHeight = 48.dp * 1.35f * .92f * .93f
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cardWidth = artistTopSongCardWidth(maxWidth, margin, columnCount)
                LazyRow(contentPadding = PaddingValues(horizontal = margin), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(columnCount) { column ->
                        Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (index in column until popular.size step columnCount) {
                                val song = popular[index]
                                val albumYearSubtitle = formatArtistSongAlbumYear(song)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = rowHeight)
                                        .clickable { model.playCollection(tracks, index, cloud) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Artwork(song.artwork, Modifier.size(40.dp * 1.35f * .88f * .93f), cornerRadius = ListArtworkCornerRadius)
                                    Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)) {
                                        Text(
                                            song.title,
                                            color = if (model.current?.key == song.key) accent else foreground,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (albumYearSubtitle.isNotEmpty()) {
                                            Text(
                                                albumYearSubtitle,
                                                color = muted,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { openSongActions(SongActionRequest(song, songActionsBackdrop)) },
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Image(
                                            AuraMoreHorizontalIcon,
                                            tr("more", "更多"),
                                            Modifier.size(20.dp),
                                            colorFilter = ColorFilter.tint(muted),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (cloud != null && tracks.isEmpty() && (cloud.loading || cloud.failed))
                CloudPagingFooter(cloud.loading, cloud.failed) { model.loadMoreCloudSongsFor(cloud) }
        }
        itemsIndexed(if (artist) emptyList() else tracks, key = { _, song -> song.key }) { index, song ->
            Column(Modifier.padding(horizontal = margin)) {
                HorizontalDivider(color = foreground.copy(alpha = .12f))
                // Keep the 44 dp action; apply the successive height reductions to the whole row.
                val trackPadding = if (artist) 8.dp else AlbumTrackRowVerticalPadding
                val showTrackArtist = !artist && shouldShowAlbumTrackArtist(song, albumArtist)
                Row(Modifier.fillMaxWidth().clickable { model.playCollection(tracks, index, cloud) }.padding(vertical = trackPadding), verticalAlignment = Alignment.CenterVertically) {
                    if (artist) Artwork(song.artwork, Modifier.size(42.dp), cornerRadius = ListArtworkCornerRadius)
                    else Text("${index + 1}", color = muted, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f).padding(start = if (artist) 12.dp else 0.dp)) {
                        Text(song.title, color = if (model.current?.key == song.key) accent else foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (artist) Text(formatArtistSongAlbumYear(song),
                            color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else if (showTrackArtist) Text(song.artist.trim(), color = foreground.copy(alpha = .56f), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                    }
                    IconButton(onClick = { openSongActions(SongActionRequest(song, songActionsBackdrop)) }, modifier = Modifier.size(44.dp)) {
                        Image(AuraMoreHorizontalIcon, tr("more", "更多"), Modifier.size(20.dp), colorFilter = ColorFilter.tint(muted))
                    }
                }
            }
        }
        if (!artist && cloud != null && (cloud.loading || cloud.failed || cloud.hasMore)) item("catalog_paging") {
            CloudPagingFooter(cloud.loading, cloud.failed) { model.loadMoreCloudSongsFor(cloud) }
        }
        if (!artist) item("release_info") {
            val dateLabel = when {
                info != null && info.published > 0 -> dateText(info.published)
                else -> tracks.firstOrNull()?.year?.takeIf { it > 0 }?.toString()
            }
            val summaryText = formatAlbumReleaseSummary(dateLabel, formatAlbumTotalDuration(tracks))
            if (summaryText != null || !info?.company.isNullOrBlank()) {
                Column(Modifier.padding(horizontal = margin, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summaryText?.let { Text(it, color = muted, fontSize = 13.sp) }
                    info?.company?.takeIf { it.isNotBlank() }?.let { Text(tr("record_label", "发行公司") + ": $it", color = muted, fontSize = 13.sp) }
                }
            }
        }
        if (artist) item("artist_albums") {
            CatalogSectionLink(tr("albums", "专辑"), margin, foreground) { open("artistAlbums:$destinationId") }
            val albumWidth = (configuration.screenWidthDp.dp - margin * 2 - 24.dp) / 3
            LazyRow(contentPadding = PaddingValues(horizontal = margin), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(related, key = { it.id }) { album ->
                    CollectionTile(album.title, album.artist, album.artwork, Modifier.width(albumWidth), foreground = foreground) {
                        openAlbum(album)
                    }
                }
            }
        }
        if (!artist && related.isNotEmpty()) item("related_albums") {
            Column(Modifier.padding(horizontal = margin, vertical = 12.dp).clip(RoundedCornerShape(24.dp))
                .background(foreground.copy(alpha = .07f)).padding(vertical = 18.dp)) {
                Text(if (artist) tr("albums", "专辑") else tr("more_by_artist", "更多该艺术家的作品"), color = foreground, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
                    items(related, key = { it.id }) { album ->
                        Column(Modifier.width(140.dp).clickable { openAlbum(album) }) {
                            Artwork(album.artwork, Modifier.size(140.dp))
                            Text(album.title, color = foreground, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                        }
                    }
                }
            }
        }
    }
    WindowDialog(descriptionOpen, title = collection.title, summary = info?.description?.takeIf { it.isNotBlank() } ?: tr("artist_info_unavailable", "暂无艺术家简介"), onDismissRequest = { descriptionOpen = false }) {
        GlassDialogButton({ descriptionOpen = false }, Modifier.fillMaxWidth()) { Text(tr("done", "完成")) }
    }
}

@Composable
internal fun AlbumInfoPage(model: AuraViewModel, padding: PaddingValues, collection: MusicCollection, cloud: CloudPlaylistContent?) {
    val tracks = cloud?.songs ?: collection.songs
    val info = cloud?.catalog
    val albumArtist = remember(info?.artist, collection.subtitle, tracks) {
        resolveAlbumArtist(info?.artist, collection.subtitle, tracks)
    }
    val foreground = Color.White
    val muted = foreground.copy(alpha = .72f)
    var confirmDownload by remember(collection.key) { mutableStateOf(false) }
    val context = LocalContext.current
    val title = collection.title
    val artist = info?.artist?.ifBlank { collection.subtitle } ?: tracks.firstOrNull()?.artist.orEmpty()
    LazyColumn(pageContentModifier(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding(), start = 28.dp, end = 28.dp)) {
        item {
            Box(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding())) {
                // Sample only the cover, never the parent content layer containing this button.
                // This avoids a backdrop read/write cycle inside the scrolling page.
                val coverBackdrop = rememberLayerBackdrop { drawContent() }
                Artwork(collection.artwork, Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp)).layerBackdrop(coverBackdrop))
                Box(Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
                    GlassToolbarIconButton(
                        icon = MiuixIcons.Regular.Download,
                        description = tr("download_album_artwork", "下载专辑封面"),
                        backdrop = coverBackdrop,
                        collapsedFraction = 1f,
                        containerPadding = PaddingValues(0.dp),
                        surfaceAlpha = .24f,
                    ) { confirmDownload = true }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = foreground, style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold)
                Text(artist, color = muted, style = MiuixTheme.textStyles.body1)
                Spacer(Modifier.height(16.dp))
                info?.published?.takeIf { it > 0 }?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(dateText(it), color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(tr("release_time", "发行时间"), color = muted, fontSize = 14.sp)
                    }
                }
                info?.company?.takeIf { it.isNotBlank() }?.let {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(it, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(tr("record_label", "发行公司"), color = muted, fontSize = 14.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(top = 22.dp), color = foreground.copy(alpha = .38f))
                info?.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = foreground, fontSize = 15.sp, lineHeight = 25.sp,
                        modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
    WindowDialog(confirmDownload, title = tr("download_album_artwork", "即将下载专辑封面"),
        titleTextAlign = androidx.compose.ui.text.style.TextAlign.Start,
        summary = "$title · $artist", summaryTextAlign = androidx.compose.ui.text.style.TextAlign.Start,
        onDismissRequest = { confirmDownload = false }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ confirmDownload = false }, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
            GlassDialogButton({
                confirmDownload = false
                val url = collection.artwork
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    runCatching {
                        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                            .setTitle(title).setDescription(artist)
                            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_PICTURES, "Aura Player/$title.jpg")
                        context.getSystemService(android.app.DownloadManager::class.java).enqueue(request)
                    }
                }
            }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("download", "下载")) }
        }
    }
}
private fun dateText(value: Long) = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(value))


@Composable
private fun CatalogSectionLink(title: String, margin: Dp, foreground: Color, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = margin, vertical = 16.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = foreground, fontWeight = FontWeight.SemiBold, style = MiuixTheme.textStyles.title3)
        Image(MiuixIcons.Regular.ChevronForward, title, Modifier.padding(start = 4.dp).size(18.dp),
            colorFilter = ColorFilter.tint(foreground.copy(alpha = .66f)))
    }
}

@Composable
internal fun ArtistContentsPage(model: AuraViewModel, padding: PaddingValues, collection: MusicCollection, cloud: CloudPlaylistContent?, albums: Boolean) {
    if (!albums) {
        val tracks = cloud?.songs ?: collection.songs
        LazyColumn(pageContentModifier(), contentPadding = padding) {
            itemsIndexed(tracks, key = { _, it -> it.key }) { index, song -> SongRow(song, model, { model.playCollection(tracks, index, cloud) }) }
            if (cloud != null && (cloud.loading || cloud.failed || cloud.hasMore)) item("more") {
                CloudPagingFooter(cloud.loading, cloud.failed) { model.loadMoreCloudSongsFor(cloud) }
            }
        }
    } else {
        val rows = cloud?.albums ?: collection.songs.groupBy { "${it.volume}:${it.albumId}:${it.album}" }.values.map {
            val song = it.first(); CatalogAlbum(song.albumId, song.album, song.artwork, song.artistId, song.artist, count = it.size) }
        LaunchedEffect(cloud) { if (cloud != null && cloud.albums.isEmpty()) model.loadMoreArtistAlbums(cloud) }
        LazyColumn(pageContentModifier(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(rows.chunked(2), key = { it.first().id }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { album -> CollectionTile(album.title, album.artist, album.artwork, Modifier.weight(1f)) {
                        if (cloud != null) model.openCatalogAlbum(album)
                        else collection.songs.firstOrNull { it.albumId == album.id }?.let(model::openSongAlbum)
                    } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (cloud != null && (cloud.albumsLoading || cloud.albumsFailed || cloud.albumsHasMore)) item("more") {
                CloudPagingFooter(cloud.albumsLoading, cloud.albumsFailed) { model.loadMoreArtistAlbums(cloud) }
            }
        }
    }
}
