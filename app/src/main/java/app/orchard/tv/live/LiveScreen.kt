package app.orchard.tv.live

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Luna's live television screen. It follows OneTV's shape: subscriptions and M3U playlists,
 * a grouped channel rail, favourites, last-channel resume and line fallback in ExoPlayer.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveHost(vm: HomeViewModel) {
    if (vm.overlay != Overlay.Live || !vm.resumed || vm.screensaverVisible) return
    val context = LocalContext.current
    val store = remember { LiveStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    var bootstrapping by remember { mutableStateOf(store.isEmpty) }
    var setupOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<LiveChannel?>(null) }
    var streamIndex by remember { mutableIntStateOf(0) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    val currentState by rememberUpdatedState(current)
    val streamState by rememberUpdatedState(streamIndex)
    val channels = remember(revision) { store.channels() }
    val favoriteChannels = remember(revision) { store.favorites() }
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    fun refresh() {
        revision++
        if (current == null) current = store.resumeChannel()
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
            if (store.isEmpty) setupOpen = true
        } else {
            current = store.resumeChannel()
        }
    }

    LaunchedEffect(current, streamIndex) {
        val channel = current ?: return@LaunchedEffect
        val url = channel.streams.getOrNull(streamIndex) ?: return@LaunchedEffect
        playbackError = null
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
                .setUserAgent("LunaTV Live").createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(context).createMediaSource(item)
        }
        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val channel = currentState
                if (channel != null && streamState + 1 < channel.streams.size) {
                    streamIndex = streamState + 1
                } else {
                    playbackError = error.localizedMessage ?: "无法播放该频道"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playbackError = null
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    fun play(channel: LiveChannel) {
        store.remember(channel)
        streamIndex = 0
        playbackError = null
        current = channel
    }

    Dialog(
        onDismissRequest = {
            exoPlayer.stop()
            vm.overlay = Overlay.ControlCenter()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU && current != null) {
                        store.toggleFavorite(current!!.id)
                        revision++
                        true
                    } else false
                },
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(356.dp).fillMaxHeight()
                        .background(Color(0xF0121214)).padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            BasicText(stringResource(R.string.luna_live_title), style = Type.LargeTitle)
                            BasicText(
                                current?.name ?: stringResource(R.string.luna_live_no_channel),
                                style = Type.Callout.copy(color = Palette.SecondaryLabel),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.widthIn(max = 330.dp)) {
                        MenuRow(
                            Glyph.Download, stringResource(R.string.luna_live_add_source),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.075f)),
                            rowHeight = 40.dp, onClick = { setupOpen = true },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.widthIn(max = 330.dp)) {
                        val id = current?.id
                        MenuRow(
                            Glyph.Check,
                            stringResource(
                                if (id != null && store.isFavorite(id)) R.string.luna_live_unfavorite
                                else R.string.luna_live_favorite,
                            ),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.05f)),
                            rowHeight = 40.dp,
                            onClick = {
                                id?.let {
                                    store.toggleFavorite(it)
                                    revision++
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        if (favoriteChannels.isNotEmpty()) {
                            item(key = "fav-head") {
                                BasicText(
                                    stringResource(R.string.luna_live_favorites),
                                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                            items(favoriteChannels, key = { "fav-${it.id}" }) { channel ->
                                ChannelRow(channel, current?.id == channel.id, true, { play(channel) })
                            }
                            item(key = "all-head") {
                                BasicText(
                                    stringResource(R.string.luna_live_all_channels),
                                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        }
                        val groups = channels.groupBy { it.group.ifBlank { "未分组" } }
                        groups.forEach { (group, rows) ->
                            item(key = "g:$group") {
                                BasicText(
                                    group,
                                    style = Type.Callout.copy(color = Palette.TertiaryLabel),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                            items(rows, key = { it.id }) { channel ->
                                ChannelRow(channel, current?.id == channel.id, false, { play(channel) })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        stringResource(R.string.luna_live_hint),
                        style = Type.Caption.copy(color = Palette.TertiaryLabel),
                    )
                }

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                setShutterBackgroundColor(AndroidColor.BLACK)
                                setBackgroundColor(AndroidColor.BLACK)
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                player = exoPlayer
                            }
                        },
                        update = { it.player = exoPlayer },
                    )
                    if (current == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            BasicText(
                                if (bootstrapping) stringResource(R.string.luna_live_loading) else stringResource(R.string.luna_live_pick_hint),
                                style = Type.Body.copy(color = Palette.SecondaryLabel),
                            )
                        }
                    }
                    playbackError?.let { message ->
                        Column(
                            Modifier.align(Alignment.BottomCenter).padding(24.dp)
                                .liquidGlass(18.dp, translucent = true).padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BasicText(message, style = Type.Body.copy(color = Palette.Red))
                            Spacer(Modifier.height(8.dp))
                            BasicText(
                                stringResource(R.string.luna_live_line_hint),
                                style = Type.Callout.copy(color = Palette.SecondaryLabel),
                            )
                        }
                    }
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
private fun ChannelRow(channel: LiveChannel, selected: Boolean, favorite: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Palette.Focus
                    selected -> Color.White.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            channel.name,
            style = Type.Body.copy(color = if (focused) Palette.OnFocus else Palette.Label),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (favorite) BasicText("★", style = Type.Callout.copy(color = if (focused) Palette.OnFocus else Palette.Orange))
        if (selected) {
            BasicText(
                stringResource(R.string.luna_live_now),
                style = Type.Caption.copy(color = if (focused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.Green),
            )
        }
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
