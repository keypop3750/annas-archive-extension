package eu.kanade.tachiyomi.extension.all.annasarchive.selection

import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookSource
import eu.kanade.tachiyomi.extension.all.annasarchive.model.DownloadMirror
import eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Phase 3: Mirror Management and Testing
 * Handles mirror reliability tracking, testing, and prioritization
 */
class MirrorManager(private val client: OkHttpClient) {
    
    companion object {
        private const val MIRROR_TEST_TIMEOUT = 10_000L // 10 seconds
        private const val HEAD_REQUEST_TIMEOUT = 5_000L // 5 seconds
    }
    
    /**
     * Get ordered mirrors for a source based on reliability and type
     */
    suspend fun getOrderedMirrors(source: BookSource): List<DownloadMirror> {
        return source.downloadMirrors
            .sortedWith(compareBy<DownloadMirror> { mirror ->
                // Primary sort: mirror type priority
                when (mirror.type) {
                    MirrorType.DIRECT -> 1
                    MirrorType.SLOW_DOWNLOAD -> 2
                    MirrorType.IPFS -> 3
                    MirrorType.PARTNER -> 4
                }
            }.thenByDescending { mirror ->
                // Secondary sort: calculated reliability
                calculateMirrorReliability(mirror)
            }.thenBy { mirror ->
                // Tertiary sort: explicit priority field
                mirror.priority
            })
    }
    
    /**
     * Test multiple mirrors concurrently to find working ones
     */
    suspend fun testMirrors(mirrors: List<DownloadMirror>): List<MirrorTestResult> {
        return coroutineScope {
            mirrors.map { mirror ->
                async {
                    testSingleMirror(mirror)
                }
            }.awaitAll()
        }
    }
    
    /**
     * Test a single mirror for availability
     */
    private suspend fun testSingleMirror(mirror: DownloadMirror): MirrorTestResult {
        return withTimeoutOrNull(MIRROR_TEST_TIMEOUT) {
            try {
                val startTime = System.currentTimeMillis()
                
                // Use HEAD request for quick availability check
                val request = Request.Builder()
                    .url(mirror.url)
                    .head()
                    .header("User-Agent", "Yokai-Extension/1.0")
                    .build()
                
                val response = client.newCall(request).execute()
                val responseTime = System.currentTimeMillis() - startTime
                
                response.use {
                    when {
                        response.isSuccessful -> MirrorTestResult(
                            mirror = mirror,
                            isWorking = true,
                            responseTimeMs = responseTime,
                            statusCode = response.code,
                            errorMessage = null
                        )
                        response.code == 403 || response.code == 429 -> MirrorTestResult(
                            mirror = mirror,
                            isWorking = false,
                            responseTimeMs = responseTime,
                            statusCode = response.code,
                            errorMessage = "Access denied or rate limited"
                        )
                        else -> MirrorTestResult(
                            mirror = mirror,
                            isWorking = false,
                            responseTimeMs = responseTime,
                            statusCode = response.code,
                            errorMessage = "HTTP ${response.code}: ${response.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                MirrorTestResult(
                    mirror = mirror,
                    isWorking = false,
                    responseTimeMs = MIRROR_TEST_TIMEOUT,
                    statusCode = null,
                    errorMessage = e.message ?: "Connection failed"
                )
            }
        } ?: MirrorTestResult(
            mirror = mirror,
            isWorking = false,
            responseTimeMs = MIRROR_TEST_TIMEOUT,
            statusCode = null,
            errorMessage = "Request timeout"
        )
    }
    
    /**
     * Calculate mirror reliability based on historical data and characteristics
     */
    private fun calculateMirrorReliability(mirror: DownloadMirror): Float {
        var reliability = 0.5f // Base reliability
        
        // Adjust based on mirror type
        reliability += when (mirror.type) {
            MirrorType.DIRECT -> 0.3f
            MirrorType.SLOW_DOWNLOAD -> 0.1f
            MirrorType.IPFS -> 0.2f
            MirrorType.PARTNER -> 0.0f
        }
        
        // Adjust based on domain reputation
        reliability += when {
            mirror.domain.contains("libgen") -> 0.2f
            mirror.domain.contains("archive.org") -> 0.3f
            mirror.domain.contains("annas-archive") -> 0.25f
            mirror.domain.contains("ipfs") -> 0.15f
            else -> 0.0f
        }
        
        // Penalty for CAPTCHA requirement
        if (mirror.requiresCaptcha) {
            reliability -= 0.15f
        }
        
        // Historical success rate (if available)
        mirror.successCount?.let { successCount ->
            mirror.failureCount?.let { failureCount ->
                val totalAttempts = successCount + failureCount
                if (totalAttempts > 0) {
                    val historicalRate = successCount.toFloat() / totalAttempts
                    reliability = (reliability * 0.6f) + (historicalRate * 0.4f)
                }
            }
        }
        
        return reliability.coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Find the best working mirror from a list
     */
    suspend fun findBestWorkingMirror(mirrors: List<DownloadMirror>): DownloadMirror? {
        val orderedMirrors = mirrors.sortedByDescending { calculateMirrorReliability(it) }
        val testResults = testMirrors(orderedMirrors.take(3)) // Test top 3 mirrors
        
        return testResults
            .filter { it.isWorking }
            .minByOrNull { it.responseTimeMs }
            ?.mirror
    }
    
    /**
     * Get mirror recommendations with explanations
     */
    fun getMirrorRecommendations(mirrors: List<DownloadMirror>): List<MirrorRecommendation> {
        return mirrors.sortedByDescending { calculateMirrorReliability(it) }
            .mapIndexed { index, mirror ->
                val reliability = calculateMirrorReliability(mirror)
                val reasons = mutableListOf<String>()
                
                // Add reasons based on mirror characteristics
                when (mirror.type) {
                    MirrorType.DIRECT -> reasons.add("Direct download - usually fastest")
                    MirrorType.SLOW_DOWNLOAD -> reasons.add("May require CAPTCHA solving")
                    MirrorType.IPFS -> reasons.add("Distributed network - good for censored regions")
                    MirrorType.PARTNER -> reasons.add("External partner site")
                }
                
                when {
                    mirror.domain.contains("libgen") -> reasons.add("Library Genesis - well-established")
                    mirror.domain.contains("archive.org") -> reasons.add("Internet Archive - highly reliable")
                    mirror.domain.contains("annas-archive") -> reasons.add("Anna's Archive native")
                }
                
                if (mirror.requiresCaptcha) {
                    reasons.add("Requires solving CAPTCHA")
                }
                
                val recommendation = when {
                    index == 0 && reliability > 0.8f -> "Recommended"
                    index == 0 -> "Best available"
                    reliability > 0.7f -> "Good alternative"
                    reliability > 0.5f -> "Backup option"
                    else -> "Last resort"
                }
                
                MirrorRecommendation(
                    mirror = mirror,
                    reliability = reliability,
                    recommendation = recommendation,
                    reasons = reasons,
                    estimatedSpeed = estimateSpeed(mirror)
                )
            }
    }
    
    /**
     * Estimate download speed category
     */
    private fun estimateSpeed(mirror: DownloadMirror): String {
        return when {
            mirror.type == MirrorType.DIRECT && !mirror.requiresCaptcha -> "Fast"
            mirror.type == MirrorType.DIRECT -> "Medium"
            mirror.type == MirrorType.SLOW_DOWNLOAD -> "Slow"
            mirror.type == MirrorType.IPFS -> "Variable"
            else -> "Unknown"
        }
    }
    
    /**
     * Update mirror statistics based on usage results
     */
    fun updateMirrorStats(mirror: DownloadMirror, success: Boolean): DownloadMirror {
        val currentSuccess = mirror.successCount ?: 0
        val currentFailure = mirror.failureCount ?: 0
        
        return if (success) {
            mirror.copy(
                successCount = currentSuccess + 1,
                lastTested = System.currentTimeMillis()
            )
        } else {
            mirror.copy(
                failureCount = currentFailure + 1,
                lastTested = System.currentTimeMillis()
            )
        }
    }
}

/**
 * Result of testing a single mirror
 */
data class MirrorTestResult(
    val mirror: DownloadMirror,
    val isWorking: Boolean,
    val responseTimeMs: Long,
    val statusCode: Int?,
    val errorMessage: String?
)

/**
 * Mirror recommendation with explanation
 */
data class MirrorRecommendation(
    val mirror: DownloadMirror,
    val reliability: Float,
    val recommendation: String,
    val reasons: List<String>,
    val estimatedSpeed: String
)

/**
 * Add fields needed for mirror statistics tracking
 */
val DownloadMirror.successCount: Int?
    get() = (this as? DownloadMirrorWithStats)?.successCount

val DownloadMirror.failureCount: Int?
    get() = (this as? DownloadMirrorWithStats)?.failureCount

val DownloadMirror.lastTested: Long?
    get() = (this as? DownloadMirrorWithStats)?.lastTested

/**
 * Enhanced mirror class with statistics (for future use)
 */
interface DownloadMirrorWithStats {
    val successCount: Int
    val failureCount: Int
    val lastTested: Long
}