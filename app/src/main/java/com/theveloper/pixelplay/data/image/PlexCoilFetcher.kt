package com.theveloper.pixelplay.data.image

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.theveloper.pixelplay.data.plex.PlexApiClient
import com.theveloper.pixelplay.data.plex.PlexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import javax.inject.Inject

class PlexCoilFetcher(
    private val uri: Uri, private val options: Options,
    private val repository: PlexRepository, private val api: PlexApiClient
) : Fetcher {
    override suspend fun fetch(): SourceResult? = withContext(Dispatchers.IO) {
        val machine = uri.host ?: return@withContext null
        val key = uri.pathSegments.singleOrNull() ?: return@withContext null
        val request = repository.artworkRequest(machine, key) ?: return@withContext null
        api.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            SourceResult(ImageSource(Buffer().write(response.body.bytes()), options.context),
                response.header("Content-Type"), DataSource.NETWORK)
        }
    }

    class Factory @Inject constructor(private val repository: PlexRepository, private val api: PlexApiClient) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == "plex_cover") PlexCoilFetcher(data, options, repository, api) else null
    }
}
