// Ported from CurrentMusicX (feature/home/DailyRecommendationHero.kt), GNU AGPL-3.0.
// Only the cover-flow scene is carried over: the hero layout around it is ours.
package com.yunyin.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import com.yunyin.music.ui.components.shelf.coverRequestUrl
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import com.yunyin.music.ui.components.shelf.ShelfSong
import com.yunyin.music.ui.theme.AppTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.math.roundToInt

/** Vinyl spin angle; the reference reads it from the player. */
val LocalPlayerArtworkRotation = staticCompositionLocalOf { 0f }

private typealias Song = ShelfSong
@Composable fun RecommendationCoverFlow(
    songs: List<ShelfSong>, selected: Int, onSelect: (Int) -> Unit, showRecord: Boolean,
    onPlay: () -> Unit, canPlay: Boolean, playLabel: String,
) {
    val select by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    var targetPosition by remember { mutableFloatStateOf(selected.toFloat()) }
    val position = remember { Animatable(selected.toFloat()) }
    var release by remember { mutableIntStateOf(0) }
    var releaseVelocity by remember { mutableStateOf<Float?>(null) }
    var dragPosition by remember { mutableFloatStateOf(selected.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val renderedPosition = remember { derivedStateOf { if (dragging) dragPosition else position.value } }
    // Only crossing the midpoint changes composition/draw order. All per-frame movement is in graphicsLayer.
    val foreground = remember { derivedStateOf { renderedPosition.value.roundToInt() } }
    LaunchedEffect(selected, release) {
        val from = Math.floorMod(targetPosition.roundToInt(), songs.size)
        val difference = selected - from
        val delta = when {
            difference > songs.size / 2 -> difference - songs.size
            difference < -songs.size / 2 -> difference + songs.size
            else -> difference
        }
        targetPosition += delta
        if (!dragging) {
            val velocity = releaseVelocity ?: position.velocity
            releaseVelocity = null
            position.animateTo(targetPosition, spring(dampingRatio = .94f, stiffness = 135f, visibilityThreshold = .001f),
                initialVelocity = velocity)
        }
    }
    val recordReveal = animateFloatAsState(if (showRecord) 1f else 0f,
        spring(dampingRatio = .94f, stiffness = 115f, visibilityThreshold = .001f), label = "recommendation record")
    val rotationStart = LocalPlayerArtworkRotation.current
    val rotation = remember { mutableFloatStateOf(rotationStart) }
    val density = LocalDensity.current
    val accent = AppTheme.palette.accent
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home_daily_deck")
        .semantics {
            stateDescription = "第 ${selected + 1} 首，共 ${songs.size} 首"
            customActions = listOf(
                CustomAccessibilityAction("上一首推荐") { if (songs.size > 1) { onSelect(Math.floorMod(selected - 1, songs.size)); true } else false },
                CustomAccessibilityAction("下一首推荐") { if (songs.size > 1) { onSelect((selected + 1) % songs.size); true } else false })
        }, contentAlignment = Alignment.Center) {
        val coverSize = (maxWidth / 2.38f).coerceIn(128.dp, 184.dp)
        val gestureDistance = with(density) { (coverSize * .65f).toPx() }
        val stage = Modifier.fillMaxWidth().height(coverSize + 40.dp)
            .pointerInput(songs.map { it.id }, gestureDistance) {
                var start = 0f
                var pulled = 0f
                var nearest = selected
                val velocityTracker = VelocityTracker()
                fun finish(cancelled: Boolean) {
                    val released = dragPosition
                    val velocity = if (cancelled || songs.size <= 1) 0f else -velocityTracker.calculateVelocity().x / gestureDistance
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        position.snapTo(released)
                        targetPosition = released.roundToInt().toFloat()
                        if (!cancelled) {
                            // Preserve release speed, with at most one additional card of momentum.
                            targetPosition = (released + velocity * .12f).coerceIn(released - 1f, released + 1f).roundToInt().toFloat()
                            select(Math.floorMod(targetPosition.roundToInt(), songs.size))
                        }
                        releaseVelocity = velocity
                        dragging = false
                        release++
                    }
                }
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = renderedPosition.value; pulled = 0f; dragPosition = start; dragging = true
                        nearest = start.roundToInt()
                        velocityTracker.resetTracking()
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { position.stop() }
                    },
                    onDragCancel = { finish(true) }, onDragEnd = { finish(false) },
                ) { change, amount ->
                    change.consume()
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    pulled += amount
                    if (songs.size > 1) {
                        dragPosition = start - pulled / gestureDistance
                        val next = dragPosition.roundToInt()
                        if (next != nearest) {
                            nearest = next
                            select(Math.floorMod(next, songs.size))
                        }
                    }
                }
            }
        Box(stage, contentAlignment = Alignment.Center) {
            val visible = minOf(songs.size, 7)
            val centre = foreground.value
            for (slot in centre - (visible - 1) / 2..centre + visible / 2) key(slot) {
                val index = Math.floorMod(slot, songs.size)
                Box(Modifier.size(coverSize).zIndex(10f - abs(slot - centre)).graphicsLayer {
                    val relative = slot - renderedPosition.value
                    val depth = abs(relative)
                    val scale = exp(-depth * .20f)
                    translationX = tanh(relative * .70f) * coverSize.toPx() * .98f
                    translationY = (1f - scale) * coverSize.toPx() * .42f
                    scaleX = scale; scaleY = scale
                    rotationZ = tanh(relative * .55f) * 12f
                    alpha = (1f - (depth - 1f).coerceAtLeast(0f) * .14f).coerceIn(.35f, 1f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }) {
                    if (index == selected) VinylRecord(accent, songs[index].cover,
                        Modifier.size(coverSize * .9f).align(Alignment.Center).graphicsLayer {
                            translationX = coverSize.toPx() * .52f * recordReveal.value
                            rotationZ = rotation.floatValue
                        }.testTag("home_daily_record"))
                    val artwork = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                        .testTag("home_daily_cover_${songs[index].id}").semantics { contentDescription = songs[index].name }
                        .clickable(enabled = index != selected || canPlay, role = Role.Button,
                            onClickLabel = if (index == selected) playLabel else "浏览${songs[index].name}") {
                            if (index == selected) onPlay() else onSelect(index)
                        }
                    DeckArtwork(songs[index].cover, artwork)
                }
            }
        }
    }
}

@Composable private fun DeckArtwork(cover: String, modifier: Modifier) {
    val context = LocalContext.current
    val model = remember(context, cover) { ImageRequest.Builder(context).data(coverRequestUrl(cover, 480)).size(480).build() }
    Box(modifier.background(AppTheme.palette.tertiaryBackground), contentAlignment = Alignment.Center) {
        if (cover.isNotBlank()) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable private fun VinylRecord(accent: Color, cover: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Color(0xFF34363A), Color(0xFF121315), Color(0xFF292B2F)), radius = radius))
        for (ring in 0..11) drawCircle(Color.White.copy(alpha = if (ring % 3 == 0) .12f else .045f),
            radius * (.44f + ring * .042f), style = Stroke(.7.dp.toPx()))
        drawArc(Color.White.copy(alpha = .13f), 210f, 48f, false,
            topLeft = Offset(radius * .16f, radius * .16f), size = size * .84f, style = Stroke(2.dp.toPx()))
        drawCircle(accent, radius * .26f)
        drawCircle(Color(0xFF101113), radius * .055f)
    }
    DeckArtwork(cover, Modifier.fillMaxSize(.26f).clip(CircleShape))
    Canvas(Modifier.fillMaxSize(.05f)) { drawCircle(Color(0xFF101113)) }
    }
}
