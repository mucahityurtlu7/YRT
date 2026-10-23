package com.yrt.music.data.repository

import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.network.YouTubeMusicClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface contract for YouTube Music discovery operations (R1, R2).
 */
interface YouTubeMusicService {
    suspend fun searchArtists(query: String): Result<List<ArtistSearchResult>>
    suspend fun searchAlbums(query: String): Result<List<AlbumSearchResult>>
    suspend fun getArtistProfile(browseId: String): Result<ArtistProfile>
    suspend fun getArtistCatalog(browseId: String): Result<ArtistProfile> = getArtistProfile(browseId)
    suspend fun getAlbumDetails(browseId: String): Result<AlbumDetails>
}

/**
 * Repository providing cached and resilient access to YouTube Music data.
 */
class YouTubeMusicRepository(
    private val client: YouTubeMusicClient = YouTubeMusicClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : YouTubeMusicService {

    // In-memory caches to prevent redundant network requests during navigation
    private val artistSearchCache = ConcurrentHashMap<String, List<ArtistSearchResult>>()
    private val albumSearchCache = ConcurrentHashMap<String, List<AlbumSearchResult>>()
    private val artistProfileCache = ConcurrentHashMap<String, ArtistProfile>()
    private val albumDetailsCache = ConcurrentHashMap<String, AlbumDetails>()

    override suspend fun searchArtists(query: String): Result<List<ArtistSearchResult>> =
        withContext(ioDispatcher) {
            val normalized = query.trim().lowercase()
            if (normalized.isBlank()) {
                return@withContext Result.success(emptyList())
            }

            artistSearchCache[normalized]?.let { cached ->
                return@withContext Result.success(cached)
            }

            try {
                val results = client.searchArtists(query.trim())
                artistSearchCache[normalized] = results
                Result.success(results)
            } catch (e: IOException) {
                Result.failure(IOException("Network error searching artists: ${e.message}", e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun searchAlbums(query: String): Result<List<AlbumSearchResult>> =
        withContext(ioDispatcher) {
            val normalized = query.trim().lowercase()
            if (normalized.isBlank()) {
                return@withContext Result.success(emptyList())
            }

            albumSearchCache[normalized]?.let { cached ->
                return@withContext Result.success(cached)
            }

            try {
                val results = client.searchAlbums(query.trim())
                albumSearchCache[normalized] = results
                Result.success(results)
            } catch (e: IOException) {
                Result.failure(IOException("Network error searching albums: ${e.message}", e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getArtistProfile(browseId: String): Result<ArtistProfile> =
        withContext(ioDispatcher) {
            val cleanId = browseId.trim()
            if (cleanId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("browseId cannot be blank"))
            }

            artistProfileCache[cleanId]?.let { cached ->
                return@withContext Result.success(cached)
            }

            try {
                val profile = client.getArtistProfile(cleanId)
                if (profile.name.isNotBlank()) {
                    artistProfileCache[cleanId] = profile
                }
                Result.success(profile)
            } catch (e: IOException) {
                // If offline and we have a cached copy, return it
                artistProfileCache[cleanId]?.let { return@withContext Result.success(it) }
                Result.failure(IOException("Network error fetching artist profile: ${e.message}", e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getArtistCatalog(browseId: String): Result<ArtistProfile> =
        withContext(ioDispatcher) {
            val cleanId = browseId.trim()
            if (cleanId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("browseId cannot be blank"))
            }
            artistProfileCache[cleanId]?.takeIf { it.isCatalogComplete }?.let {
                return@withContext Result.success(it)
            }
            try {
                val catalog = client.getArtistCatalog(cleanId)
                artistProfileCache[cleanId] = catalog
                Result.success(catalog)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never return the cached five-song preview as a successful full catalog.
                Result.failure(e)
            }
        }

    override suspend fun getAlbumDetails(browseId: String): Result<AlbumDetails> =
        withContext(ioDispatcher) {
            val cleanId = browseId.trim()
            if (cleanId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("browseId cannot be blank"))
            }

            albumDetailsCache[cleanId]?.let { cached ->
                return@withContext Result.success(cached)
            }

            try {
                val details = client.getAlbumDetails(cleanId)
                if (details.title.isNotBlank()) {
                    albumDetailsCache[cleanId] = details
                }
                Result.success(details)
            } catch (e: IOException) {
                // If offline and we have a cached copy, return it
                albumDetailsCache[cleanId]?.let { return@withContext Result.success(it) }
                Result.failure(IOException("Network error fetching album details: ${e.message}", e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Clears all in-memory caches. Useful on user pull-to-refresh or low-memory warnings.
     */
    fun clearCache() {
        artistSearchCache.clear()
        albumSearchCache.clear()
        artistProfileCache.clear()
        albumDetailsCache.clear()
        client.clearArtistCache()
    }
}
