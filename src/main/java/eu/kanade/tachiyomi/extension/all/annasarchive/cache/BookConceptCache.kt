package eu.kanade.tachiyomi.extension.all.annasarchive.cache

import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookConcept
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * In-memory cache for book concepts and search results
 * Provides TTL-based expiration and memory management
 */
class BookConceptCache {
    
    companion object {
        private const val DEFAULT_TTL_MINUTES = 30L
        private const val MAX_CACHE_SIZE = 1000
        private const val CLEANUP_INTERVAL_MINUTES = 10L
    }
    
    private data class CacheEntry(
        val data: BookConcept,
        val timestamp: Long,
        val ttlMillis: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMillis
    }
    
    private data class SearchCacheEntry(
        val results: List<BookConcept>,
        val timestamp: Long,
        val ttlMillis: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMillis
    }
    
    private val conceptCache = ConcurrentHashMap<String, CacheEntry>()
    private val searchCache = ConcurrentHashMap<String, SearchCacheEntry>()
    private val downloadUrlCache = ConcurrentHashMap<String, Pair<List<String>, Long>>()
    
    @Volatile
    private var lastCleanup = System.currentTimeMillis()
    
    /**
     * Store book concept in cache
     */
    fun putBookConcept(conceptId: String, concept: BookConcept, ttlMinutes: Long = DEFAULT_TTL_MINUTES) {
        cleanupIfNeeded()
        
        val entry = CacheEntry(
            data = concept,
            timestamp = System.currentTimeMillis(),
            ttlMillis = TimeUnit.MINUTES.toMillis(ttlMinutes)
        )
        
        conceptCache[conceptId] = entry
        
        // Enforce max cache size
        if (conceptCache.size > MAX_CACHE_SIZE) {
            removeOldestEntries()
        }
    }
    
    /**
     * Retrieve book concept from cache
     */
    fun getBookConcept(conceptId: String): BookConcept? {
        cleanupIfNeeded()
        
        val entry = conceptCache[conceptId] ?: return null
        
        return if (entry.isExpired()) {
            conceptCache.remove(conceptId)
            null
        } else {
            entry.data
        }
    }
    
    /**
     * Store search results in cache
     */
    fun putSearchResults(
        query: String, 
        page: Int, 
        results: List<BookConcept>,
        ttlMinutes: Long = DEFAULT_TTL_MINUTES
    ) {
        cleanupIfNeeded()
        
        val cacheKey = buildSearchCacheKey(query, page)
        val entry = SearchCacheEntry(
            results = results,
            timestamp = System.currentTimeMillis(),
            ttlMillis = TimeUnit.MINUTES.toMillis(ttlMinutes)
        )
        
        searchCache[cacheKey] = entry
        
        // Also cache individual concepts
        results.forEach { concept ->
            putBookConcept(concept.conceptId, concept, ttlMinutes)
        }
    }
    
    /**
     * Retrieve search results from cache
     */
    fun getSearchResults(query: String, page: Int): List<BookConcept>? {
        cleanupIfNeeded()
        
        val cacheKey = buildSearchCacheKey(query, page)
        val entry = searchCache[cacheKey] ?: return null
        
        return if (entry.isExpired()) {
            searchCache.remove(cacheKey)
            null
        } else {
            entry.results
        }
    }
    
    /**
     * Store download URLs in cache
     */
    fun putDownloadUrls(md5: String, urls: List<String>, ttlMinutes: Long = 60L) {
        cleanupIfNeeded()
        
        val timestamp = System.currentTimeMillis()
        downloadUrlCache[md5] = urls to timestamp
    }
    
    /**
     * Retrieve download URLs from cache
     */
    fun getDownloadUrls(md5: String, ttlMinutes: Long = 60L): List<String>? {
        cleanupIfNeeded()
        
        val (urls, timestamp) = downloadUrlCache[md5] ?: return null
        val ttlMillis = TimeUnit.MINUTES.toMillis(ttlMinutes)
        
        return if (System.currentTimeMillis() - timestamp > ttlMillis) {
            downloadUrlCache.remove(md5)
            null
        } else {
            urls
        }
    }
    
    /**
     * Check if concept exists in cache (without retrieving)
     */
    fun hasBookConcept(conceptId: String): Boolean {
        val entry = conceptCache[conceptId] ?: return false
        return !entry.isExpired()
    }
    
    /**
     * Check if search results exist in cache
     */
    fun hasSearchResults(query: String, page: Int): Boolean {
        val cacheKey = buildSearchCacheKey(query, page)
        val entry = searchCache[cacheKey] ?: return false
        return !entry.isExpired()
    }
    
    /**
     * Update book concept sources (for download mirrors)
     */
    fun updateBookConceptSources(conceptId: String, updatedConcept: BookConcept) {
        val entry = conceptCache[conceptId] ?: return
        
        val updatedEntry = entry.copy(
            data = updatedConcept,
            timestamp = System.currentTimeMillis() // Refresh timestamp
        )
        
        conceptCache[conceptId] = updatedEntry
    }
    
    /**
     * Clear all cached data
     */
    fun clearAll() {
        conceptCache.clear()
        searchCache.clear()
        downloadUrlCache.clear()
    }
    
    /**
     * Clear expired entries
     */
    fun clearExpired() {
        // Clear expired concept cache entries
        val expiredConcepts = conceptCache.filter { (_, entry) -> entry.isExpired() }.keys
        expiredConcepts.forEach { conceptCache.remove(it) }
        
        // Clear expired search cache entries
        val expiredSearches = searchCache.filter { (_, entry) -> entry.isExpired() }.keys
        expiredSearches.forEach { searchCache.remove(it) }
        
        // Clear expired download URL entries
        val expiredDownloads = downloadUrlCache.filter { (_, pair) ->
            val (_, timestamp) = pair
            System.currentTimeMillis() - timestamp > TimeUnit.HOURS.toMillis(1)
        }.keys
        expiredDownloads.forEach { downloadUrlCache.remove(it) }
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        return CacheStats(
            conceptCacheSize = conceptCache.size,
            searchCacheSize = searchCache.size,
            downloadUrlCacheSize = downloadUrlCache.size,
            lastCleanupTime = lastCleanup
        )
    }
    
    /**
     * Build cache key for search results
     */
    private fun buildSearchCacheKey(query: String, page: Int): String {
        return "search:${query.lowercase().trim()}:$page"
    }
    
    /**
     * Perform cleanup if enough time has passed
     */
    private fun cleanupIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup > TimeUnit.MINUTES.toMillis(CLEANUP_INTERVAL_MINUTES)) {
            clearExpired()
            lastCleanup = now
        }
    }
    
    /**
     * Remove oldest entries when cache is full
     */
    private fun removeOldestEntries() {
        val entriesToRemove = conceptCache.size - (MAX_CACHE_SIZE * 0.8f).toInt()
        if (entriesToRemove <= 0) return
        
        val oldestEntries = conceptCache.toList()
            .sortedBy { (_, entry) -> entry.timestamp }
            .take(entriesToRemove)
        
        oldestEntries.forEach { (key, _) ->
            conceptCache.remove(key)
        }
    }
}

/**
 * Cache statistics data class
 */
data class CacheStats(
    val conceptCacheSize: Int,
    val searchCacheSize: Int,
    val downloadUrlCacheSize: Int,
    val lastCleanupTime: Long
) {
    fun totalSize(): Int = conceptCacheSize + searchCacheSize + downloadUrlCacheSize
}