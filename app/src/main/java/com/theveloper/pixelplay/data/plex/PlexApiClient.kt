package com.theveloper.pixelplay.data.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlexApiClient @Inject constructor() {
    private val clientIdentifier = java.util.UUID.randomUUID().toString()
    // No logging interceptor and no redirects: Plex credentials stay on the configured origin.
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    fun request(connection: PlexConnection, url: HttpUrl): Request {
        require(PlexUris.sameOrigin(url, connection.server)) { "Plex request must stay on the connected server" }
        return Request.Builder().url(url)
            .header("X-Plex-Token", connection.token)
            .header("X-Plex-Product", "PixelPlayerONE")
            .header("X-Plex-Client-Identifier", clientIdentifier)
            .header("Accept", "application/json").build()
    }

    suspend fun container(connection: PlexConnection, url: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        httpClient.newCall(request(connection, url)).execute().use { response ->
            check(response.isSuccessful) {
                when (response.code) {
                    401, 403 -> "Plex rejected the token or denied access to this library"
                    else -> "Plex request failed (HTTP ${response.code})"
                }
            }
            JSONObject(response.body.string()).getJSONObject("MediaContainer")
        }
    }

    suspend fun libraries(connection: PlexConnection): List<PlexLibrary> {
        val directories = container(connection, connection.server.resolve("/library/sections")!!)
            .optJSONArray("Directory") ?: return emptyList()
        return (0 until directories.length()).mapNotNull { index ->
            directories.getJSONObject(index).let { item ->
                if (item.optString("type") == "artist") PlexLibrary(item.getString("key"), item.getString("title"))
                else null
            }
        }
    }

    suspend fun tracks(connection: PlexConnection, library: PlexLibrary, progress: (Int) -> Unit): List<PlexTrack> {
        require(PlexUris.validRatingKey(library.key)) { "Invalid Plex library key" }
        val result = mutableListOf<PlexTrack>()
        val seenKeys = mutableSetOf<String>()
        var offset = 0
        var total: Int? = null
        do {
            val url = connection.server.newBuilder().encodedPath("/library/sections/${library.key}/all")
                .addQueryParameter("type", "10").addQueryParameter("sort", "titleSort:asc")
                .addQueryParameter("X-Plex-Container-Start", offset.toString())
                .addQueryParameter("X-Plex-Container-Size", "500").build()
            val page = container(connection, url)
            if (page.has("totalSize")) {
                val pageTotal = page.getInt("totalSize")
                check(total == null || total == pageTotal) { "Plex library changed during sync; try syncing again" }
                total = pageTotal
            }
            check(!page.has("offset") || page.getInt("offset") == offset) { "Plex returned an unexpected page offset" }
            val items = page.optJSONArray("Metadata")
            val count = items?.length() ?: 0
            check(count > 0 || total == null || offset >= total) { "Plex returned an incomplete library; try syncing again" }
            if (count == 0) break
            repeat(count) {
                val track = parseTrack(items!!.getJSONObject(it))
                check(seenKeys.add(track.ratingKey)) { "Plex returned duplicate tracks during sync; try syncing again" }
                result.add(track)
            }
            offset += count
            progress(offset)
        } while (total == null || offset < total)
        check(total == null || result.size == total) { "Plex returned an incomplete library; try syncing again" }
        return result
    }

    companion object {
        fun parseTrack(item: JSONObject): PlexTrack {
            val key = item.getString("ratingKey")
            require(PlexUris.validRatingKey(key)) { "Invalid Plex track key" }
            val media = item.optJSONArray("Media")?.optJSONObject(0)
            val stream = media?.optJSONArray("Part")?.optJSONObject(0)?.optJSONArray("Stream")
            val audio = stream?.let { list ->
                (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optInt("streamType") == 2 }
            }
            return PlexTrack(
                ratingKey = key, title = item.optString("title", "Untitled"),
                artist = item.optString("grandparentTitle", "Unknown Artist"),
                artistKey = item.optString("grandparentRatingKey", item.optString("grandparentTitle", "Unknown Artist")),
                album = item.optString("parentTitle", "Unknown Album"),
                albumKey = item.optString("parentRatingKey", "${item.optString("grandparentTitle")}:${item.optString("parentTitle")}"),
                duration = item.optLong("duration").coerceAtLeast(0), trackNumber = item.optInt("index"),
                discNumber = item.optInt("parentIndex").takeIf { it > 0 }, year = item.optInt("year"),
                addedAt = item.optLong("addedAt") * 1000,
                genre = item.optJSONArray("Genre")?.optJSONObject(0)?.optString("tag"),
                bitrate = media?.optInt("bitrate")?.takeIf { it > 0 }?.times(1000),
                sampleRate = audio?.optInt("samplingRate")?.takeIf { it > 0 }
            )
        }
    }
}
