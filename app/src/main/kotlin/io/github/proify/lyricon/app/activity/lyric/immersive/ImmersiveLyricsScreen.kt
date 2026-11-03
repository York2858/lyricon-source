package io.github.proify.lyricon.app.activity.lyric.immersive

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import io.github.proify.lyricon.app.R
import androidx.compose.ui.semantics.stateDescription
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.*
import java.io.File

@Composable
internal fun ImmersiveLyricsScreen(state: ImmersiveLyricsState) {
    val context = LocalContext.current
    LaunchedEffect(state.playbackError) {
        state.playbackError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            state.clearPlaybackError()
        }
    }
    val manager = remember(context) { SettingsManager.getInstance(context) }
    val preferences by manager.values.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val view = LocalView.current
    val keepScreenOn = preferences.flag("keep_screen_on", false)
    DisposableEffect(view, lifecycle, keepScreenOn) {
        val previous = view.keepScreenOn
        val observer = LifecycleEventObserver { _, _ ->
            visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            view.keepScreenOn = visible && keepScreenOn
        }
        lifecycle.addObserver(observer)
        view.keepScreenOn = visible && keepScreenOn
        onDispose { lifecycle.removeObserver(observer); view.keepScreenOn = previous }
    }
    val activity = context as? Activity
    val fullscreen = preferences.flag("fullscreen", false)
    DisposableEffect(activity, fullscreen, visible) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen && visible) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } else controller?.show(WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
    val hdr = preferences.flag("hdr", false)
    val hdrRatio = preferences.number("hdr_brightness", 200) / 100f
    DisposableEffect(activity, hdr, hdrRatio, visible) {
        if (activity != null) LyricHdrWindow.apply(activity, hdr && visible, hdrRatio)
        onDispose { if (activity != null) LyricHdrWindow.release(activity) }
    }
    SideEffect {
        activity?.window?.let {
            androidx.core.view.WindowInsetsControllerCompat(it, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    val fontWeight = preferences.number("font_weight", 800).coerceIn(100, 900)
    val originalFont = rememberHalcyonFont(preferences["original_font"] as? String, fontWeight)
    val translationFont = rememberHalcyonFont(preferences["translation_font"] as? String, fontWeight)
    val lyrics = remember(state.song) { state.song?.lyrics.orEmpty().toHalcyonLyrics() }
    val position = state.currentPosition()
    val offset = preferences.number("offset", 0).toLong()
    val index = lyrics.indexOfLast { it.timeMs <= position - offset }.coerceAtLeast(0)
    val palette = remember(state.cover) { PlayerPalette.from(state.cover).withCoverContentColor() }
    val residentPreferences = rememberAppleMusicLyricsViewPreferences(manager)
    val livePosition = remember(state, offset) { { (state.currentPosition() - offset).coerceAtLeast(0L) } }
    CompositionLocalProvider(
        LocalPlayerSurfaceActive provides visible,
        LocalPlayerLyricPositionProvider provides livePosition,
        LocalAppleMusicLyricsViewPreferences provides residentPreferences
    ) {
        Box(Modifier.fillMaxSize().background(palette.middle)) {
            HalcyonBackdrop(state.cover, palette, preferences, state.playing && visible, position)
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .1f))
                        .clickable(onClickLabel = "打开音乐解读") { context.startActivity(state.explanationIntent(context)) }
                        .semantics { contentDescription = "打开音乐解读" }, contentAlignment = Alignment.Center) {
                        state.cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Text("♪", color = palette.onBackground, fontSize = 28.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    SynchronizedSongHeader(state.title.ifBlank { "沉浸歌词" }, state.artist.ifBlank { "词幕" },
                        originalFont, palette.onBackground, visible, Modifier.weight(1f),
                        preferences.number("title_size", 22).coerceIn(12, 36), preferences.number("artist_size", 14).coerceIn(10, 28),
                        onTitleClick = state::openMusicTags)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = state::toggleFavorite, enabled = state.canFavorite && !state.favoriteBusy,
                        modifier = Modifier.size(40.dp).semantics {
                            contentDescription = if (state.favorite) "取消喜爱" else "喜爱"
                            stateDescription = if (state.favorite) "已喜爱" else "未喜爱"
                        }) {
                        Icon(painterResource(if (state.favorite) R.drawable.halcyon_favorite_filled else R.drawable.halcyon_favorite),
                            null, Modifier.size(25.dp), tint = palette.onBackground.copy(alpha = if (state.favorite) 1f else .92f))
                    }
                    IconButton(onClick = {
                        context.startActivity(android.content.Intent(context, ImmersiveSettingsActivity::class.java))
                    }, modifier = Modifier.size(40.dp).semantics { contentDescription = "歌词设置" }) {
                        Canvas(Modifier.size(24.dp)) {
                            repeat(3) { dot -> drawCircle(palette.onBackground, 2.dp.toPx(), Offset(center.x, center.y + (dot - 1) * 6.dp.toPx())) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                    if (lyrics.isEmpty()) {
                        Text(state.status, color = palette.onBackground.copy(alpha = .54f), fontSize = 20.sp,
                            fontFamily = originalFont, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                    } else AppleMusicLyricsView(
                        lyrics = lyrics, currentIndex = index, currentPositionMs = (position - offset).coerceAtLeast(0L),
                        isPlaying = state.playing, pageVisible = visible,
                        showTranslation = preferences.flag("translation", true), showPronunciation = preferences.flag("roma", true),
                        fontFamily = originalFont, translationFontFamily = translationFont,
                        fontWeight = FontWeight(preferences.number("font_weight", 800).coerceIn(100, 900)),
                        fontScale = preferences.number("font_scale", 100) / 100f,
                        secondaryFontScale = preferences.number("secondary_scale", 100) / 100f,
                        primaryTextSizeSp = preferences.number("primary_size", 32).toFloat(),
                        secondaryTextSizeSp = preferences.number("secondary_size", 16).toFloat(),
                        lyricTextAlign = preferences.number("align", 0), contentColor = palette.onBackground,
                        wordLiftEnabled = preferences.flag("word_lift", true),
                        perspectiveEffectEnabled = preferences.flag("perspective", false),
                        perspectiveYAngle = preferences.number("perspective_angle", 25),
                        onLineClick = { if (preferences.flag("click_seek", true)) state.seekAndPlay(it.timeMs + offset) },
                        onLineLongClick = {}, topContentPadding = 72.dp, bottomContentPadding = 72.dp,
                        useFocusLeadingPadding = false, focusOffsetRatio = .24f,
                        showBackgroundText = preferences.flag("backing_vocals", true),
                        followRequest = state.followRequest, manualScrollRecenterDelayMs = 8000L,
                        modifier = Modifier.fillMaxSize().testTag("immersive_lyrics")
                    )
                }
            }
        }
        if (state.musicTagChoices.size > 1) SongVersionChooser(state.musicTagChoices, state.cover, palette, preferences,
            originalFont, state.playing && visible, position, state.musicTagsBusy,
            onDismiss = state::dismissMusicTagChoices, onSelect = state::selectMusicTagVersion)
    }
}

@Composable
private fun rememberHalcyonFont(path: String?, weight: Int): FontFamily {
    val context = LocalContext.current
    return remember(path, context, weight) {
        if (path == "system") return@remember FontFamily.Default
        if (path == "inter") return@remember FontFamily(Font("fonts/Inter-Bold.ttf", context.assets, weight = FontWeight.Bold))
        if (path.isNullOrBlank()) {
            val families = listOf("Inter-Bold.ttf", "MiSans-Bold.ttf").map { name ->
                android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(context.assets, "fonts/$name").setWeight(weight).build()).build()
            }
            return@remember FontFamily(android.graphics.Typeface.CustomFallbackBuilder(families[0]).addCustomFallback(families[1])
                .setSystemFallback("sans-serif").setStyle(android.graphics.fonts.FontStyle(weight, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)).build())
        }
        val custom = path.let(::File).takeIf { it.isFile }
        if (custom != null) runCatching { FontFamily(android.graphics.Typeface.createFromFile(custom)) }.getOrNull()
            ?: FontFamily(Font("fonts/MiSans-Bold.ttf", context.assets, weight = FontWeight.Bold))
        else FontFamily(Font("fonts/MiSans-Bold.ttf", context.assets, weight = FontWeight.Bold))
    }
}

@Composable
internal fun HalcyonBackdrop(cover: Bitmap?, palette: PlayerPalette, preferences: Map<String, *>, playing: Boolean, position: Long,
    customBackground: Bitmap? = null) {
    when (preferences.number("background_mode", 0)) {
        1 -> BeautifulLyricsDynamicBackground(palette, cover, position, playing, animate = playing, modifier = Modifier.fillMaxSize(),
            speedOverride = preferences.number("beautiful_speed", 25), blurOverride = preferences.number("beautiful_blur", 32),
            brightnessOverride = preferences.number("beautiful_brightness", 70))
        2 -> {
            Box(Modifier.fillMaxSize().background(palette.middle)) {
                if (cover != null) Image(cover.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 2.9f; scaleY = 2.9f; alpha = .78f }.blur(48.dp))
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(palette.accent.copy(alpha = .28f), palette.top.copy(alpha = .42f), Color.Black.copy(alpha = .34f)))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.Transparent, Color.Black.copy(alpha = .32f)))))
            }
        }
        3 -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.top, palette.middle, palette.bottom))))
        4 -> PlayerCustomBackground(preferences["custom_background_uri"] as? String ?: "",
            imageAlpha = preferences.number("custom_opacity", 100) / 100f,
            dimAlpha = preferences.number("custom_dim", 26) / 100f, modifier = Modifier.fillMaxSize(), bitmap = customBackground)
        else -> AppleCoverFlowBackground(cover, palette.middle, isDark = true, isPlaying = playing,
            animate = preferences.flag("flow_enabled", true) && playing,
            blur = preferences.number("apple_flow_blur", 60).toFloat(), modifier = Modifier.fillMaxSize(),
            speedOverride = preferences.number("apple_flow_speed", 10))
    }
}
