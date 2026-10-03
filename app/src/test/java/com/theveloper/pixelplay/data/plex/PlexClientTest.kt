package com.theveloper.pixelplay.data.plex

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlexClientTest {
    @Test
    fun `server URLs reject embedded credentials and token query strings`() {
        listOf("https://user:secret@plex.example", "https://plex.example?X-Plex-Token=secret",
            "https://plex.example/path", "file:///music", "http://localhost:32400").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { PlexUris.serverUrl(url) }
        }
        assertEquals("http://192.168.1.10:32400/", PlexUris.serverUrl("http://192.168.1.10:32400").toString())
    }

    @Test
    fun `track identities distinguish servers and entity kinds and remain negative`() {
        val ids = (1..3).flatMap { kind -> listOf("serverA", "serverB").map { PlexUris.databaseId(it, kind, "42") } }
        assertEquals(6, ids.toSet().size)
        assertTrue(ids.all { it < -10_000_000_000_000L })
        assertEquals(PlexUris.databaseId("serverA", 1, "42"), PlexUris.databaseId("serverA", 1, "42"))
        assertEquals("plex://serverA/42", PlexUris.track("serverA", "42"))
        assertThrows(IllegalArgumentException::class.java) { PlexUris.track("serverA", "../42") }
    }

    @Test
    fun `authenticated requests keep tokens in headers and enforce exact origin`() {
        val api = PlexApiClient()
        val connection = PlexConnection("https://plex.example:32400".toHttpUrl(), "test-token", "serverA")
        val request = api.request(connection, "https://plex.example:32400/library/metadata/42".toHttpUrl())
        assertEquals("test-token", request.header("X-Plex-Token"))
        assertFalse(request.url.toString().contains("test-token"))
        assertFalse(connection.toString().contains("test-token"))
        assertFalse(api.httpClient.followRedirects)
        listOf("https://other.plex.example:32400/", "http://plex.example:32400/", "https://plex.example:443/").forEach {
            assertThrows(IllegalArgumentException::class.java) { api.request(connection, it.toHttpUrl()) }
        }
    }

    @Test
    fun `Plex audio metadata preserves disc duration and audio properties`() {
        val track = PlexApiClient.parseTrack(JSONObject("""{
            "ratingKey":"42", "title":"Song", "grandparentTitle":"Artist", "grandparentRatingKey":"5",
            "parentTitle":"Album", "parentRatingKey":"6", "duration":213456, "index":3, "parentIndex":2,
            "addedAt":1700000000, "year":2024, "Genre":[{"tag":"Rock"}],
            "Media":[{"bitrate":921,"Part":[{"Stream":[{"streamType":2,"samplingRate":44100}]}]}]
        }"""))
        assertEquals(213456L, track.duration)
        assertEquals(2, track.discNumber)
        assertEquals(1700000000000L, track.addedAt)
        assertEquals(921000, track.bitrate)
        assertEquals(44100, track.sampleRate)
        assertEquals("Rock", track.genre)
        val sparse = PlexApiClient.parseTrack(JSONObject("""{"ratingKey":"43"}"""))
        assertEquals("Unknown Artist", sparse.artist)
        assertNull(sparse.discNumber)
        assertNull(sparse.bitrate)
    }

    @Test
    fun `paginated imports send the next offset and include every track`() = runBlocking {
        withServer(listOf(
            """{"MediaContainer":{"totalSize":2,"offset":0,"Metadata":[{"ratingKey":"1"}]}}""",
            """{"MediaContainer":{"totalSize":2,"offset":1,"Metadata":[{"ratingKey":"2"}]}}"""
        )) { connection, requests ->
            val tracks = PlexApiClient().tracks(connection, PlexLibrary("5", "Music")) {}
            assertEquals(listOf("1", "2"), tracks.map { it.ratingKey })
            assertTrue(requests[1].contains("X-Plex-Container-Start=1"))
            assertTrue(requests.all { it.contains("X-Plex-Token: test-token", ignoreCase = true) })
        }
    }

    @Test
    fun `truncated pages fail instead of silently replacing the library`() = runBlocking {
        withServer(listOf(
            """{"MediaContainer":{"totalSize":2,"offset":0,"Metadata":[{"ratingKey":"1"}]}}""",
            """{"MediaContainer":{"totalSize":2,"offset":1,"Metadata":[]}}"""
        )) { connection, _ ->
            var failed = false
            try { PlexApiClient().tracks(connection, PlexLibrary("5", "Music")) {} }
            catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
        }
    }

    @Test
    fun `servers ignoring pagination cannot cause an endless import`() = runBlocking {
        withServer(listOf(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"1"}]}}""",
            """{"MediaContainer":{"Metadata":[{"ratingKey":"1"}]}}"""
        )) { connection, _ ->
            var failed = false
            try { PlexApiClient().tracks(connection, PlexLibrary("5", "Music")) {} }
            catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
        }
    }

    @Test
    fun `library changes between pages abort the import`() = runBlocking {
        withServer(listOf(
            """{"MediaContainer":{"totalSize":2,"offset":0,"Metadata":[{"ratingKey":"1"}]}}""",
            """{"MediaContainer":{"totalSize":3,"offset":1,"Metadata":[{"ratingKey":"2"}]}}"""
        )) { connection, _ ->
            var failed = false
            try { PlexApiClient().tracks(connection, PlexLibrary("5", "Music")) {} }
            catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
        }
    }

    private suspend fun withServer(pages: List<String>, test: suspend (PlexConnection, List<String>) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
            val executor = Executors.newSingleThreadExecutor()
            val serving = executor.submit {
                pages.forEach { page ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            lines.add(line)
                        }
                        requests.add(lines.joinToString("\n"))
                        val bytes = page.toByteArray()
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                    }
                }
            }
            try {
                test(PlexConnection("http://127.0.0.1:${server.localPort}".toHttpUrl(), "test-token", "serverA"), requests)
                serving.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
