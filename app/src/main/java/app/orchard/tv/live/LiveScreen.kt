package app.orchard.tv.live

import android.graphics.Color as AndroidColor
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.ParserException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the OneTV-style left drawer: a section title or a selectable channel. */
private sealed interface DrawerRow {
    val key: String
    data class Header(val title: String) : DrawerRow { override val key get() = "h:$title" }
    data class Channel(val channel: LiveChannel, override val key: String) : DrawerRow
}

/**
 * Luna's live television screen. The video is full-screen, as on OneTV; MENU opens the channel
 * drawer, D-pad zaps channels when it is closed, and the drawer row is moved by D-pad instead of
 * relying on focus traversal.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveHost(vm: HomeViewModel) {
    if (vm.overlay != Overlay.Live || !vm.resumed || vm.screensaverVisible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LiveStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    var bootstrapping by remember { mutableStateOf(store.isEmpty) }
    var setupOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<LiveChannel?>(null) }
    var streamIndex by remember { mutableIntStateOf(0) }
    var replayToken by remember { mutableIntStateOf(0) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var renderedFirstFrame by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var drawerRow by remember { mutableIntStateOf(-1) }
    var titleVisible by remember { mutableStateOf(false) }
    val baseFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val currentState by rememberUpdatedState(current)
    val streamState by rememberUpdatedState(streamIndex)
    val replayState by rememberUpdatedState(replayToken)

    val channels = remember(revision) { store.channels() }
    val favoriteChannels = remember(revision) { store.favorites() }
    val channelNumbers = remember(channels) { channels.mapIndexed { index, channel -> channel.id to index + 1 }.toMap() }
    val activeName = remember(revision) { store.activeName() }

    val drawerRows = remember(revision, favoritesOnly, channels, favoriteChannels) {
        buildList {
            if (!favoritesOnly && favoriteChannels.isNotEmpty()) {
                add(DrawerRow.Header(context.getString(R.string.luna_live_favorites)))
                favoriteChannels.forEach { add(DrawerRow.Channel(it, "f:${it.id}")) }
                add(DrawerRow.Header(context.getString(R.string.luna_live_all_channels)))
            }
            if (favoritesOnly) {
                favoriteChannels.forEach { add(DrawerRow.Channel(it, "f:${it.id}")) }
            } else {
                channels.groupBy { ChannelNames.group(it.group) }.forEach { (group, rows) ->
                    add(DrawerRow.Header(group))
                    rows.forEach { add(DrawerRow.Channel(it, "c:${it.id}")) }
                }
            }
        }
    }

    // A faster live buffer and generous HTTP timeouts are what OneTV uses for IPTV streams.
    val loadControl = remember(context) {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 20_000, 2_000, 3_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
    val mediaFactory = remember(context) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("LunaTV Live/1.0")
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val policy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 3
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val httpError = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                return if (loadErrorInfo.exception is ParserException || httpError?.responseCode in listOf(401, 403, 404, 410)) {
                    C.TIME_UNSET
                } else super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(policy)
    }
    val exoPlayer = remember(context) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
    }

    fun closeLive() {
        exoPlayer.stop()
        vm.overlay = Overlay.ControlCenter()
    }

    fun play(channel: LiveChannel) {
        store.remember(channel)
        streamIndex = store.preferredLine(channel)
        replayToken++
        playbackError = null
        renderedFirstFrame = false
        titleVisible = true
        current = channel
        drawerOpen = false
    }

    fun nextLine(reason: String? = null) {
        val channel = currentState ?: return
        val index = streamState
        if (index + 1 < channel.streams.size) {
            playbackError = null
            renderedFirstFrame = false
            streamIndex = index + 1
            replayToken++
        } else {
            playbackError = reason ?: context.getString(R.string.luna_live_line_hint)
        }
    }

    fun refresh() {
        revision++
        val available = store.channels()
        if (current == null || available.none { it.id == current!!.id }) {
            current = available.firstOrNull()
            if (current != null) {
                streamIndex = store.preferredLine(current!!)
                replayToken++
            }
        }
    }

    fun browseChannels(): List<LiveChannel> =
        if (favoritesOnly && favoriteChannels.isNotEmpty()) favoriteChannels else channels

    fun stepChannel(delta: Int) {
        val list = browseChannels()
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == current?.id }
        play(list[if (index < 0) 0 else (index + delta + list.size) % list.size])
    }

    fun drawerChannelIndex(from: Int, direction: Int): Int {
        var index = from
        while (true) {
            index += direction
            if (index !in drawerRows.indices) return from
            if (drawerRows[index] is DrawerRow.Channel) return index
        }
    }

    fun openDrawer() {
        val currentId = current?.id
        drawerRow = drawerRows.indexOfFirst { it is DrawerRow.Channel && it.channel.id == currentId }
            .takeIf { it >= 0 }
            ?: drawerRows.indexOfFirst { it is DrawerRow.Channel }
        if (drawerRow < 0) drawerRow = 0
        drawerOpen = true
    }

    fun handleDrawerKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
            drawerRow = drawerChannelIndex(drawerRow, -1)
            true
        }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            drawerRow = drawerChannelIndex(drawerRow, 1)
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            (drawerRows.getOrNull(drawerRow) as? DrawerRow.Channel)?.let { play(it.channel) }
            true
        }
        else -> false
    }

    LaunchedEffect(Unit) {
        if (store.isEmpty) {
            val result = withContext(Dispatchers.IO) { store.addSubscription(LiveStore.DEFAULT_SOURCE) }
            bootstrapping = false
            status = result.fold(
                onSuccess = { count -> "已载入默认直播源，共 $count 个频道" },
                onFailure = { "默认直播源加载失败：${it.message}" },
            )
            refresh()
            if (store.isEmpty) {
                setupOpen = true
            } else {
                current?.let { play(it) }
            }
        } else {
            current = store.resumeChannel()
            if (current == null) setupOpen = true else play(current!!)
        }
    }

    LaunchedEffect(current?.id) {
        val channel = current ?: return@LaunchedEffect
        titleVisible = true
        delay(4_200)
        if (current?.id == channel.id && !drawerOpen) titleVisible = false
    }

    LaunchedEffect(drawerOpen, drawerRow) {
        if (drawerOpen && drawerRow >= 0) listState.scrollToItem(drawerRow)
    }

    LaunchedEffect(drawerOpen, favoritesOnly, drawerRows) {
        if (!drawerOpen) return@LaunchedEffect
        val currentRow = drawerRows.indexOfFirst { it is DrawerRow.Channel && it.channel.id == current?.id }
        if (currentRow >= 0 && drawerRows.getOrNull(drawerRow) !is DrawerRow.Channel) drawerRow = currentRow
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        baseFocus.requestFocus()
    }

    LaunchedEffect(current, streamIndex, replayToken) {
        val channel = current ?: return@LaunchedEffect
        val url = channel.streams.getOrNull(streamIndex) ?: return@LaunchedEffect
        playbackError = null
        playbackState = Player.STATE_BUFFERING
        renderedFirstFrame = false
        val item = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(12_000)
                    .setMinPlaybackSpeed(0.98f)
                    .setMaxPlaybackSpeed(1.02f)
                    .build(),
            )
            .build()
        val source = if (url.startsWith("rtsp:", true)) {
            RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8_000)
                .setUserAgent("LunaTV Live/1.0").createMediaSource(item)
        } else {
            mediaFactory.createMediaSource(item)
        }
        exoPlayer.stop()
        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                nextLine(error.localizedMessage ?: context.getString(R.string.luna_live_line_hint))
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) playbackError = null
            }

            override fun onRenderedFirstFrame() {
                renderedFirstFrame = true
                val channel = currentState ?: return
                val url = channel.streams.getOrNull(streamState) ?: return
                scope.launch(Dispatchers.IO) { store.rememberLine(channel, url) }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // A dead first line must not leave the viewer staring at a black rectangle.
    LaunchedEffect(current?.id, streamIndex, replayToken) {
        if (current == null) return@LaunchedEffect
        delay(18_000)
        if (current?.id == currentState?.id && streamIndex == streamState && replayState == replayToken &&
            !renderedFirstFrame && playbackError == null
        ) {
            nextLine()
        }
    }

    LaunchedEffect(current?.id, streamIndex, replayToken, playbackState) {
        if (playbackState != Player.STATE_BUFFERING || current == null) return@LaunchedEffect
        delay(28_000)
        if (playbackState == Player.STATE_BUFFERING && playbackError == null &&
            streamIndex == streamState && replayState == replayToken
        ) {
            nextLine()
        }
    }

    Dialog(
        onDismissRequest = {
            if (drawerOpen) drawerOpen = false else closeLive()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(baseFocus).focusable()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (val key = event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_MENU -> {
                            if (drawerOpen) drawerOpen = false else openDrawer()
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            if (drawerOpen) drawerOpen = false else closeLive()
                            true
                        }
                        else -> if (drawerOpen) {
                            handleDrawerKey(key)
                        } else when (key) {
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                                stepChannel(-1)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                stepChannel(1)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                openDrawer()
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        setBackgroundColor(AndroidColor.BLACK)
                        isFocusable = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = exoPlayer
                    }
                },
                update = { it.player = exoPlayer },
            )

            if (current == null && playbackError == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        if (bootstrapping) stringResource(R.string.luna_live_loading) else stringResource(R.string.luna_live_pick_hint),
                        style = Type.Body.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.66f)).padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
            }

            if (current != null && !renderedFirstFrame && playbackError == null) {
                Row(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.70f)).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Orange))
                    BasicText(
                        stringResource(R.string.luna_live_loading_channel, ChannelNames.display(current!!.name)),
                        style = Type.Callout.copy(color = Color.White),
                    )
                }
            }

            if (!drawerOpen && titleVisible && current != null) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(start = 30.dp, bottom = 28.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(width = 38.dp, height = 26.dp).clip(RoundedCornerShape(7.dp))
                            .background(Color.White.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            (channelNumbers[current!!.id] ?: 0).toString().padStart(3, '0'),
                            style = Type.Caption.copy(color = Color.White),
                        )
                    }
                    Column {
                        BasicText(
                            ChannelNames.display(current!!.name),
                            style = Type.Title.copy(color = Color.White),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF453A)))
                            BasicText(
                                if (activeName.isBlank()) stringResource(R.string.luna_live_title) else activeName,
                                style = Type.Caption.copy(color = Color.White.copy(alpha = 0.72f)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            playbackError?.let { message ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 105.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.82f))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(message, style = Type.Body.copy(color = Color(0xFFFF6B63)))
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        stringResource(R.string.luna_live_line_hint),
                        style = Type.Callout.copy(color = Color.White.copy(alpha = 0.66f)),
                    )
                }
            }

            if (drawerOpen) {
                Column(
                    Modifier.width(392.dp).fillMaxHeight().padding(14.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xE6101318))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
                        .padding(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(stringResource(R.string.luna_live_drawer_title), style = Type.Title.copy(color = Color.White))
                            BasicText(
                                stringResource(R.string.luna_live_channel_count, channels.size) +
                                    if (activeName.isBlank()) "" else " · $activeName",
                                style = Type.Caption.copy(color = Color.White.copy(alpha = 0.55f)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current != null) {
                            BasicText(
                                (channelNumbers[current!!.id] ?: 0).toString().padStart(3, '0'),
                                style = Type.Callout.copy(color = Color.White.copy(alpha = 0.62f)),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DrawerChip(stringResource(R.string.luna_live_all_channels), !favoritesOnly) {
                            favoritesOnly = false
                            drawerRow = drawerRows.indexOfFirst { it is DrawerRow.Channel }
                        }
                        DrawerChip(stringResource(R.string.luna_live_favorites), favoritesOnly) {
                            favoritesOnly = true
                            drawerRow = drawerRows.indexOfFirst { it is DrawerRow.Channel }
                        }
                        Spacer(Modifier.weight(1f))
                        DrawerChip(stringResource(R.string.luna_live_manage), false) {
                            drawerOpen = false
                            setupOpen = true
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (drawerRows.isEmpty()) {
                        BasicText(
                            stringResource(R.string.luna_live_pick_hint),
                            style = Type.Callout.copy(color = Color.White.copy(alpha = 0.55f)),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(drawerRows, key = { it.key }) { row ->
                            when (row) {
                                is DrawerRow.Header -> BasicText(
                                    row.title,
                                    style = Type.Caption.copy(color = Color.White.copy(alpha = 0.48f)),
                                    modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp),
                                )
                                is DrawerRow.Channel -> {
                                    val index = drawerRows.indexOf(row)
                                    DrawerChannelRow(
                                        channel = row.channel,
                                        number = channelNumbers[row.channel.id] ?: 0,
                                        playing = current?.id == row.channel.id,
                                        focused = drawerRow == index,
                                        favorite = store.isFavorite(row.channel.id),
                                        onClick = { play(row.channel) },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        stringResource(R.string.luna_live_drawer_hint),
                        style = Type.Caption.copy(color = Color.White.copy(alpha = 0.42f)),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            if (setupOpen) {
                LiveSetup(store, status, onClose = {
                    setupOpen = false
                    refresh()
                }, onChanged = { message ->
                    status = message
                    refresh()
                })
            }
        }
    }
}

@Composable
private fun DrawerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .focusProperties { canFocus = false }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            style = Type.Caption.copy(color = if (selected) Color(0xFF111419) else Color.White.copy(alpha = 0.82f)),
            maxLines = 1,
        )
    }
}

@Composable
private fun DrawerChannelRow(
    channel: LiveChannel,
    number: Int,
    playing: Boolean,
    focused: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        focused -> Palette.Focus
        playing -> Color.White.copy(alpha = 0.11f)
        else -> Color.Transparent
    }
    val foreground = if (focused) Palette.OnFocus else Color.White
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp)).background(background)
            .clickable(onClick = onClick)
            .focusProperties { canFocus = false }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(width = 30.dp, height = 21.dp).clip(RoundedCornerShape(5.dp))
                .background(if (focused) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.09f)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                number.toString().padStart(3, '0'),
                style = Type.Caption.copy(color = if (focused) Palette.OnFocus.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.56f)),
            )
        }
        BasicText(
            ChannelNames.display(channel.name),
            style = Type.Body.copy(color = foreground),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (playing) {
            BasicText(
                stringResource(R.string.luna_live_now),
                style = Type.Caption.copy(color = if (focused) Palette.OnFocus.copy(alpha = 0.62f) else Color(0xFF30D158)),
            )
        }
        BasicText("★", style = Type.Callout.copy(color = if (favorite) Color(0xFFFFC233) else Color.White.copy(alpha = 0.22f)))
    }
}

@Composable
private fun LiveSetup(
    store: LiveStore,
    initialStatus: String?,
    onClose: () -> Unit,
    onChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var directName by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(initialStatus ?: "") }
    var busy by remember { mutableStateOf(false) }

    fun subscribe() {
        if (address.isBlank() || busy) return
        busy = true
        message = "正在读取订阅…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.addSubscription(address.trim()) }
            busy = false
            result.fold(
                onSuccess = {
                    message = "已添加 $it 个频道"
                    address = ""
                    onChanged(message)
                },
                onFailure = { message = "订阅失败：${it.message}" },
            )
        }
    }

    fun addDirect() {
        if (directUrl.isBlank() || busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.addDirect(directName.trim(), directUrl.trim()) }
            busy = false
            result.fold(
                onSuccess = {
                    message = "已添加 $it 个频道"
                    directName = ""
                    directUrl = ""
                    onChanged(message)
                },
                onFailure = { message = "添加失败：${it.message}" },
            )
        }
    }

    fun useDefault() {
        if (busy) return
        busy = true
        message = "正在获取默认直播源…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.addSubscription(LiveStore.DEFAULT_SOURCE) }
            busy = false
            result.fold(
                onSuccess = { message = "已添加 $it 个频道"; onChanged(message) },
                onFailure = { message = "默认源失败：${it.message}" },
            )
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(760.dp).height(520.dp).liquidGlass(24.dp, translucent = true)
                    .padding(24.dp).verticalScroll(rememberScrollState()),
            ) {
                BasicText(stringResource(R.string.luna_live_setup_title), style = Type.LargeTitle)
                Spacer(Modifier.height(6.dp))
                BasicText(
                    stringResource(R.string.luna_live_setup_body),
                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                )
                Spacer(Modifier.height(18.dp))
                BasicText(stringResource(R.string.luna_live_subscription), style = Type.Callout)
                Spacer(Modifier.height(6.dp))
                LiveField(address, { address = it }, stringResource(R.string.luna_live_url_hint))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        MenuRow(Glyph.Download, stringResource(R.string.luna_live_subscribe), onClick = ::subscribe)
                    }
                    Box(Modifier.weight(1f)) {
                        MenuRow(Glyph.Globe, stringResource(R.string.luna_live_default_source), onClick = ::useDefault)
                    }
                }
                Spacer(Modifier.height(18.dp))
                BasicText(stringResource(R.string.luna_live_direct), style = Type.Callout)
                Spacer(Modifier.height(6.dp))
                LiveField(directName, { directName = it }, stringResource(R.string.luna_live_name_hint))
                Spacer(Modifier.height(6.dp))
                LiveField(directUrl, { directUrl = it }, stringResource(R.string.luna_live_direct_hint))
                Spacer(Modifier.height(8.dp))
                MenuRow(Glyph.Play, stringResource(R.string.luna_live_add_direct), onClick = ::addDirect)
                Spacer(Modifier.height(14.dp))
                if (message.isNotEmpty()) {
                    BasicText(
                        message,
                        style = Type.Callout.copy(color = if (busy) Palette.SecondaryLabel else Palette.Label),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                MenuRow(Glyph.ChevronLeft, stringResource(R.string.luna_live_back), onClick = onClose)
            }
        }
    }
}

@Composable
private fun LiveField(value: String, onValueChange: (String) -> Unit, hint: String) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            BasicText(hint, style = Type.Body.copy(color = Palette.TertiaryLabel), maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Type.Body.copy(color = Palette.Label),
            cursorBrush = SolidColor(Palette.Label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
