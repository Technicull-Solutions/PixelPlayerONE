@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.plex

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theveloper.pixelplay.data.database.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlexRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PlexApiClient,
    private val musicDao: MusicDao
) {
    // Fail closed if the keystore is unavailable; do not silently write plaintext tokens.
    private val prefs by lazy {
        EncryptedSharedPreferences.create(context, "plex_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val mutex = Mutex()
    @Volatile private var active: PlexConnection? = null
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    val songCount = musicDao.getPlexSongCount()
    val serverUrl: String? get() = active?.server?.toString()

    suspend fun restore() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (active != null) return@withLock
            val server = prefs.getString("server", null) ?: return@withLock
            val token = prefs.getString("token", null) ?: return@withLock
            val machine = prefs.getString("machine", null) ?: return@withLock
            require(PlexUris.validMachineId(machine) && token.isNotBlank()) { "Invalid saved Plex connection" }
            active = PlexConnection(PlexUris.serverUrl(server), token, machine)
            _connected.value = true
        }
    }

    suspend fun connect(server: String, token: String): List<PlexLibrary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(token.isNotBlank() && !token.contains('\n') && !token.contains('\r')) { "Enter a valid Plex token" }
            val provisional = PlexConnection(PlexUris.serverUrl(server), token.trim(), "pending")
            val identity = api.container(provisional, provisional.server.resolve("/")!!)
            val machine = identity.getString("machineIdentifier")
            require(PlexUris.validMachineId(machine)) { "Plex returned an invalid server identity" }
            val connection = PlexConnection(provisional.server, provisional.token, machine)
            val libraries = api.libraries(connection)
            require(libraries.isNotEmpty()) { "This server has no accessible music libraries" }
            // Commit credentials only after validation succeeds. Existing imports remain until sync succeeds.
            check(prefs.edit().putString("server", connection.server.toString())
                .putString("token", connection.token).putString("machine", machine).commit()) {
                "Could not save the Plex connection"
            }
            active = connection
            _connected.value = true
            libraries
        }
    }

    suspend fun libraries(): List<PlexLibrary> {
        restore()
        return api.libraries(requireConnection())
    }

    suspend fun sync(progress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        restore()
        mutex.withLock {
            val connection = requireConnection()
            val libraries = api.libraries(connection)
            require(libraries.isNotEmpty()) { "No accessible music libraries; existing imports have been kept" }
            val tracks = libraries.flatMap { library ->
                api.tracks(connection, library) { progress("${library.title}: $it tracks read") }
            }.distinctBy { it.ratingKey }
            val songs = tracks.map { track ->
                SongEntity(
                    id = PlexUris.databaseId(connection.machineId, 1, track.ratingKey), title = track.title,
                    artistName = track.artist, artistId = PlexUris.databaseId(connection.machineId, 3, track.artistKey),
                    albumName = track.album, albumId = PlexUris.databaseId(connection.machineId, 2, track.albumKey),
                    albumArtist = track.artist,
                    contentUriString = PlexUris.track(connection.machineId, track.ratingKey),
                    albumArtUriString = PlexUris.artwork(connection.machineId, track.ratingKey),
                    duration = track.duration, genre = track.genre,
                    filePath = "/Cloud/Plex/${connection.machineId}/${track.ratingKey}",
                    parentDirectoryPath = "/Cloud/Plex", trackNumber = track.trackNumber,
                    discNumber = track.discNumber, year = track.year, dateAdded = track.addedAt,
                    bitrate = track.bitrate, sampleRate = track.sampleRate, sourceType = SourceType.PLEX
                )
            }
            val albums = songs.groupBy { it.albumId }.map { (id, group) ->
                val song = group.first()
                AlbumEntity(id, song.albumName, song.artistName, song.artistId,
                    song.albumArtUriString, group.size, group.maxOf { it.dateAdded }, song.year, song.albumArtist)
            }
            val artists = songs.groupBy { it.artistId }.map { (id, group) ->
                ArtistEntity(id, group.first().artistName, group.size)
            }
            val crossRefs = songs.map { SongArtistCrossRef(it.id, it.artistId, true) }
            val currentIds = songs.map { it.id }.toSet()
            val deleted = musicDao.getAllPlexSongIds().filter { it !in currentIds }
            // One Room transaction after all pages succeed. Network errors cannot erase the library.
            musicDao.incrementalSyncMusicData(songs, albums, artists, crossRefs, deleted)
            songs.size
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(prefs.edit().clear().commit()) { "Could not clear the Plex connection" }
            active = null
            _connected.value = false
            musicDao.deleteSongsAndRelatedData(musicDao.getAllPlexSongIds())
        }
    }

    private fun requireConnection() = active ?: error("Connect to Plex first")

    suspend fun streamUrl(machine: String, key: String): String {
        restore()
        val connection = requireConnection()
        require(machine == connection.machineId && PlexUris.validRatingKey(key)) { "This track belongs to a different Plex server; sync again" }
        val metadata = api.container(connection, connection.server.resolve("/library/metadata/$key")!!)
            .getJSONArray("Metadata").getJSONObject(0)
        val part = metadata.getJSONArray("Media").getJSONObject(0).getJSONArray("Part").getJSONObject(0)
        val path = part.getString("key")
        require(path.startsWith("/library/parts/")) { "Invalid Plex audio path" }
        val url = connection.server.resolve(path) ?: error("Invalid Plex audio URL")
        require(PlexUris.sameOrigin(url, connection.server)) { "Plex audio must stay on the connected server" }
        return url.toString()
    }

    fun authenticatedRequest(url: String) = api.request(requireConnection(),
        url.toHttpUrl()).newBuilder().header("Accept", "*/*").build()

    fun isAllowedStreamUrl(url: String): Boolean {
        val connection = active ?: return false
        val candidate = url.toHttpUrlOrNull() ?: return false
        return PlexUris.sameOrigin(candidate, connection.server) && candidate.encodedPath.startsWith("/library/parts/")
    }

    suspend fun artworkRequest(machine: String, key: String): okhttp3.Request? {
        restore()
        val connection = active ?: return null
        if (machine != connection.machineId || !PlexUris.validRatingKey(key)) return null
        // Resolve artwork at fetch time so stale server addresses and tokens never enter the image cache key.
        val metadata = api.container(connection, connection.server.resolve("/library/metadata/$key")!!)
            .getJSONArray("Metadata").getJSONObject(0)
        val path = metadata.optString("thumb").ifBlank { metadata.optString("parentThumb") }
        if (!path.startsWith("/library/metadata/")) return null
        return api.request(connection, connection.server.resolve(path) ?: return null)
            .newBuilder().header("Accept", "image/*").build()
    }
}
