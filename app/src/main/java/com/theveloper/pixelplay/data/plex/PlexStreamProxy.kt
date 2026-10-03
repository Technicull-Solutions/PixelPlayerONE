package com.theveloper.pixelplay.data.plex

import android.net.Uri
import com.theveloper.pixelplay.data.stream.CloudStreamProxy
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlexStreamProxy @Inject constructor(
    private val repository: PlexRepository,
    api: PlexApiClient
) : CloudStreamProxy<String>(api.httpClient) {
    override val allowedHostSuffixes = emptySet<String>()
    override val cacheExpirationMs = 0L // Validate current credentials and media part on each request.
    override val proxyTag = "PlexStreamProxy"
    override val routePath = "/plex/{track}"
    override val routeParamName = "track"
    override val uriScheme = "plex"
    override val routePrefix = "/plex"
    override fun parseRouteParam(value: String) = value.takeIf { validateId(it) }
    override fun validateId(id: String): Boolean {
        val parts = id.split('~')
        return parts.size == 2 && PlexUris.validMachineId(parts[0]) && PlexUris.validRatingKey(parts[1])
    }
    override fun formatIdForUrl(id: String) = id
    override fun extractIdFromUri(uri: Uri): String? {
        val machine = uri.host ?: return null
        val key = uri.pathSegments.singleOrNull() ?: return null
        return "$machine~$key".takeIf { validateId(it) }
    }
    override suspend fun resolveStreamUrl(id: String): String {
        val (machine, key) = id.split('~')
        return repository.streamUrl(machine, key)
    }
    override fun isAllowedStreamUrl(url: String) = repository.isAllowedStreamUrl(url)
    override fun upstreamRequest(url: String): Request.Builder = repository.authenticatedRequest(url).newBuilder()
}
