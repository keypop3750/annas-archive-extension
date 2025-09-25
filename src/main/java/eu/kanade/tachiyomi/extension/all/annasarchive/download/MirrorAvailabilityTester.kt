package eu.kanade.tachiyomi.extension.all.annasarchive.download

import eu.kanade.tachiyomi.extension.all.annasarchive.model.DownloadMirror
import eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 4: Mirror Availability Testing
 * Tests mirror reliability and availability in real-time
 */
class MirrorAvailabilityTester(private val client: OkHttpClient) {
    
    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private const val USER_AGENT = "Yokai-Extension/1.0 (https://github.com/null2264/yokai)"
        private const val MAX_CONCURRENT_TESTS = 5
    }
    
    // Cache test results for 5 minutes
    private val testCache = ConcurrentHashMap<String, CachedTestResult>()
    private val cacheTimeoutMs = 5 * 60 * 1000L // 5 minutes
    
    private data class CachedTestResult(
        val isAvailable: Boolean,
        val responseTimeMs: Long,
        val timestamp: Long,
        val errorMessage: String? = null
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > 5 * 60 * 1000L // 5 min
    }
    
    /**
     * Test availability of multiple mirrors
     */
    suspend fun testMirrorAvailability(mirrors: List<DownloadMirror>): List<MirrorTestResult> = coroutineScope {
        
        // Check cache first
        val cachedResults = mirrors.mapNotNull { mirror ->
            testCache[mirror.url]?.takeIf { !it.isExpired() }?.let { cached ->
                MirrorTestResult(
                    mirror = mirror,
                    isAvailable = cached.isAvailable,
                    responseTimeMs = cached.responseTimeMs,
                    error = cached.errorMessage
                )
            }
        }
        
        val uncachedMirrors = mirrors.filter { mirror ->
            testCache[mirror.url]?.isExpired() != false
        }
        
        // Test uncached mirrors in parallel
        val newResults = uncachedMirrors
            .chunked(MAX_CONCURRENT_TESTS)
            .flatMap { chunk ->
                chunk.map { mirror ->
                    async { testSingleMirror(mirror) }
                }.awaitAll()
            }
        
        // Cache new results
        newResults.forEach { result ->
            testCache[result.mirror.url] = CachedTestResult(
                isAvailable = result.isAvailable,
                responseTimeMs = result.responseTimeMs,
                timestamp = System.currentTimeMillis(),
                errorMessage = result.error
            )
        }
        
        cachedResults + newResults
    }
    
    /**
     * Test a single mirror
     */
    private suspend fun testSingleMirror(mirror: DownloadMirror): MirrorTestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            withTimeout(TIMEOUT_SECONDS.seconds) {
                val request = Request.Builder()
                    .url(mirror.url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-1023") // Request only first 1KB for testing
                    .build()
                
                val response = client.newCall(request).execute()
                val responseTime = System.currentTimeMillis() - startTime
                
                response.use {
                    val isAvailable = response.isSuccessful || response.code == 206 // Accept partial content
                    
                    MirrorTestResult(
                        mirror = mirror,
                        isAvailable = isAvailable,
                        responseTimeMs = responseTime,
                        error = if (!isAvailable) "HTTP ${response.code}: ${response.message}" else null
                    )
                }
            }
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            MirrorTestResult(
                mirror = mirror,
                isAvailable = false,
                responseTimeMs = responseTime,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Find the best available mirror from a list
     */
    suspend fun findBestMirror(mirrors: List<DownloadMirror>): DownloadMirror? {
        if (mirrors.isEmpty()) return null
        if (mirrors.size == 1) return mirrors.first()
        
        val testResults = testMirrorAvailability(mirrors)
        val availableResults = testResults.filter { it.isAvailable }
        
        if (availableResults.isEmpty()) {
            return mirrors.maxByOrNull { calculateMirrorScore(it) }
        }
        
        // Score available mirrors by response time and type
        return availableResults.maxByOrNull { result ->
            val baseScore = calculateMirrorScore(result.mirror)
            val speedScore = when {
                result.responseTimeMs < 1000 -> 1.0f
                result.responseTimeMs < 3000 -> 0.8f
                result.responseTimeMs < 5000 -> 0.6f
                else -> 0.4f
            }
            baseScore * speedScore
        }?.mirror
    }
    
    /**
     * Calculate base score for a mirror
     */
    private fun calculateMirrorScore(mirror: DownloadMirror): Float {
        var score = mirror.priority.let { 1.0f / (it + 1) } // Lower priority number = higher score
        
        // Bonus for mirror type
        score *= when (mirror.type) {
            MirrorType.DIRECT -> 1.0f
            MirrorType.SLOW_DOWNLOAD -> 0.9f
            MirrorType.IPFS -> 0.8f
            MirrorType.PARTNER -> 0.7f
        }
        
        // Penalty for CAPTCHA requirement
        if (mirror.requiresCaptcha) {
            score *= 0.7f
        }
        
        return score
    }
    
    /**
     * Get cached availability status
     */
    fun getCachedAvailability(url: String): Boolean? {
        return testCache[url]?.takeIf { !it.isExpired() }?.isAvailable
    }
    
    /**
     * Clear expired cache entries
     */
    fun cleanupCache() {
        val now = System.currentTimeMillis()
        testCache.entries.removeAll { (_, cached) ->
            now - cached.timestamp > cacheTimeoutMs
        }
    }
    
    /**
     * Get mirror testing statistics
     */
    fun getMirrorStatistics(): MirrorStatistics {
        val cachedEntries = testCache.values.filter { !it.isExpired() }
        val availableCount = cachedEntries.count { it.isAvailable }
        val totalCount = cachedEntries.size
        val averageResponseTime = cachedEntries
            .filter { it.isAvailable }
            .map { it.responseTimeMs }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toLong() ?: 0L
        
        return MirrorStatistics(
            totalTested = totalCount,
            availableCount = availableCount,
            availabilityRate = if (totalCount > 0) availableCount.toFloat() / totalCount else 0f,
            averageResponseTimeMs = averageResponseTime
        )
    }
}

/**
 * Result of testing a single mirror
 */
data class MirrorTestResult(
    val mirror: DownloadMirror,
    val isAvailable: Boolean,
    val responseTimeMs: Long,
    val error: String? = null
)

/**
 * Overall mirror testing statistics
 */
data class MirrorStatistics(
    val totalTested: Int,
    val availableCount: Int,
    val availabilityRate: Float,
    val averageResponseTimeMs: Long
)