package io.github.proify.lyricon.app.activity.lyric.immersive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.ceil

internal const val SONG_MARQUEE_PAUSE_MS = 5000L

internal fun songMarqueeOffset(overflow: Float, distance: Float): Float = -distance.coerceIn(0f, overflow.coerceAtLeast(0f))

internal fun songMarqueeDuration(distance: Float, pixelsPerSecond: Float): Int =
    ceil(distance.coerceAtLeast(0f) / pixelsPerSecond.coerceAtLeast(1f) * 1000).toInt().coerceAtLeast(1)

@Composable
internal fun SynchronizedSongHeader(title: String, artist: String, font: FontFamily, color: Color, active: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.testTag("song_header")) {
        val density = LocalDensity.current
        val viewport = with(density) { maxWidth.toPx() }
        var titleWidth by remember(title, font, density) { mutableFloatStateOf(0f) }
        var artistWidth by remember(artist, font, density) { mutableFloatStateOf(0f) }
        val titleOverflow = (titleWidth - viewport).coerceAtLeast(0f)
        val artistOverflow = (artistWidth - viewport).coerceAtLeast(0f)
        val distance = remember { Animatable(0f) }
        val longest = maxOf(titleOverflow, artistOverflow)
        val speed = with(density) { 30f * this.density }
        LaunchedEffect(title, artist, longest, speed, active) {
            distance.snapTo(0f)
            if (!active || longest <= 0) return@LaunchedEffect
            while (true) {
                distance.animateTo(longest, tween(songMarqueeDuration(longest, speed), easing = LinearEasing))
                delay(SONG_MARQUEE_PAUSE_MS)
                distance.snapTo(0f)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                Text(title, color = color.copy(alpha = .96f), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = font, maxLines = 1, softWrap = false,
                    onTextLayout = { titleWidth = it.size.width.toFloat() },
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                        .graphicsLayer { translationX = songMarqueeOffset(titleOverflow, distance.value) }.testTag("song_title_marquee"))
            }
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                Text(artist, color = color.copy(alpha = .72f), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    fontFamily = font, maxLines = 1, softWrap = false,
                    onTextLayout = { artistWidth = it.size.width.toFloat() },
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                        .graphicsLayer { translationX = songMarqueeOffset(artistOverflow, distance.value) }.testTag("song_artist_marquee"))
            }
        }
    }
}
