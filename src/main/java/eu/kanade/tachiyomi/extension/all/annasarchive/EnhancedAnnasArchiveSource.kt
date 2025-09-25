package eu.kanade.tachiyomi.extension.all.annasarchive

import android.content.Context
import eu.kanade.tachiyomi.extension.all.annasarchive.api.AnnasArchiveApi
import eu.kanade.tachiyomi.extension.all.annasarchive.cache.SearchCache
import eu.kanade.tachiyomi.extension.all.annasarchive.download.CaptchaWebViewHandler
import eu.kanade.tachiyomi.extension.all.annasarchive.download.DownloadOrchestrator
import eu.kanade.tachiyomi.extension.all.annasarchive.download.MirrorAvailabilityTester
import eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.AdvancedErrorHandler
import eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.BackgroundMirrorTracker
import eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.PerformanceMonitor
import eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.UserPreferencesManager
import eu.kanade.tachiyomi.extension.all.annasarchive.model.Book
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookDetails
import eu.kanade.tachiyomi.extension.all.annasarchive.source.SourceAggregator
import eu.kanade.tachiyomi.extension.all.annasarchive.source.SourceSelectionEngine
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SBook
import eu.kanade.tachiyomi.source.model.SBooksPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Enhanced Anna's Archive Extension Core
 * Integrates all Phase 1-5 components for a complete, production-ready extension
 */
class EnhancedAnnasArchiveSource(
    private val context: Context,
    client: OkHttpClient
) : AnnasArchiveSource(client) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Phase 5: Enhancement components
    private val userPreferences = UserPreferencesManager(context)
    private val performanceMonitor = PerformanceMonitor()
    private val errorHandler = AdvancedErrorHandler()
    private val mirrorTracker = BackgroundMirrorTracker(userPreferences)
    
    // Enhanced Phase 1-4 components with monitoring
    private val enhancedApi = AnnasArchiveApi(client, performanceMonitor, errorHandler)
    private val enhancedCache = SearchCache(userPreferences, performanceMonitor)
    private val enhancedSourceAggregator = SourceAggregator(enhancedApi, enhancedCache, performanceMonitor)
    private val enhancedSourceSelector = SourceSelectionEngine(userPreferences, mirrorTracker)
    
    // Download components with full integration
    private val captchaHandler = CaptchaWebViewHandler(context, userPreferences)
    private val mirrorTester = MirrorAvailabilityTester(client, userPreferences, mirrorTracker)
    private val downloadOrchestrator = DownloadOrchestrator(
        client, captchaHandler, mirrorTester, performanceMonitor, errorHandler
    )
    
    // Reactive state exposure
    val preferencesFlow: StateFlow<eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.UserPreferences> 
        get() = userPreferences.preferencesState
    
    val mirrorReliabilityFlow: StateFlow<Map<String, eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.MirrorReliability>>
        get() = mirrorTracker.reliabilityState
    
    /**
     * Enhanced search with full error handling and performance monitoring
     */
    override suspend fun searchBooks(page: Int, query: String, filters: FilterList): SBooksPage {
        val startTime = System.currentTimeMillis()
        
        return errorHandler.handleWithRetry(
            operation = {
                performanceMonitor.recordSearch(query, 0, 0) // Will update with actual results
                
                val result = enhancedSourceAggregator.searchBooks(query, page, filters)
                val books = result.books.map { book ->
                    enhanceSBookWithPreferences(book)
                }
                
                val duration = System.currentTimeMillis() - startTime
                performanceMonitor.recordSearch(query, books.size, duration)
                
                SBooksPage(books, result.hasNextPage)
            },
            context = "searchBooks(query='$query', page=$page)"
        ).getOrThrow()
    }
    
    /**
     * Enhanced book details with comprehensive error handling
     */
    override suspend fun getBookDetails(book: SBook): BookDetails {
        return errorHandler.handleWithRetry(
            operation = {
                val startTime = System.currentTimeMillis()
                
                // Use source selection engine to get best source
                val selectedSource = enhancedSourceSelector.selectBestSource(
                    availableSources = listOf("annas_archive"), // Primary source
                    bookIdentifier = book.url,
                    userQuery = book.title
                )
                
                val details = enhancedSourceAggregator.getBookDetails(book.url)
                
                val duration = System.currentTimeMillis() - startTime
                performanceMonitor.recordApiCall("book_details", duration, true)
                
                details
            },
            context = "getBookDetails(url='${book.url}')"
        ).getOrThrow()
    }
    
    /**
     * Enhanced download with full orchestration
     */
    suspend fun downloadBook(book: SBook): DownloadResult {
        return errorHandler.handleWithRetry(
            operation = {
                downloadOrchestrator.initiateDownload(
                    bookUrl = book.url,
                    preferredFormat = userPreferences.getCurrentPreferences().preferredFormats.first(),
                    userPreferences = userPreferences.getCurrentPreferences()
                )
            },
            context = "downloadBook(url='${book.url}')"
        ).getOrElse { 
            DownloadResult.Error("Download failed: ${it.message}")
        }
    }
    
    /**
     * Get comprehensive extension statistics
     */
    suspend fun getExtensionStatistics(): ExtensionStatistics {
        val performanceStats = performanceMonitor.getPerformanceStats()
        val errorStats = errorHandler.getErrorStats()
        val preferences = userPreferences.getCurrentPreferences()
        
        return ExtensionStatistics(
            performance = performanceStats,
            errors = errorStats,
            preferences = preferences,
            cacheStats = enhancedCache.getCacheStatistics(),
            mirrorStats = mirrorTracker.getRankedMirrors()
        )
    }
    
    /**
     * Get user preferences manager for settings integration
     */
    fun getUserPreferences(): UserPreferencesManager {
        return userPreferences
    }
    
    /**
     * Get performance monitor for diagnostics
     */
    fun getPerformanceMonitor(): PerformanceMonitor {
        return performanceMonitor
    }
    
    /**
     * Get mirror tracker for reliability analysis
     */
    fun getMirrorTracker(): BackgroundMirrorTracker {
        return mirrorTracker
    }
    
    /**
     * Reset all extension data and statistics
     */
    suspend fun resetExtensionData() {
        enhancedCache.clearCache()
        performanceMonitor.reset()
        errorHandler.clearHistory()
        mirrorTracker.clearAllStats()
    }
    
    /**
     * Export extension configuration and statistics
     */
    suspend fun exportExtensionData(): String {
        val stats = getExtensionStatistics()
        val preferences = userPreferences.exportPreferences()
        
        return """
            {
                "extension_version": "1.0.0",
                "export_timestamp": ${System.currentTimeMillis()},
                "preferences": $preferences,
                "performance": {
                    "avg_api_response_ms": ${stats.performance.avgApiResponseTimeMs},
                    "api_success_rate": ${stats.performance.apiSuccessRate},
                    "cache_hit_rate": ${stats.performance.cacheHitRate},
                    "total_searches": ${stats.performance.totalSearches}
                },
                "mirror_reliability": [
                    ${stats.mirrorStats.joinToString(",\n                    ") { mirror ->
                        """{"url": "${mirror.mirrorUrl}", "reliability": ${mirror.reliabilityScore}, "speed_ms": ${mirror.averageResponseMs}}"""
                    }}
                ]
            }
        """.trimIndent()
    }
    
    /**
     * Enhance SBook with user preferences (format priority, file size display, etc.)
     */
    private fun enhanceSBookWithPreferences(book: Book): SBook {
        val prefs = userPreferences.getCurrentPreferences()
        
        return SBook().apply {
            title = book.title
            author = book.author
            url = book.url
            thumbnail_url = book.thumbnailUrl
            
            // Enhanced description with user preferences
            description = buildString {
                append(book.description)
                
                if (prefs.showFileSizes && book.fileSize != null) {
                    append("\n\nFile Size: ${formatFileSize(book.fileSize)}")
                }
                
                if (book.formats.isNotEmpty()) {
                    val sortedFormats = book.formats.sortedBy { prefs.getFormatPriority(it) }
                    append("\n\nFormats: ${sortedFormats.joinToString(", ")}")
                    
                    // Highlight preferred formats
                    val preferredFormats = sortedFormats.filter { prefs.isFormatPreferred(it) }
                    if (preferredFormats.isNotEmpty()) {
                        append(" (Preferred: ${preferredFormats.joinToString(", ")})")
                    }
                }
                
                if (book.isbn != null) {
                    append("\n\nISBN: ${book.isbn}")
                }
            }
            
            // Set initialized flag
            initialized = true
        }
    }
    
    private fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        
        return "%.1f %s".format(size, units[unitIndex])
    }
    
    /**
     * Cleanup resources when extension is destroyed
     */
    fun cleanup() {
        scope.launch {
            performanceMonitor.stop()
            mirrorTracker.stop()
            enhancedCache.cleanup()
            captchaHandler.cleanup()
        }
    }
}

/**
 * Download result sealed class
 */
sealed class DownloadResult {
    data class Success(val downloadUrl: String, val format: String) : DownloadResult()
    data class CaptchaRequired(val captchaUrl: String, val sessionData: Map<String, String>) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

/**
 * Comprehensive extension statistics
 */
data class ExtensionStatistics(
    val performance: eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.PerformanceStats,
    val errors: eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.ErrorStats,
    val preferences: eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.UserPreferences,
    val cacheStats: eu.kanade.tachiyomi.extension.all.annasarchive.cache.CacheStatistics,
    val mirrorStats: List<eu.kanade.tachiyomi.extension.all.annasarchive.enhancement.MirrorReliability>
)