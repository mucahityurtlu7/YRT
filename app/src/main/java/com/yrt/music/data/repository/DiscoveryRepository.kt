package com.yrt.music.data.repository

import com.yrt.music.data.model.DiscoveryPage
import com.yrt.music.data.network.YouTubeMusicDiscoveryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

interface DiscoveryService {
    suspend fun getPage(
        browseId: String = "FEmusic_explore",
        params: String? = null,
        forceRefresh: Boolean = false,
    ): Result<DiscoveryPage>
}

/** A small, short-lived cache; explicit refresh always requests current regional content. */
class DiscoveryRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loadPage: (String, String?) -> DiscoveryPage = YouTubeMusicDiscoveryClient::getPage,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : DiscoveryService {
    private data class Key(val browseId: String, val params: String?)
    private data class Cached(val loadedAt: Long, val page: DiscoveryPage)
    private val cache = LinkedHashMap<Key, Cached>(16, 0.75f, true)

    override suspend fun getPage(browseId: String, params: String?, forceRefresh: Boolean): Result<DiscoveryPage> =
        withContext(ioDispatcher) {
            val cleanId = browseId.trim()
            if (cleanId.isBlank()) return@withContext Result.failure(IllegalArgumentException("browseId cannot be blank"))
            val key = Key(cleanId, params)
            if (!forceRefresh) synchronized(cache) {
                cache[key]?.takeIf { clockMs() - it.loadedAt in 0 until CACHE_TTL_MS }?.page
            }?.let { return@withContext Result.success(it) }
            try {
                val page = loadPage(cleanId, params)
                synchronized(cache) {
                    cache[key] = Cached(clockMs(), page)
                    while (cache.size > MAX_CACHED_PAGES) cache.remove(cache.keys.first())
                }
                Result.success(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun clearCache() = synchronized(cache) { cache.clear() }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val MAX_CACHED_PAGES = 20
    }
}
