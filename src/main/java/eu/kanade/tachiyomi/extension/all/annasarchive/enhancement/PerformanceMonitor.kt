package eu.kanade.tachiyomi.extension.all.annasarchive.enhancement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

/**
 * Phase 5: Performance Monitor
 * Tracks API performance, cache hit rates, and user experience metrics
 */
class PerformanceMonitor {
    
    companion object {
        private const val METRICS_RETENTION_MINUTES = 60L
        private const val CLEANUP_INTERVAL_MINUTES = 10L
    }
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private val metricsMap = ConcurrentHashMap<String, MutableList<MetricEntry>>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private var cleanupJob: Job? = null
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Record an API call with timing
     */
    fun recordApiCall(endpoint: String, durationMs: Long, success: Boolean) {
        val metric = MetricEntry(
            timestamp = System.currentTimeMillis(),
            value = durationMs.toDouble(),
            success = success
        )
        
        metricsMap.computeIfAbsent("api_$endpoint") { mutableListOf() }.add(metric)
        
        // Update counters
        incrementCounter("api_calls_total")
        if (success) {
            incrementCounter("api_calls_success")
        } else {
            incrementCounter("api_calls_error")
        }
    }
    
    /**
     * Record cache hit/miss
     */
    fun recordCacheAccess(cacheType: String, hit: Boolean) {
        incrementCounter("cache_${cacheType}_total")
        if (hit) {
            incrementCounter("cache_${cacheType}_hits")
        } else {
            incrementCounter("cache_${cacheType}_misses")
        }
    }
    
    /**
     * Record search operation
     */
    fun recordSearch(query: String, resultCount: Int, durationMs: Long) {
        val metric = MetricEntry(
            timestamp = System.currentTimeMillis(),
            value = durationMs.toDouble(),
            success = true,
            metadata = mapOf(
                "query" to query,
                "result_count" to resultCount.toString()
            )
        )
        
        metricsMap.computeIfAbsent("search_operations") { mutableListOf() }.add(metric)
        incrementCounter("searches_total")
    }
    
    /**
     * Record download initiation
     */
    fun recordDownloadInitiation(format: String, mirrorType: String, success: Boolean, durationMs: Long) {
        val metric = MetricEntry(
            timestamp = System.currentTimeMillis(),
            value = durationMs.toDouble(),
            success = success,
            metadata = mapOf(
                "format" to format,
                "mirror_type" to mirrorType
            )
        )
        
        metricsMap.computeIfAbsent("download_initiations") { mutableListOf() }.add(metric)
        incrementCounter("downloads_initiated")
        if (success) {
            incrementCounter("downloads_success")
        } else {
            incrementCounter("downloads_failed")
        }
    }
    
    /**
     * Get performance statistics
     */
    fun getPerformanceStats(): PerformanceStats {
        val now = System.currentTimeMillis()
        val cutoffTime = now - METRICS_RETENTION_MINUTES.minutes.inWholeMilliseconds
        
        // API performance
        val apiMetrics = metricsMap.filterKeys { it.startsWith("api_") }
            .flatMap { it.value }
            .filter { it.timestamp > cutoffTime }
        
        val avgApiResponseTime = apiMetrics
            .filter { it.success }
            .map { it.value }
            .takeIf { it.isNotEmpty() }
            ?.average() ?: 0.0
        
        val apiSuccessRate = if (apiMetrics.isNotEmpty()) {
            apiMetrics.count { it.success }.toFloat() / apiMetrics.size
        } else 0f
        
        // Cache performance
        val cacheHitRate = calculateCacheHitRate()
        
        // Search performance
        val searchMetrics = metricsMap["search_operations"]
            ?.filter { it.timestamp > cutoffTime }
            ?: emptyList()
        
        val avgSearchTime = searchMetrics
            .map { it.value }
            .takeIf { it.isNotEmpty() }
            ?.average() ?: 0.0
        
        // Download performance
        val downloadMetrics = metricsMap["download_initiations"]
            ?.filter { it.timestamp > cutoffTime }
            ?: emptyList()
        
        val downloadSuccessRate = if (downloadMetrics.isNotEmpty()) {
            downloadMetrics.count { it.success }.toFloat() / downloadMetrics.size
        } else 0f
        
        return PerformanceStats(
            avgApiResponseTimeMs = avgApiResponseTime,
            apiSuccessRate = apiSuccessRate,
            cacheHitRate = cacheHitRate,
            avgSearchTimeMs = avgSearchTime,
            downloadSuccessRate = downloadSuccessRate,
            totalApiCalls = getCounter("api_calls_total"),
            totalSearches = getCounter("searches_total"),
            totalDownloads = getCounter("downloads_initiated")
        )
    }
    
    /**
     * Calculate cache hit rate across all cache types
     */
    private fun calculateCacheHitRate(): Float {
        val cacheCounters = counters.filterKeys { it.contains("cache") && it.endsWith("_total") }
        var totalAccesses = 0L
        var totalHits = 0L
        
        cacheCounters.forEach { (key, _) ->
            val cacheType = key.substringAfter("cache_").substringBefore("_total")
            val total = getCounter("cache_${cacheType}_total")
            val hits = getCounter("cache_${cacheType}_hits")
            
            totalAccesses += total
            totalHits += hits
        }
        
        return if (totalAccesses > 0) totalHits.toFloat() / totalAccesses else 0f
    }
    
    /**
     * Get error statistics
     */
    fun getErrorStats(): ErrorStats {
        val now = System.currentTimeMillis()
        val cutoffTime = now - METRICS_RETENTION_MINUTES.minutes.inWholeMilliseconds
        
        val errorMetrics = metricsMap.values
            .flatten()
            .filter { !it.success && it.timestamp > cutoffTime }
        
        val errorsByType = errorMetrics
            .groupBy { it.metadata?.get("error_type") ?: "unknown" }
            .mapValues { it.value.size }
        
        return ErrorStats(
            totalErrors = errorMetrics.size,
            errorRate = if (getCounter("api_calls_total") > 0) {
                errorMetrics.size.toFloat() / getCounter("api_calls_total")
            } else 0f,
            errorsByType = errorsByType
        )
    }
    
    /**
     * Reset all metrics
     */
    fun reset() {
        metricsMap.clear()
        counters.clear()
    }
    
    private fun incrementCounter(key: String) {
        counters.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }
    
    private fun getCounter(key: String): Long {
        return counters[key]?.get() ?: 0L
    }
    
    private fun startPeriodicCleanup() {
        cleanupJob = scope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MINUTES.minutes)
                cleanupOldMetrics()
            }
        }
    }
    
    private fun cleanupOldMetrics() {
        val cutoffTime = System.currentTimeMillis() - METRICS_RETENTION_MINUTES.minutes.inWholeMilliseconds
        
        metricsMap.values.forEach { metrics ->
            metrics.removeAll { it.timestamp < cutoffTime }
        }
        
        // Remove empty lists
        metricsMap.entries.removeAll { it.value.isEmpty() }
    }
    
    fun stop() {
        cleanupJob?.cancel()
    }
}

/**
 * Single metric entry
 */
private data class MetricEntry(
    val timestamp: Long,
    val value: Double,
    val success: Boolean,
    val metadata: Map<String, String>? = null
)

/**
 * Performance statistics
 */
data class PerformanceStats(
    val avgApiResponseTimeMs: Double,
    val apiSuccessRate: Float,
    val cacheHitRate: Float,
    val avgSearchTimeMs: Double,
    val downloadSuccessRate: Float,
    val totalApiCalls: Long,
    val totalSearches: Long,
    val totalDownloads: Long
)

/**
 * Error statistics
 */
data class ErrorStats(
    val totalErrors: Int,
    val errorRate: Float,
    val errorsByType: Map<String, Int>
)