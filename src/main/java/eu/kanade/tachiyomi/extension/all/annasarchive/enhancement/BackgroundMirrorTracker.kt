package eu.kanade.tachiyomi.extension.all.annasarchive.enhancement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Phase 5: Background Mirror Reliability Tracker
 * Continuously monitors mirror performance and reliability in the background
 */
class BackgroundMirrorTracker(
    private val userPreferences: UserPreferencesManager
) {
    
    companion object {
        private const val TRACKING_INTERVAL_MINUTES = 30L
        private const val RELIABILITY_HISTORY_HOURS = 24L
        private const val MIN_SAMPLES_FOR_RELIABILITY = 5
        private const val RELIABILITY_WEIGHT_RECENT = 0.7f
        private const val RELIABILITY_WEIGHT_HISTORICAL = 0.3f
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val trackingJob: Job?
    
    // Thread-safe collections for tracking data
    private val mirrorStats = ConcurrentHashMap<String, MirrorStats>()
    private val reliabilityHistory = ConcurrentHashMap<String, MutableList<ReliabilityMeasurement>>()
    private val historyMutex = Mutex()
    
    // Reactive state
    private val _reliabilityState = MutableStateFlow<Map<String, MirrorReliability>>(emptyMap())
    val reliabilityState: StateFlow<Map<String, MirrorReliability>> = _reliabilityState.asStateFlow()
    
    init {
        trackingJob = startBackgroundTracking()
    }
    
    /**
     * Record a mirror access attempt
     */
    suspend fun recordMirrorAccess(
        mirrorUrl: String,
        success: Boolean,
        responseTimeMs: Long,
        errorType: String? = null
    ) {
        val stats = mirrorStats.computeIfAbsent(mirrorUrl) { MirrorStats(mirrorUrl) }
        
        // Update immediate stats
        stats.totalAttempts.incrementAndGet()
        if (success) {
            stats.successfulAttempts.incrementAndGet()
            stats.lastSuccessTime.set(System.currentTimeMillis())
            
            // Update response times (keep last 100)
            val responseTimes = stats.responseTimes
            synchronized(responseTimes) {
                responseTimes.add(responseTimeMs)
                if (responseTimes.size > 100) {
                    responseTimes.removeAt(0)
                }
            }
        } else {
            stats.failedAttempts.incrementAndGet()
            stats.lastFailureTime.set(System.currentTimeMillis())
            errorType?.let { stats.recentErrors.add(it) }
        }
        
        // Record for historical analysis
        recordReliabilityMeasurement(mirrorUrl, success, responseTimeMs)
        
        // Update reactive state
        updateReliabilityState()
    }
    
    /**
     * Get current reliability score for a mirror
     */
    fun getReliabilityScore(mirrorUrl: String): Float {
        return _reliabilityState.value[mirrorUrl]?.reliabilityScore ?: 0f
    }
    
    /**
     * Get ranked list of mirrors by reliability
     */
    fun getRankedMirrors(): List<MirrorReliability> {
        val prefs = userPreferences.getCurrentPreferences()
        
        return _reliabilityState.value.values.sortedWith { a, b ->
            when (prefs.mirrorPreference) {
                MirrorPreference.FAST -> {
                    // Prioritize speed, then reliability
                    val speedComparison = a.averageResponseMs.compareTo(b.averageResponseMs)
                    if (speedComparison != 0) speedComparison
                    else b.reliabilityScore.compareTo(a.reliabilityScore)
                }
                MirrorPreference.RELIABLE -> {
                    // Prioritize reliability, then speed
                    val reliabilityComparison = b.reliabilityScore.compareTo(a.reliabilityScore)
                    if (reliabilityComparison != 0) reliabilityComparison
                    else a.averageResponseMs.compareTo(b.averageResponseMs)
                }
                MirrorPreference.BALANCED -> {
                    // Balanced scoring
                    val aScore = calculateBalancedScore(a)
                    val bScore = calculateBalancedScore(b)
                    bScore.compareTo(aScore)
                }
            }
        }
    }
    
    /**
     * Get detailed statistics for a mirror
     */
    fun getMirrorStatistics(mirrorUrl: String): MirrorStatistics? {
        val stats = mirrorStats[mirrorUrl] ?: return null
        val reliability = _reliabilityState.value[mirrorUrl] ?: return null
        
        return MirrorStatistics(
            mirrorUrl = mirrorUrl,
            totalAttempts = stats.totalAttempts.get(),
            successfulAttempts = stats.successfulAttempts.get(),
            failedAttempts = stats.failedAttempts.get(),
            successRate = reliability.successRate,
            averageResponseMs = reliability.averageResponseMs,
            reliabilityScore = reliability.reliabilityScore,
            lastSuccessTime = stats.lastSuccessTime.get(),
            lastFailureTime = stats.lastFailureTime.get(),
            recentErrors = stats.recentErrors.takeLast(10),
            trend = calculateTrend(mirrorUrl)
        )
    }
    
    /**
     * Reset statistics for a mirror
     */
    suspend fun resetMirrorStats(mirrorUrl: String) {
        mirrorStats.remove(mirrorUrl)
        historyMutex.withLock {
            reliabilityHistory.remove(mirrorUrl)
        }
        updateReliabilityState()
    }
    
    /**
     * Clear all tracking data
     */
    suspend fun clearAllStats() {
        mirrorStats.clear()
        historyMutex.withLock {
            reliabilityHistory.clear()
        }
        updateReliabilityState()
    }
    
    private fun startBackgroundTracking(): Job {
        return scope.launch {
            while (true) {
                delay(TRACKING_INTERVAL_MINUTES.minutes)
                
                try {
                    // Clean up old data
                    cleanupOldData()
                    
                    // Update reliability calculations
                    updateReliabilityState()
                    
                    // Perform background health checks if needed
                    performHealthChecks()
                    
                } catch (e: Exception) {
                    // Log error but continue tracking
                    println("Background mirror tracking error: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun recordReliabilityMeasurement(
        mirrorUrl: String,
        success: Boolean,
        responseTimeMs: Long
    ) {
        val measurement = ReliabilityMeasurement(
            timestamp = System.currentTimeMillis(),
            success = success,
            responseTimeMs = responseTimeMs
        )
        
        historyMutex.withLock {
            val history = reliabilityHistory.computeIfAbsent(mirrorUrl) { mutableListOf() }
            history.add(measurement)
            
            // Keep only recent history
            val cutoffTime = System.currentTimeMillis() - RELIABILITY_HISTORY_HOURS.hours.inWholeMilliseconds
            history.removeAll { it.timestamp < cutoffTime }
        }
    }
    
    private suspend fun updateReliabilityState() {
        val newState = mutableMapOf<String, MirrorReliability>()
        
        mirrorStats.forEach { (mirrorUrl, stats) ->
            val reliability = calculateReliability(mirrorUrl, stats)
            newState[mirrorUrl] = reliability
        }
        
        _reliabilityState.value = newState
    }
    
    private suspend fun calculateReliability(mirrorUrl: String, stats: MirrorStats): MirrorReliability {
        val totalAttempts = stats.totalAttempts.get()
        val successfulAttempts = stats.successfulAttempts.get()
        
        val successRate = if (totalAttempts > 0) {
            successfulAttempts.toFloat() / totalAttempts
        } else 0f
        
        val averageResponseMs = synchronized(stats.responseTimes) {
            if (stats.responseTimes.isNotEmpty()) {
                stats.responseTimes.average().toLong()
            } else 0L
        }
        
        // Calculate historical reliability
        val historicalReliability = historyMutex.withLock {
            calculateHistoricalReliability(mirrorUrl)
        }
        
        // Combine recent and historical data
        val reliabilityScore = if (totalAttempts >= MIN_SAMPLES_FOR_RELIABILITY) {
            successRate * RELIABILITY_WEIGHT_RECENT + historicalReliability * RELIABILITY_WEIGHT_HISTORICAL
        } else {
            // Not enough samples, use historical data or default
            historicalReliability.takeIf { it > 0f } ?: 0.5f
        }
        
        return MirrorReliability(
            mirrorUrl = mirrorUrl,
            successRate = successRate,
            averageResponseMs = averageResponseMs,
            reliabilityScore = reliabilityScore.coerceIn(0f, 1f),
            sampleSize = totalAttempts,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    private fun calculateHistoricalReliability(mirrorUrl: String): Float {
        val history = reliabilityHistory[mirrorUrl] ?: return 0f
        
        if (history.isEmpty()) return 0f
        
        val successCount = history.count { it.success }
        return successCount.toFloat() / history.size
    }
    
    private fun calculateBalancedScore(reliability: MirrorReliability): Float {
        // Normalize response time (assume 5000ms is maximum acceptable)
        val normalizedSpeed = (5000f - reliability.averageResponseMs.coerceAtMost(5000L)) / 5000f
        
        // Balanced score: 60% reliability, 40% speed
        return reliability.reliabilityScore * 0.6f + normalizedSpeed * 0.4f
    }
    
    private fun calculateTrend(mirrorUrl: String): ReliabilityTrend {
        val history = reliabilityHistory[mirrorUrl] ?: return ReliabilityTrend.STABLE
        
        if (history.size < 10) return ReliabilityTrend.STABLE
        
        // Compare recent half vs older half
        val midPoint = history.size / 2
        val olderHalf = history.subList(0, midPoint)
        val recentHalf = history.subList(midPoint, history.size)
        
        val olderSuccessRate = olderHalf.count { it.success }.toFloat() / olderHalf.size
        val recentSuccessRate = recentHalf.count { it.success }.toFloat() / recentHalf.size
        
        val difference = recentSuccessRate - olderSuccessRate
        
        return when {
            difference > 0.1f -> ReliabilityTrend.IMPROVING
            difference < -0.1f -> ReliabilityTrend.DECLINING
            else -> ReliabilityTrend.STABLE
        }
    }
    
    private suspend fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - RELIABILITY_HISTORY_HOURS.hours.inWholeMilliseconds
        
        historyMutex.withLock {
            reliabilityHistory.values.forEach { history ->
                history.removeAll { it.timestamp < cutoffTime }
            }
            
            // Remove empty histories
            reliabilityHistory.entries.removeAll { it.value.isEmpty() }
        }
        
        // Clean up recent errors from stats
        mirrorStats.values.forEach { stats ->
            synchronized(stats.recentErrors) {
                if (stats.recentErrors.size > 20) {
                    // Keep only recent errors
                    val toKeep = stats.recentErrors.takeLast(10)
                    stats.recentErrors.clear()
                    stats.recentErrors.addAll(toKeep)
                }
            }
        }
    }
    
    private suspend fun performHealthChecks() {
        // Optional: Perform periodic health checks on mirrors
        // This could be implemented to proactively test mirror availability
        // For now, we rely on organic usage data
    }
    
    fun stop() {
        trackingJob?.cancel()
    }
}

/**
 * Thread-safe mirror statistics
 */
private class MirrorStats(val mirrorUrl: String) {
    val totalAttempts = java.util.concurrent.atomic.AtomicLong(0)
    val successfulAttempts = java.util.concurrent.atomic.AtomicLong(0)
    val failedAttempts = java.util.concurrent.atomic.AtomicLong(0)
    val lastSuccessTime = java.util.concurrent.atomic.AtomicLong(0)
    val lastFailureTime = java.util.concurrent.atomic.AtomicLong(0)
    val responseTimes = mutableListOf<Long>()
    val recentErrors = mutableListOf<String>()
}

/**
 * Single reliability measurement
 */
private data class ReliabilityMeasurement(
    val timestamp: Long,
    val success: Boolean,
    val responseTimeMs: Long
)

/**
 * Mirror reliability information
 */
data class MirrorReliability(
    val mirrorUrl: String,
    val successRate: Float,
    val averageResponseMs: Long,
    val reliabilityScore: Float,
    val sampleSize: Long,
    val lastUpdated: Long
)

/**
 * Detailed mirror statistics
 */
data class MirrorStatistics(
    val mirrorUrl: String,
    val totalAttempts: Long,
    val successfulAttempts: Long,
    val failedAttempts: Long,
    val successRate: Float,
    val averageResponseMs: Long,
    val reliabilityScore: Float,
    val lastSuccessTime: Long,
    val lastFailureTime: Long,
    val recentErrors: List<String>,
    val trend: ReliabilityTrend
)

/**
 * Reliability trend indicators
 */
enum class ReliabilityTrend {
    IMPROVING,
    STABLE,  
    DECLINING
}