package eu.kanade.tachiyomi.extension.all.annasarchive.model

import kotlinx.serialization.Serializable

/**
 * Represents a book concept - a logical grouping of different sources/formats for the same book
 * This solves the UX problem of multiple entries for the same book in different formats
 */
@Serializable
data class BookConcept(
    val conceptId: String, // Generated hash from title + author
    val title: String,
    val author: String?,
    val isbn: String? = null,
    val thumbnail: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val publishedYear: String? = null,
    val language: String? = null,
    val categories: List<String> = emptyList(),
    val sources: List<BookSource> = emptyList(),
    val metadata: BookMetadata? = null,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /**
     * Get the best available source based on reliability and format preference
     */
    fun getBestSource(preferredFormat: String? = null): BookSource? {
        if (sources.isEmpty()) return null
        
        // First, try to find preferred format with highest reliability
        preferredFormat?.let { format ->
            val preferredSources = sources.filter { it.format.equals(format, ignoreCase = true) }
            if (preferredSources.isNotEmpty()) {
                return preferredSources.maxByOrNull { it.reliability }
            }
        }
        
        // Fallback to highest reliability regardless of format
        return sources.maxByOrNull { it.reliability }
    }
    
    /**
     * Get sources grouped by format for UI display
     */
    fun getSourcesByFormat(): Map<String, List<BookSource>> {
        return sources.groupBy { it.format.uppercase() }
            .toSortedMap(compareBy { formatPriority(it) })
    }
    
    /**
     * Get available formats sorted by priority
     */
    fun getAvailableFormats(): List<String> {
        return sources.map { it.format.uppercase() }.distinct()
            .sortedBy { formatPriority(it) }
    }
    
    /**
     * Get total number of download mirrors across all sources
     */
    fun getTotalMirrors(): Int {
        return sources.sumOf { it.downloadMirrors.size }
    }
    
    /**
     * Check if concept has sources in preferred formats
     */
    fun hasPreferredFormats(preferredFormats: List<String>): Boolean {
        val availableFormats = sources.map { it.format.lowercase() }
        return preferredFormats.any { preferred -> 
            availableFormats.contains(preferred.lowercase()) 
        }
    }
    
    private fun formatPriority(format: String): Int = when (format.uppercase()) {
        "EPUB" -> 1 // Most versatile
        "PDF" -> 2  // Universal
        "MOBI" -> 3 // Kindle
        "AZW3" -> 4 // Better Kindle
        "FB2" -> 5  // Popular
        "TXT" -> 6  // Basic
        "CBR", "CBZ" -> 7 // Comics
        else -> 99
    }
}

/**
 * Represents a specific source/format of a book from Anna's Archive
 */
@Serializable
data class BookSource(
    val md5: String, // Anna's Archive primary identifier
    val conceptId: String, // Reference to parent concept
    val format: String, // pdf, epub, mobi, etc.
    val fileSize: String? = null,
    val quality: String? = null, // HD, Standard, etc.
    val downloadMirrors: List<DownloadMirror> = emptyList(),
    val reliability: Float = 0.5f, // 0.0-1.0 based on success rate
    val annasArchiveUrl: String,
    val originalSource: String? = null, // libgen, zlib, etc.
    val metadata: Map<String, String> = emptyMap(),
    val lastVerified: Long = 0L
) {
    /**
     * Get mirrors ordered by priority and reliability
     */
    fun getOrderedMirrors(): List<DownloadMirror> {
        return downloadMirrors.sortedWith(
            compareBy<DownloadMirror> { it.type.priority }
                .thenByDescending { it.calculateReliability() }
                .thenBy { it.priority }
        )
    }
}

/**
 * Represents a download mirror for a book source
 */
@Serializable
data class DownloadMirror(
    val url: String,
    val type: MirrorType,
    val domain: String,
    val requiresCaptcha: Boolean = false,
    val estimatedSpeed: String? = null,
    val priority: Int = 999, // Lower = higher priority
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastTested: Long = 0L,
    val pathIndex: Int? = null, // For slow_download URLs
    val domainIndex: Int? = null // For slow_download URLs
) {
    fun calculateReliability(): Float {
        val totalAttempts = successCount + failureCount
        return if (totalAttempts > 0) {
            successCount.toFloat() / totalAttempts
        } else {
            0.5f // Default for untested mirrors
        }
    }
}

/**
 * Types of download mirrors with their priorities
 */
@Serializable
enum class MirrorType(val priority: Int) {
    IPFS(1),           // Highest priority - decentralized
    SLOW_DOWNLOAD(2),  // Anna's Archive slow download with CAPTCHA
    PARTNER(3),        // Partner sites (libgen, zlib)
    DIRECT(4)          // Direct HTTP links
}

/**
 * Enhanced metadata from various sources (Google Books, Anna's Archive, etc.)
 */
@Serializable
data class BookMetadata(
    val googleBooksId: String? = null,
    val openLibraryId: String? = null,
    val goodreadsId: String? = null,
    val averageRating: Float? = null,
    val ratingsCount: Int? = null,
    val previewUrl: String? = null,
    val enhancedDescription: String? = null,
    val subjects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val series: String? = null,
    val edition: String? = null,
    val pageCount: Int? = null,
    val tableOfContents: List<String> = emptyList(),
    val lastEnhanced: Long = System.currentTimeMillis()
)