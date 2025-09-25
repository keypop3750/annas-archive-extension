package eu.kanade.tachiyomi.extension.all.annasarchive.aggregation

import eu.kanade.tachiyomi.extension.all.annasarchive.api.RawBookData
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookConcept
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookMetadata
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookSource
import eu.kanade.tachiyomi.extension.all.annasarchive.model.DownloadMirror
import eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType
import eu.kanade.tachiyomi.extension.all.annasarchive.model.generateConceptId
import java.util.Locale

/**
 * Aggregates raw book data from Anna's Archive API into organized BookConcepts
 * Handles deduplication, format grouping, and quality scoring
 */
class BookConceptAggregator {
    
    companion object {
        // Format priority for determining best source (higher = better)
        private val FORMAT_PRIORITY = mapOf(
            "pdf" to 10,
            "epub" to 9, 
            "mobi" to 8,
            "azw3" to 7,
            "fb2" to 6,
            "txt" to 5,
            "doc" to 4,
            "docx" to 3,
            "djvu" to 2
        )
        
        // Minimum file sizes by format (bytes)
        private val MIN_FILE_SIZES = mapOf(
            "pdf" to 50_000L,    // 50KB
            "epub" to 10_000L,   // 10KB
            "mobi" to 10_000L,   // 10KB
            "txt" to 1_000L      // 1KB
        )
        
        // Quality multipliers
        private const val HAS_ISBN_BONUS = 1.2f
        private const val HAS_DESCRIPTION_BONUS = 1.1f
        private const val HAS_PUBLISHER_BONUS = 1.1f
        private const val GOOD_FILESIZE_BONUS = 1.15f
    }
    
    /**
     * Aggregate list of raw book data into organized concepts
     */
    fun aggregateBooks(rawBooks: List<RawBookData>): List<BookConcept> {
        if (rawBooks.isEmpty()) return emptyList()
        
        // Group by concept (same book, different formats/sources)
        val conceptGroups = groupByBookConcept(rawBooks)
        
        // Convert each group to a BookConcept
        return conceptGroups.map { (conceptId, books) ->
            createBookConcept(conceptId, books)
        }.sortedByDescending { it.bestQualityScore() }
    }
    
    /**
     * Group raw books by concept ID (same book, different sources)
     */
    private fun groupByBookConcept(rawBooks: List<RawBookData>): Map<String, List<RawBookData>> {
        return rawBooks.groupBy { book ->
            generateConceptId(book.title, book.author)
        }.filter { (_, books) ->
            // Filter out groups with suspicious data
            books.isNotEmpty() && books.any { isValidBook(it) }
        }
    }
    
    /**
     * Check if book data appears valid
     */
    private fun isValidBook(book: RawBookData): Boolean {
        // Basic validation
        if (book.title.length < 2 || book.md5.length != 32) return false
        
        // Check minimum file size for format
        val minSize = MIN_FILE_SIZES[book.extension?.lowercase()] ?: 1000L
        if (book.filesize != null && book.filesize < minSize) return false
        
        // Exclude obvious spam patterns
        val spamPatterns = listOf("test", "sample", "example", "dummy")
        val titleLower = book.title.lowercase()
        if (spamPatterns.any { titleLower.contains(it) } && book.filesize ?: 0 < 10000) {
            return false
        }
        
        return true
    }
    
    /**
     * Create BookConcept from group of raw book data
     */
    private fun createBookConcept(conceptId: String, rawBooks: List<RawBookData>): BookConcept {
        val validBooks = rawBooks.filter { isValidBook(it) }
        if (validBooks.isEmpty()) {
            // Fallback to first book if all are filtered out
            return createFromSingleBook(conceptId, rawBooks.first())
        }
        
        // Pick best book for primary metadata
        val primaryBook = selectPrimaryBook(validBooks)
        
        // Create sources for each valid book
        val sources = validBooks.map { book ->
            createBookSource(conceptId, book)
        }.sortedByDescending { it.reliability }
        
        // Aggregate metadata
        val metadata = aggregateMetadata(validBooks)
        
        // Extract categories
        val categories = aggregateCategories(validBooks)
        
        return BookConcept(
            conceptId = conceptId,
            title = cleanTitle(primaryBook.title),
            author = cleanAuthor(primaryBook.author),
            thumbnail = selectBestThumbnail(validBooks),
            description = selectBestDescription(validBooks),
            publisher = selectBestPublisher(validBooks),
            categories = categories,
            sources = sources,
            metadata = metadata
        )
    }
    
    /**
     * Create BookConcept from single raw book (fallback)
     */
    private fun createFromSingleBook(conceptId: String, book: RawBookData): BookConcept {
        val source = createBookSource(conceptId, book)
        
        return BookConcept(
            conceptId = conceptId,
            title = cleanTitle(book.title),
            author = cleanAuthor(book.author),
            thumbnail = book.coverUrl,
            description = book.description,
            publisher = book.publisher,
            categories = book.categories,
            sources = listOf(source)
        )
    }
    
    /**
     * Select the best book for primary metadata
     */
    private fun selectPrimaryBook(books: List<RawBookData>): RawBookData {
        return books.maxByOrNull { book ->
            var score = 0f
            
            // Prefer books with more metadata
            if (!book.isbn.isNullOrBlank()) score += 3f
            if (!book.description.isNullOrBlank()) score += 2f
            if (!book.publisher.isNullOrBlank()) score += 1f
            if (!book.year.isNullOrBlank()) score += 1f
            
            // Prefer better formats
            val formatPriority = FORMAT_PRIORITY[book.extension?.lowercase()] ?: 0
            score += formatPriority * 0.1f
            
            // Prefer reasonable file sizes
            if (book.filesize != null) {
                val size = book.filesize
                score += when {
                    size > 100_000_000 -> -1f // Very large files might be low quality scans
                    size > 1_000_000 -> 1f   // Good size
                    size > 100_000 -> 0.5f   // Acceptable size
                    else -> -0.5f             // Suspiciously small
                }
            }
            
            // Prefer higher search scores
            book.score?.let { score += it * 0.1f }
            
            score
        } ?: books.first()
    }
    
    /**
     * Create BookSource from raw book data
     */
    private fun createBookSource(conceptId: String, book: RawBookData): BookSource {
        return BookSource(
            md5 = book.md5,
            conceptId = conceptId,
            format = book.extension?.lowercase() ?: "unknown",
            fileSize = formatFileSize(book.filesize),
            downloadMirrors = emptyList(), // Will be populated when needed
            reliability = calculateReliability(book),
            quality = book.quality,
            annasArchiveUrl = "https://annas-archive.org/md5/${book.md5}"
        )
    }
    
    /**
     * Calculate reliability score for a book source
     */
    private fun calculateReliability(book: RawBookData): Float {
        var reliability = 0.5f // Base score
        
        // ISBN bonus
        if (!book.isbn.isNullOrBlank()) reliability *= HAS_ISBN_BONUS
        
        // Description bonus
        if (!book.description.isNullOrBlank() && book.description.length > 50) {
            reliability *= HAS_DESCRIPTION_BONUS
        }
        
        // Publisher bonus
        if (!book.publisher.isNullOrBlank()) reliability *= HAS_PUBLISHER_BONUS
        
        // File size bonus
        if (book.filesize != null && book.filesize > 1_000_000) {
            reliability *= GOOD_FILESIZE_BONUS
        }
        
        // Source bonus
        when (book.originalSource) {
            "Library Genesis" -> reliability *= 1.3f
            "Internet Archive" -> reliability *= 1.2f
            "Z-Library" -> reliability *= 1.1f
        }
        
        // Format bonus
        val formatPriority = FORMAT_PRIORITY[book.extension?.lowercase()] ?: 0  
        reliability += formatPriority * 0.01f
        
        return reliability.coerceIn(0.1f, 1.0f)
    }
    
    /**
     * Aggregate metadata from multiple sources
     */
    private fun aggregateMetadata(books: List<RawBookData>): BookMetadata? {
        val isbns = books.mapNotNull { it.isbn }.distinct()
        val languages = books.mapNotNull { it.language }.distinct()
        val years = books.mapNotNull { it.year }.distinct()
        
        if (isbns.isEmpty() && languages.isEmpty() && years.isEmpty()) return null
        
        return BookMetadata(
            isbn = isbns.firstOrNull(),
            alternativeIsbns = isbns.drop(1),
            languages = languages,
            publishYear = years.firstOrNull(),
            alternativeYears = years.drop(1),
            subjects = aggregateSubjects(books),
            enhancedDescription = selectBestDescription(books)
        )
    }
    
    /**
     * Aggregate subjects/topics from all sources
     */
    private fun aggregateSubjects(books: List<RawBookData>): List<String> {
        return books.flatMap { it.categories }
            .groupBy { it.lowercase() }
            .filter { (_, occurrences) -> occurrences.size >= 2 || books.size == 1 }
            .keys.toList()
    }
    
    /**
     * Aggregate categories, preferring most common ones
     */
    private fun aggregateCategories(books: List<RawBookData>): List<String> {
        return books.flatMap { it.categories }
            .groupBy { it }
            .toList()
            .sortedByDescending { (_, occurrences) -> occurrences.size }
            .take(5)
            .map { (category, _) -> category }
    }
    
    /**
     * Select best thumbnail URL
     */
    private fun selectBestThumbnail(books: List<RawBookData>): String? {
        return books.firstNotNullOfOrNull { it.coverUrl }
    }
    
    /**
     * Select best description
     */
    private fun selectBestDescription(books: List<RawBookData>): String? {
        return books.filter { !it.description.isNullOrBlank() }
            .maxByOrNull { it.description!!.length }
            ?.description
    }
    
    /**
     * Select best publisher
     */
    private fun selectBestPublisher(books: List<RawBookData>): String? {
        return books.mapNotNull { it.publisher }
            .groupBy { it }
            .maxByOrNull { (_, occurrences) -> occurrences.size }
            ?.key
    }
    
    /**
     * Clean and normalize title
     */
    private fun cleanTitle(title: String): String {
        return title.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\[.*?\\]"), "") // Remove bracketed content
            .trim()
    }
    
    /**
     * Clean and normalize author
     */
    private fun cleanAuthor(author: String): String {
        return author.trim()
            .replace(Regex("\\s+"), " ")
            .split(",", ";", "&", " and ")
            .first()
            .trim()
    }
    
    /**
     * Format file size for display
     */
    private fun formatFileSize(bytes: Long?): String? {
        if (bytes == null) return null
        
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/**
 * Extension function to get best quality score for sorting
 */
private fun BookConcept.bestQualityScore(): Float {
    return sources.maxOfOrNull { it.reliability } ?: 0f
}