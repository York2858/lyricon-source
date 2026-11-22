package io.github.proify.lyricon.app.activity.lyric.immersive

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.res.stringResource
import io.github.proify.lyricon.app.R
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.*
import io.github.proify.lyricon.app.activity.lyric.immersive.halcyon.model.LyricLine
import io.github.proify.lyricon.app.bridge.ImmersiveLyricsContract
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
    val offsetStore = remember(context) { ImmersiveOffsetStore.get(context) }
    val savedOffsets by offsetStore.values.collectAsState()
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
    val offset = (if (state.sourceKey.isBlank()) 0 else
        (savedOffsets[state.sourceKey] as? Int) ?: state.savedLyricOffset).coerceIn(-5000, 5000).toLong()
    val index = lyrics.indexOfLast { it.timeMs <= position - offset }.coerceAtLeast(0)
    val palette = remember(state.cover) { PlayerPalette.from(state.cover).withCoverContentColor() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dimLandscapeBackdrop = landscape && preferences.number("background_mode", 0) == 0
    val landscapePalette = remember(palette, dimLandscapeBackdrop) {
        if (dimLandscapeBackdrop) palette.copy(isLight = false).withCoverContentColor() else palette
    }
    val headerTop = preferences.number(HEADER_TOP_KEY, DEFAULT_HEADER_TOP).coerceIn(HEADER_TOP_RANGE)
        .dp.coerceAtMost(LocalConfiguration.current.screenHeightDp.dp * .25f)
    val bottomActionsMargin = preferences.number("bottom_actions_margin", 8).coerceIn(0, 120)
    val bottomActionsSize = preferences.number("bottom_actions_size", 36).coerceIn(20, 56)
    val titleSize = preferences.number("title_size", 22).coerceIn(12, 36)
    val albumSize = preferences.number("album_size", 14).coerceIn(10, 28)
    val artistSize = preferences.number("artist_size", 14).coerceIn(10, 28)
    val headerHeight = with(LocalDensity.current) {
        (titleSize * 1.2f).sp.toDp() + (albumSize * 1.3f).sp.toDp() + (artistSize * 1.3f).sp.toDp() + 8.dp
    }.coerceAtLeast(72.dp)
    val residentPreferences = rememberAppleMusicLyricsViewPreferences(manager)
    val livePosition = remember(state, offset) { { (state.currentPosition() - offset).coerceAtLeast(0L) } }
    CompositionLocalProvider(
        LocalPlayerSurfaceActive provides visible,
        LocalPlayerLyricPositionProvider provides livePosition,
        LocalAppleMusicLyricsViewPreferences provides residentPreferences
    ) {
        Box(Modifier.fillMaxSize().background(palette.middle)) {
            HalcyonBackdrop(state.cover, palette, preferences, state.playing && visible, position)
            if (dimLandscapeBackdrop) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                    Color(0xFF262D3A).copy(alpha = .72f), Color(0xFF10131A).copy(alpha = .85f)
                ))))
            }
            if (landscape) {
                ImmersiveLyricsLandscape(
                    state = state,
                    palette = landscapePalette,
                    lyrics = lyrics,
                    index = index,
                    position = position,
                    offset = offset,
                    visible = visible,
                    preferences = preferences,
                    originalFont = originalFont,
                    translationFont = translationFont,
                    titleSize = titleSize,
                    albumSize = albumSize,
                    artistSize = artistSize,
                    bottomActionsSize = bottomActionsSize,
                    onExplanation = { context.startActivity(state.explanationIntent(context)) },
                    onSettings = {
                        context.startActivity(android.content.Intent(context, ImmersiveSettingsActivity::class.java)
                            .putExtra("source_token", state.sourceToken)
                            .putExtra("source_key", state.sourceKey).putExtra("lyric_offset", offset.toInt()))
                    }
                )
            } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = headerTop), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(headerHeight).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .1f))
                        .clickable(onClickLabel = "打开音乐解读") { context.startActivity(state.explanationIntent(context)) }
                        .semantics { contentDescription = "打开音乐解读" }, contentAlignment = Alignment.Center) {
                        state.cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Text("♪", color = palette.onBackground, fontSize = 28.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    SynchronizedSongHeader(
                        title = state.title.ifBlank { "沉浸歌词" },
                        artist = state.artist.ifBlank { "词幕" },
                        font = originalFont,
                        color = palette.onBackground,
                        active = visible,
                        modifier = Modifier.weight(1f).height(headerHeight),
                        titleSize = titleSize,
                        artistSize = artistSize,
                        onTitleClick = state::openMusicTags,
                        album = state.album,
                        albumSize = albumSize,
                        onAlbumClick = if (state.album.isNotBlank()) state::openAlbumPage else null,
                        onArtistClick = if (state.artist.isNotBlank()) state::openArtistPage else null,
                        fillHeight = true
                    )
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
                        context.startActivity(android.content.Intent(context, ImmersiveSettingsActivity::class.java)
                            .putExtra("source_token", state.sourceToken)
                            .putExtra("source_key", state.sourceKey).putExtra("lyric_offset", offset.toInt()))
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
                        onLineLongClick = {}, topContentPadding = 0.dp,
                        bottomContentPadding = (64 + bottomActionsMargin).dp,
                        focusOffsetRatio = preferences.lyricFocusRatio(landscape = false),
                        showBackgroundText = preferences.flag("backing_vocals", true),
                        followRequest = state.followRequest, manualScrollRecenterDelayMs = 8000L,
                        modifier = Modifier.fillMaxSize().testTag("immersive_lyrics")
                    )
                    if (lyrics.isNotEmpty()) {
                        val busy = state.translationBusy
                        val enabled = !busy && !state.demo && state.sourceToken.isNotBlank()
                        val iconBox = (bottomActionsSize + 8).dp
                        Box(Modifier.align(Alignment.BottomStart).padding(bottom = bottomActionsMargin.dp).size(iconBox)
                            .testTag("immersive_retranslate")
                            .semantics {
                                contentDescription = "重新翻译"
                                stateDescription = if (busy) "翻译中" else "重新翻译当前歌词"
                            }
                            .clickable(enabled = enabled, role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClick = state::retranslate), contentAlignment = Alignment.Center) {
                            if (busy && visible) {
                                CircularProgressIndicator(Modifier.size((bottomActionsSize * 2 / 3).dp), color = palette.onBackground, strokeWidth = 2.dp)
                            } else {
                                Icon(painterResource(R.drawable.halcyon_retranslate), null, Modifier.size(bottomActionsSize.dp),
                                    tint = palette.onBackground.copy(alpha = if (enabled) 1f else .4f))
                            }
                        }
                    }
                    if (!state.demo && state.song != null) {
                        Row(Modifier.align(Alignment.BottomEnd).padding(bottom = bottomActionsMargin.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            BottomActionButton(R.drawable.halcyon_comment, "打开评论页", palette.onBackground,
                                bottomActionsSize, Modifier.testTag("immersive_comments"), state::openCommentsPage)
                            BottomActionButton(R.drawable.halcyon_review, "打开乐评页", palette.onBackground,
                                bottomActionsSize, Modifier.testTag("immersive_review"), state::openReviewPage)
                        }
                    }
                }
            }
        }
        if (state.musicTagChoices.size > 1) SongVersionChooser(state.musicTagChoices, state.cover, palette, preferences,
            originalFont, state.playing && visible, position, state.musicTagsBusy,
            onDismiss = state::dismissMusicTagChoices, onSelect = state::selectMusicTagVersion)
    }
}

@Composable
private fun ImmersiveLyricsLandscape(
    state: ImmersiveLyricsState,
    palette: PlayerPalette,
    lyrics: List<LyricLine>,
    index: Int,
    position: Long,
    offset: Long,
    visible: Boolean,
    preferences: Map<String, *>,
    originalFont: FontFamily,
    translationFont: FontFamily,
    titleSize: Int,
    albumSize: Int,
    artistSize: Int,
    bottomActionsSize: Int,
    onExplanation: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val gutter = (maxWidth * .055f).coerceIn(16.dp, 56.dp)
        val contentWidth = (maxWidth - gutter * 2).coerceAtLeast(0.dp)
        val leftWidth = (maxWidth * .28f).coerceIn(192.dp, 440.dp).coerceAtMost(contentWidth * .53f)
        val screenHeight = maxHeight
        val verticalInset = (screenHeight * .03f).coerceIn(4.dp, 18.dp)

        Column(Modifier.fillMaxSize().padding(horizontal = gutter, vertical = verticalInset)) {
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp, 36.dp).clip(RoundedCornerShape(12.dp))
                    .background(palette.onBackground.copy(alpha = .10f))
                    .semantics { contentDescription = "收起歌词页" }
                    .clickable(role = Role.Button) { (context as? Activity)?.finish() }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.halcyon_chevron_down), null, Modifier.size(20.dp),
                        tint = palette.onBackground.copy(alpha = .95f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LandscapeRetranslate(state, palette, visible)
                    Box(Modifier.size(40.dp, 36.dp).clip(RoundedCornerShape(12.dp))
                        .background(palette.onBackground.copy(alpha = .10f))
                        .semantics { contentDescription = "歌词设置" }
                        .clickable(role = Role.Button, onClick = onSettings), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(20.dp)) {
                            repeat(3) { dot -> drawCircle(palette.onBackground.copy(alpha = .95f), 1.6.dp.toPx(),
                                Offset(center.x + (dot - 1) * 6.dp.toPx(), center.y)) }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().weight(1f)) {
                val compact = screenHeight < 330.dp
                Column(Modifier.width(leftWidth).fillMaxHeight().padding(bottom = (screenHeight * .08f).coerceAtMost(48.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp, Alignment.CenterVertically)) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        val coverActionGap = 8.dp
                        val actionIconSize = (bottomActionsSize * .5f).dp.coerceIn(14.dp, 24.dp)
                        val actionWidth = (maxWidth * .16f).coerceIn(36.dp, 54.dp).coerceAtMost(maxWidth * .27f)
                        val coverSide = minOf(maxHeight, maxWidth * .65f,
                            maxWidth - (actionWidth + coverActionGap) * 2).coerceAtLeast(0.dp)
                        val showTrackPages = !state.demo && state.song != null
                        val actionCount = if (showTrackPages) 3 else 1
                        val actionHeight = (maxHeight / actionCount).coerceAtMost(40.dp)
                        Box(Modifier.fillMaxWidth().height(maxOf(coverSide, actionHeight * actionCount))) {
                            Box(Modifier.align(Alignment.Center).size(coverSide).clip(RoundedCornerShape(14.dp))
                                .background(palette.onBackground.copy(alpha = .08f))
                                .clickable(onClickLabel = "打开音乐解读", onClick = onExplanation)
                                .semantics { contentDescription = "打开音乐解读" }, contentAlignment = Alignment.Center) {
                                state.cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                    ?: Text("♪", color = palette.onBackground, fontSize = 24.sp)
                            }
                            LandscapeTrackActions(
                                state, palette, showTrackPages, actionIconSize, actionHeight,
                                Modifier.align(Alignment.CenterEnd).width(actionWidth)
                            )
                        }
                    }
                    SynchronizedSongHeader(
                        title = state.title.ifBlank { "沉浸歌词" },
                        artist = state.artist.ifBlank { "词幕" },
                        font = originalFont,
                        color = palette.onBackground,
                        active = visible,
                        modifier = Modifier.fillMaxWidth(),
                        titleSize = titleSize,
                        artistSize = artistSize,
                        onTitleClick = state::openMusicTags,
                        album = state.album,
                        albumSize = albumSize,
                        onAlbumClick = if (state.album.isNotBlank()) state::openAlbumPage else null,
                        onArtistClick = if (state.artist.isNotBlank()) state::openArtistPage else null,
                        centered = true
                    )
                    LandscapePlayback(state, palette, position, offset)
                }
                Spacer(Modifier.width(gutter))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (lyrics.isEmpty()) {
                        Text(state.status, color = palette.onBackground.copy(alpha = .54f), fontSize = 20.sp,
                            fontFamily = originalFont, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                    } else {
                        AppleMusicLyricsView(
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
                            onLineLongClick = {}, topContentPadding = 0.dp, bottomContentPadding = 8.dp,
                            focusOffsetRatio = preferences.lyricFocusRatio(landscape = true),
                            showBackgroundText = preferences.flag("backing_vocals", true),
                            followRequest = state.followRequest, manualScrollRecenterDelayMs = 8000L,
                            modifier = Modifier.fillMaxSize().testTag("immersive_lyrics")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapePlayback(state: ImmersiveLyricsState, palette: PlayerPalette, position: Long, offset: Long) {
    val duration = state.duration.coerceAtLeast(0L)
    val current = (position - offset).coerceIn(0L, duration)
    val fraction = if (duration > 0L) current.toFloat() / duration else 0f
    val tint = palette.onBackground

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp).progressSemantics(fraction)) {
            val radius = minOf(3.5.dp.toPx(), size.width / 2)
            val start = Offset(radius, center.y)
            val end = Offset(size.width - radius, center.y)
            val progress = Offset(start.x + (end.x - start.x) * fraction, center.y)
            val stroke = 2.5.dp.toPx()
            drawLine(tint.copy(alpha = .22f), start, end, stroke, StrokeCap.Round)
            if (fraction > 0f) drawLine(tint.copy(alpha = .92f), start, progress, stroke, StrokeCap.Round)
            drawCircle(tint, radius, progress)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                LandscapePlaybackTime(formatLyricTime(current), tint)
            }
            Row(Modifier.weight(2.6f), verticalAlignment = Alignment.CenterVertically) {
                TransportButton(R.drawable.halcyon_previous, 21.dp, "后退 6 秒", palette, Modifier.weight(1f)) {
                    state.command(ImmersiveLyricsContract.PREVIOUS)
                }
                TransportButton(if (state.playing) R.drawable.halcyon_pause else R.drawable.halcyon_play, 23.dp,
                    if (state.playing) "暂停" else "播放", palette, Modifier.weight(1f), prominent = true) {
                    state.command(ImmersiveLyricsContract.TOGGLE)
                }
                TransportButton(R.drawable.halcyon_next, 21.dp, "前进 6 秒", palette, Modifier.weight(1f)) {
                    state.command(ImmersiveLyricsContract.NEXT)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                LandscapePlaybackTime("−" + formatLyricTime(duration - current), tint.copy(alpha = .76f))
            }
        }
    }
}

@Composable
private fun LandscapePlaybackTime(time: String, tint: Color) {
    BasicText(time, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = tint, fontFeatureSettings = "tnum"),
        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 18.sp, stepSize = 1.sp))
}

@Composable
private fun TransportButton(icon: Int, size: Dp, label: String, palette: PlayerPalette, modifier: Modifier,
    prominent: Boolean = false, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(CircleShape).semantics { contentDescription = label }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        if (prominent) {
            val iconTint = if (palette.isLight) Color.White else Color(0xFF252D3A)
            Box(Modifier.size(36.dp).background(palette.onBackground, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(size), tint = iconTint)
            }
        } else {
            Icon(painterResource(icon), null, Modifier.size(size), tint = palette.onBackground)
        }
    }
}

@Composable
private fun LandscapeRetranslate(state: ImmersiveLyricsState, palette: PlayerPalette, visible: Boolean) {
    val busy = state.translationBusy
    val enabled = !busy && !state.demo && state.sourceToken.isNotBlank()
    val tint = palette.onBackground.copy(alpha = if (enabled || busy) .92f else .40f)
    val label = stringResource(R.string.immersive_retranslate_short)
    val description = stringResource(R.string.immersive_retranslate_description)
    val translating = stringResource(R.string.immersive_retranslating)
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(12.dp))
        .background(palette.onBackground.copy(alpha = .10f)).testTag("immersive_retranslate")
        .semantics {
            contentDescription = description
            if (busy) stateDescription = translating
        }
        .clickable(enabled = enabled, role = Role.Button, onClick = state::retranslate)
        .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (busy && visible) {
            CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.halcyon_retranslate), null, Modifier.size(18.dp), tint = tint)
        }
        Text(label, color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun LandscapeTrackActions(state: ImmersiveLyricsState, palette: PlayerPalette, showTrackPages: Boolean,
    iconSize: Dp, itemHeight: Dp, modifier: Modifier) {
    val tint = palette.onBackground
    val favoriteLabel = stringResource(R.string.immersive_favorite)
    val favoriteDescription = if (state.favorite) stringResource(R.string.immersive_remove_favorite) else favoriteLabel
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .08f))) {
        LandscapeTrackAction(
            if (state.favorite) R.drawable.halcyon_favorite_filled else R.drawable.halcyon_favorite,
            favoriteLabel, tint, iconSize, itemHeight, description = favoriteDescription,
            modifier = Modifier.semantics { selected = state.favorite },
            enabled = state.canFavorite && !state.favoriteBusy, onClick = state::toggleFavorite
        )
        if (showTrackPages) {
            LandscapeTrackAction(R.drawable.halcyon_comment, stringResource(R.string.immersive_comments),
                tint, iconSize, itemHeight, Modifier.testTag("immersive_comments"), divider = true, onClick = state::openCommentsPage)
            LandscapeTrackAction(R.drawable.halcyon_review, stringResource(R.string.immersive_review),
                tint, iconSize, itemHeight, Modifier.testTag("immersive_review"), divider = true, onClick = state::openReviewPage)
        }
    }
}

@Composable
private fun LandscapeTrackAction(icon: Int, label: String, tint: Color, iconSize: Dp, height: Dp,
    modifier: Modifier = Modifier, description: String = label, divider: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit) {
    val color = tint.copy(alpha = if (enabled) .90f else .38f)
    Box(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        if (divider) {
            HorizontalDivider(Modifier.align(Alignment.TopCenter).fillMaxWidth(.60f),
                color = tint.copy(alpha = .12f))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), null, Modifier.size(iconSize.coerceAtMost(height * .50f)), tint = color)
            if (height >= 32.dp) {
                BasicText(label, Modifier.clearAndSetSemantics {}, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis, style = TextStyle(color = color),
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.sp, stepSize = 1.sp))
            }
        }
    }
}

private fun formatLyricTime(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

@Composable
private fun BottomActionButton(icon: Int, label: String, tint: Color, size: Int,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size((size + 8).dp).clip(RoundedCornerShape(12.dp))
        .semantics { contentDescription = label }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(size.dp), tint = tint.copy(alpha = .92f))
    }
}

@Composable
internal fun rememberHalcyonFont(path: String?, weight: Int): FontFamily {
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
