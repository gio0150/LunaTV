package app.orchard.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live-source tab in Luna TV settings. It replaces the old need to add a subscription from
 * inside the player: every saved source can be activated, refreshed or removed here, and the
 * currently playing source is marked.
 */
@Composable
internal fun LiveSourcesSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LiveStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("") }
    var directName by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val sources = remember(revision) { store.sourcesInfo() }

    fun report(ok: Boolean, text: String) {
        messageOk = ok
        message = text
        revision++
    }

    fun subscribe() {
        if (address.isBlank() || busy) return
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.addSubscription(address.trim()) }
            busy = false
            result.fold(
                onSuccess = {
                    address = ""
                    report(true, context.getString(R.string.luna_live_source_added, it))
                },
                onFailure = { report(false, context.getString(R.string.luna_live_source_failed, it.message ?: "")) },
            )
        }
    }

    fun defaultSource() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.addSubscription(LiveStore.DEFAULT_SOURCE) }
            busy = false
            result.fold(
                onSuccess = { report(true, context.getString(R.string.luna_live_source_added, it)) },
                onFailure = { report(false, context.getString(R.string.luna_live_source_failed, it.message ?: "")) },
            )
        }
    }

    fun addDirect() {
        if (directUrl.isBlank() || busy) return
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.addDirect(directName.trim(), directUrl.trim()) }
            busy = false
            result.fold(
                onSuccess = {
                    directName = ""
                    directUrl = ""
                    report(true, context.getString(R.string.luna_live_source_added, it))
                },
                onFailure = { report(false, context.getString(R.string.luna_live_source_failed, it.message ?: "")) },
            )
        }
    }

    fun refreshSource(url: String) {
        if (busy) return
        val activeUrl = store.sourcesInfo().firstOrNull { it.active }?.url
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.addSubscription(url) }
            busy = false
            // Refreshing a background playlist should not silently make it the active source.
            if (activeUrl != null && activeUrl != url) store.setActive(activeUrl)
            result.fold(
                onSuccess = { report(true, context.getString(R.string.luna_live_source_refreshed, it)) },
                onFailure = { report(false, context.getString(R.string.luna_live_source_failed, it.message ?: "")) },
            )
        }
    }

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.075f)).padding(16.dp),
        ) {
            BasicText(stringResource(R.string.luna_live_sources_title), style = Type.Title)
            Spacer(Modifier.height(4.dp))
            BasicText(
                stringResource(R.string.luna_live_sources_note),
                style = Type.Caption.copy(color = Palette.SecondaryLabel, lineHeight = 18.sp),
            )
            Spacer(Modifier.height(12.dp))
            if (sources.isEmpty()) {
                BasicText(
                    stringResource(R.string.luna_live_sources_empty),
                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            sources.forEachIndexed { index, source ->
                SourceCard(
                    source = source,
                    busy = busy,
                    deleting = pendingDelete == source.url,
                    onActivate = {
                        store.setActive(source.url)
                        pendingDelete = null
                        report(true, context.getString(R.string.luna_live_source_activated, source.name))
                    },
                    onRefresh = { refreshSource(source.url) }.takeIf { source.url.startsWith("http://") || source.url.startsWith("https://") },
                    onDelete = {
                        if (pendingDelete == source.url) {
                            store.removeSource(source.url)
                            pendingDelete = null
                            report(true, context.getString(R.string.luna_live_source_removed, source.name))
                        } else {
                            pendingDelete = source.url
                        }
                    },
                )
                if (index != sources.lastIndex) Spacer(Modifier.height(10.dp))
            }
            message?.let {
                Spacer(Modifier.height(10.dp))
                BasicText(
                    it,
                    style = Type.Caption.copy(color = if (messageOk) Palette.Green else Palette.Orange),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsCard(stringResource(R.string.luna_live_subscription)) {
            SourceField(address, { address = it }, stringResource(R.string.luna_live_url_hint))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    MenuRow(
                        Glyph.Download,
                        stringResource(if (busy) R.string.luna_live_source_working else R.string.luna_live_subscribe),
                        onClick = ::subscribe,
                    )
                }
                Box(Modifier.weight(1f)) {
                    MenuRow(Glyph.Globe, stringResource(R.string.luna_live_default_source), onClick = ::defaultSource)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsCard(stringResource(R.string.luna_live_direct)) {
            SourceField(directName, { directName = it }, stringResource(R.string.luna_live_name_hint))
            Spacer(Modifier.height(6.dp))
            SourceField(directUrl, { directUrl = it }, stringResource(R.string.luna_live_direct_hint))
            Spacer(Modifier.height(8.dp))
            MenuRow(Glyph.Play, stringResource(R.string.luna_live_add_direct), onClick = ::addDirect)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.075f)).padding(16.dp),
    ) {
        BasicText(title, style = Type.Title)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SourceCard(
    source: LiveStore.SourceInfo,
    busy: Boolean,
    deleting: Boolean,
    onActivate: () -> Unit,
    onRefresh: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val date = remember(source.updated) {
        if (source.updated <= 0L) "—"
        else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(source.updated))
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (source.active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.045f))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(source.name, style = Type.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (source.active) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).background(Palette.Green.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    BasicText(stringResource(R.string.luna_live_source_active), style = Type.Caption.copy(color = Palette.Green))
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        BasicText(
            source.url,
            style = Type.Caption.copy(color = Palette.SecondaryLabel),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            stringResource(R.string.luna_live_source_meta, source.channels, date),
            style = Type.Caption.copy(color = Palette.TertiaryLabel),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!source.active) {
                Box(Modifier.weight(1f)) {
                    MenuRow(Glyph.Play, stringResource(R.string.luna_live_source_activate), onClick = onActivate, rowHeight = 40.dp)
                }
            }
            if (onRefresh != null) {
                Box(Modifier.weight(1f)) {
                    MenuRow(Glyph.Download, stringResource(R.string.luna_live_source_refresh), onClick = onRefresh, rowHeight = 40.dp)
                }
            }
            Box(Modifier.weight(1f)) {
                MenuRow(
                    Glyph.Trash,
                    stringResource(if (deleting) R.string.luna_live_source_remove_confirm else R.string.luna_live_source_remove),
                    onClick = onDelete,
                    rowHeight = 40.dp,
                    destructive = true,
                )
            }
        }
        if (busy) {
            Spacer(Modifier.height(4.dp))
            BasicText(stringResource(R.string.luna_live_source_working), style = Type.Caption.copy(color = Palette.SecondaryLabel))
        }
    }
}

@Composable
private fun SourceField(value: String, onValueChange: (String) -> Unit, hint: String) {
    Box(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            BasicText(hint, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 1)
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
