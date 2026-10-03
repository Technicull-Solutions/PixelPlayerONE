package com.theveloper.pixelplay.data.plex

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Tokens never belong in library URIs, artwork URIs, or database rows. */
class PlexConnection(val server: HttpUrl, val token: String, val machineId: String) {
    override fun toString() = "PlexConnection(server=$server, machineId=$machineId)"
}

data class PlexLibrary(val key: String, val title: String)

data class PlexTrack(
    val ratingKey: String,
    val title: String,
    val artist: String,
    val artistKey: String,
    val album: String,
    val albumKey: String,
    val duration: Long,
    val trackNumber: Int,
    val discNumber: Int?,
    val year: Int,
    val addedAt: Long,
    val genre: String?,
    val bitrate: Int?,
    val sampleRate: Int?
)

object PlexUris {
    private val machinePattern = Regex("[A-Za-z0-9_-]{1,100}")
    private val keyPattern = Regex("[0-9]{1,20}")

    fun validMachineId(value: String) = machinePattern.matches(value)
    fun validRatingKey(value: String) = keyPattern.matches(value)

    fun serverUrl(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a full server URL, such as http://192.168.1.10:32400")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Enter the server URL without credentials, query parameters, or a fragment"
        }
        require(url.encodedPath == "/") { "Enter the server origin without a path" }
        require(url.host !in setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")) {
            "Use the Plex server's network address, not this phone's localhost address"
        }
        return url
    }

    fun track(machine: String, key: String): String {
        require(validMachineId(machine) && validRatingKey(key))
        return "plex://$machine/$key"
    }

    fun artwork(machine: String, key: String) = track(machine, key).replace("plex://", "plex_cover://")

    /** Stable 56-bit hashes in reserved negative namespaces, independent of server URL. */
    fun databaseId(machine: String, kind: Int, key: String): Long {
        require(validMachineId(machine) && kind in 1..3)
        val digest = MessageDigest.getInstance("SHA-256").digest("$machine:$kind:$key".toByteArray(Charsets.UTF_8))
        val hash = digest.take(7).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 255) }
        return -((kind.toLong() shl 56) or hash)
    }

    fun sameOrigin(candidate: HttpUrl, server: HttpUrl): Boolean =
        candidate.scheme == server.scheme && candidate.host == server.host && candidate.port == server.port &&
            candidate.username.isEmpty() && candidate.password.isEmpty()
}
