package io.github.proify.lyricon.app.activity.lyric.immersive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.ceil

internal const val SONG_MARQUEE_PAUSE_MS = 5000L

internal fun songMarqueeOffset(cycle: Float, distance: Float): Float =
    if (cycle <= 0 || distance >= cycle) 0f else -distance.coerceAtLeast(0f)

internal fun songMarqueeDuration(distance: Float, pixelsPerSecond: Float): Int =
    ceil(distance.coerceAtLeast(0f) / pixelsPerSecond.coerceAtLeast(1f) * 1000).toInt().coerceAtLeast(1)

@Composable
internal fun SynchronizedSongHeader(title: String, artist: String, font: FontFamily, color: Color, active: Boolean,
    modifier: Modifier = Modifier, titleSize: Int = 22, artistSize: Int = 14, onTitleClick: (() -> Unit)? = null) {
    BoxWithConstraints(modifier.testTag("song_header")) {
        val density = LocalDensity.current
        val viewport = with(density) { maxWidth.toPx() }
        val gap = with(density) { 32.dp.toPx() }
        var titleWidth by remember(title, font, density, titleSize) { mutableFloatStateOf(0f) }
        var artistWidth by remember(artist, font, density, artistSize) { mutableFloatStateOf(0f) }
        val titleCycle = if (titleWidth > viewport + 1f) titleWidth + gap else 0f
        val artistCycle = if (artistWidth > viewport + 1f) artistWidth + gap else 0f
        val distance = remember { Animatable(0f) }
        val longest = maxOf(titleCycle, artistCycle)
        val speed = with(density) { 30f * this.density }
        LaunchedEffect(title, artist, longest, speed, active, titleSize, artistSize) {
            distance.snapTo(0f)
            if (!active || longest <= 0) return@LaunchedEffect
            while (true) {
                distance.animateTo(longest, tween(songMarqueeDuration(longest, speed), easing = LinearEasing))
                delay(SONG_MARQUEE_PAUSE_MS)
                distance.snapTo(0f)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            SongMarqueeLine(title, font, color.copy(alpha = .96f), titleSize, FontWeight.ExtraBold,
                titleCycle, { distance.value }, "song_title_marquee", onTitleClick) { titleWidth = it }
            SongMarqueeLine(artist, font, color.copy(alpha = .72f), artistSize, FontWeight.Bold,
                artistCycle, { distance.value }, "song_artist_marquee", null) { artistWidth = it }
        }
    }
}

@Composable
private fun SongMarqueeLine(text: String, font: FontFamily, color: Color, size: Int, weight: FontWeight,
    cycle: Float, distance: () -> Float, tag: String, onClick: (() -> Unit)?, measured: (Float) -> Unit) {
    Box(Modifier.fillMaxWidth().clipToBounds().then(if (onClick != null)
        Modifier.clickable(role = Role.Button, onClickLabel = "打开 Lyrico", onClick = onClick) else Modifier)) {
        Text(text, color = color, fontSize = size.sp, fontWeight = weight, fontFamily = font,
            maxLines = 1, softWrap = false, onTextLayout = { measured(it.size.width.toFloat()) },
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = songMarqueeOffset(cycle, distance()) }.testTag(tag))
        if (cycle > 0) Text(text, color = color, fontSize = size.sp, fontWeight = weight, fontFamily = font,
            maxLines = 1, softWrap = false,
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer {
                    translationX = cycle - distance().coerceIn(0f, cycle)
                    alpha = if (distance() < cycle) 1f else 0f
                }.clearAndSetSemantics {})
    }
}
