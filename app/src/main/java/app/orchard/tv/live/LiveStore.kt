package app.orchard.tv.live

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A small, self-contained version of OneTV's LiveRepository: subscriptions, direct links,
 * favourites and the last played channel. Everything lives in one JSON file so the live screen
 * can be opened and closed without a service.
 */
class LiveStore(context: Context) {
    data class Source(val url: String, val name: String, val channels: List<LiveChannel>, val updated: Long)

    /** What the settings page shows for each saved source. */
    data class SourceInfo(val url: String, val name: String, val channels: Int, val active: Boolean, val updated: Long)

    private val file = AtomicFile(File(context.filesDir, "luna-live.json"))
    private val sources = mutableListOf<Source>()
    private val favorites = mutableSetOf<String>()
    /** Last working line per channel, so repeated zapping skips dead lines. */
    private val lines = mutableMapOf<String, String>()
    private var active = "local"
    private var lastChannel = ""

    init {
        load()
    }

    private fun load() {
        try {
            val text = file.readFully().toString(Charsets.UTF_8)
            val root = JSONObject(text)
            active = root.optString("active", "local")
            lastChannel = root.optString("lastChannel")
            root.optJSONArray("sources")?.let { list ->
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val channels = mutableListOf<LiveChannel>()
                    item.optJSONArray("channels")?.let { rows ->
                        for (j in 0 until rows.length()) {
                            val row = rows.optJSONObject(j) ?: continue
                            val streams = mutableListOf<String>()
                            row.optJSONArray("streams")?.let { urls ->
                                for (k in 0 until urls.length()) {
                                    PlaylistParser.streamUrl(urls.optString(k))?.let(streams::add)
                                }
                            }
                            if (streams.isNotEmpty()) {
                                channels += LiveChannel(
                                    row.optString("id"),
                                    row.optString("name"),
                                    row.optString("group"),
                                    streams,
                                )
                            }
                        }
                    }
                    sources += Source(item.optString("url"), item.optString("name"), channels, item.optLong("updated"))
                }
            }
            root.optJSONArray("favorites")?.let { list ->
                for (i in 0 until list.length()) favorites += list.optString(i)
            }
            root.optJSONObject("lines")?.let { saved ->
                saved.keys().forEach { id -> saved.optString(id)?.takeIf { it.isNotBlank() }?.let { lines[id] = it } }
            }
        } catch (_: Exception) {
            // A half-written file must not keep live TV from opening.
        }
        if (sources.none { it.url == active }) active = sources.firstOrNull()?.url ?: "local"
    }

    private fun save() {
        // Snapshot cheaply on the caller, then build and write the whole JSON on the single
        // writer thread. Zapping a channel must not serialise 150 channels on the UI thread.
        val sourcesSnapshot = sources.toList()
        val favoritesSnapshot = favorites.toList()
        val linesSnapshot = lines.toMap()
        val activeSnapshot = active
        val lastSnapshot = lastChannel
        writer.execute {
            val text = runCatching {
                val sourcesJson = JSONArray()
                sourcesSnapshot.forEach { source ->
                    sourcesJson.put(
                        JSONObject()
                            .put("url", source.url)
                            .put("name", source.name)
                            .put("updated", source.updated)
                            .put("channels", JSONArray(source.channels.map { it.json() })),
                    )
                }
                JSONObject()
                    .put("active", activeSnapshot)
                    .put("sources", sourcesJson)
                    .put("favorites", JSONArray(favoritesSnapshot))
                    .put("lines", JSONObject().apply { linesSnapshot.forEach { (id, url) -> put(id, url) } })
                    .put("lastChannel", lastSnapshot)
                    .toString()
            }.getOrNull() ?: return@execute
            val out = file.startWrite()
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (_: Exception) {
                file.failWrite(out)
            }
        }
    }

    val isEmpty: Boolean get() = sources.none { it.channels.isNotEmpty() }

    fun sourceNames(): List<String> = sources.filter { it.channels.isNotEmpty() }.map { it.name }

    fun sourcesInfo(): List<SourceInfo> = sources.map {
        SourceInfo(it.url, it.name, it.channels.size, it.url == active, it.updated)
    }

    fun activeName(): String = sources.firstOrNull { it.url == active }?.name.orEmpty()

    fun setActive(url: String) {
        if (sources.none { it.url == url }) return
        active = url
        save()
    }

    fun preferredLine(channel: LiveChannel): Int {
        val url = lines[channel.id] ?: return 0
        return channel.streams.indexOfFirst { it == url }.coerceAtLeast(0)
    }

    fun rememberLine(channel: LiveChannel, url: String) {
        if (lines[channel.id] == url) return
        lines[channel.id] = url
        save()
    }

    fun channels(): List<LiveChannel> {
        if (sources.none { it.url == active }) active = sources.firstOrNull { it.channels.isNotEmpty() }?.url ?: "local"
        return sources.firstOrNull { it.url == active }?.channels.orEmpty()
    }

    fun favorites(): List<LiveChannel> = sources.flatMap { it.channels }.filter { favorites.contains(it.id) }.distinctBy { it.id }

    fun isFavorite(id: String): Boolean = favorites.contains(id)

    fun resumeChannel(): LiveChannel? {
        val all = sources.flatMap { it.channels }
        all.firstOrNull { it.id == lastChannel }?.let { channel ->
            sources.firstOrNull { source -> source.channels.any { it.id == channel.id } }?.let { active = it.url }
            return channel
        }
        return channels().firstOrNull() ?: all.firstOrNull()
    }

    fun remember(channel: LiveChannel) {
        if (channel.id == "cast") return
        lastChannel = channel.id
        sources.firstOrNull { source -> source.channels.any { it.id == channel.id } }?.let { active = it.url }
        save()
    }

    fun toggleFavorite(id: String) {
        if (!favorites.add(id)) favorites.remove(id)
        save()
    }

    fun removeSource(url: String) {
        sources.removeAll { it.url == url }
        if (active == url) active = sources.firstOrNull { it.channels.isNotEmpty() }?.url ?: "local"
        save()
    }

    fun importText(text: String, name: String = "本地导入"): Result<Int> = runCatching {
        val channels = PlaylistParser.parse(text, "local")
        require(channels.isNotEmpty()) { "没有找到可用频道" }
        val previous = sources.firstOrNull { it.url == "local" }?.channels.orEmpty()
        replace(Source("local", name, (previous + channels).distinctBy { it.id }, System.currentTimeMillis()))
        channels.size
    }

    fun addDirect(name: String, url: String): Result<Int> = runCatching {
        val stream = PlaylistParser.streamUrl(url) ?: error("请输入 HTTP、HTTPS、RTSP 或 UDP 直播地址")
        val text = JSONArray().put(
            JSONObject()
                .put("name", name.ifBlank { "自定义直播" })
                .put("url", stream),
        ).toString()
        importText(text, "自定义频道").getOrThrow()
    }

    fun addSubscription(address: String): Result<Int> = runCatching {
        var next = PlaylistParser.playlistAddress(address) ?: error("请输入 HTTP / HTTPS 节目单链接")
        val original = next
        var body: String? = null
        var attempt = 0
        while (attempt++ < 6) {
            val connection = (URL(next).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "LunaTV Live")
            }
            try {
                connection.connect()
                if (connection.responseCode in 300..399) {
                    require(attempt < 5) { "重定向过多" }
                    val location = connection.getHeaderField("Location") ?: error("重定向地址缺失")
                    next = PlaylistParser.httpUrl(URL(URL(next), location).toString())
                        ?: error("订阅跳转到不支持的地址")
                    continue
                }
                require(connection.responseCode in 200..299) {
                    "订阅更新失败 (${connection.responseCode})"
                }
                require((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) <= PlaylistParser.MAX_BYTES) {
                    "节目单超过 20 MB"
                }
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= PlaylistParser.MAX_BYTES) { "节目单超过 20 MB" }
                        out.write(buffer, 0, n)
                    }
                    body = out.toString("UTF-8")
                }
            } finally {
                connection.disconnect()
            }
            break
        }
        val text = body ?: error("订阅读取失败")
        val channels = PlaylistParser.parse(text, original, next)
        require(channels.isNotEmpty()) { "节目单没有可用频道" }
        val label = sources.firstOrNull { it.url == original }?.name ?: runCatching { URL(original).host }.getOrDefault("直播订阅")
        replace(Source(original, label, channels, System.currentTimeMillis()))
        channels.size
    }

    private fun replace(source: Source) {
        require(sources.size < 10 || sources.any { it.url == source.url }) { "最多保存 10 个直播源" }
        val total = source.channels.size + sources.filter { it.url != source.url }.sumOf { it.channels.size }
        require(total <= PlaylistParser.MAX_CHANNELS) { "总频道数最多 20000" }
        sources.removeAll { it.url == source.url }
        sources += source
        active = source.url
        save()
    }

    companion object {
        /** Channel zaps must never wait for a full playlist rewrite, and every screen instance
         *  shares one writer so opening live repeatedly cannot leak idle threads. */
        private val writer = Executors.newSingleThreadExecutor()

        /** A public playlist gives the button something to show on first use; it stays editable. */
        const val DEFAULT_SOURCE = "https://iptv-org.github.io/iptv/countries/cn.m3u"
    }
}
