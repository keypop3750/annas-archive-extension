package eu.kanade.tachiyomi.extension.all.annasarchive.model

import eu.kanade.tachiyomi.source.model.SBook
import java.security.MessageDigest

/**
 * Extension functions to convert between Anna's Archive models and Tachiyomi's SBook model
 */

/**
 * Convert BookConcept to SBook using the best available source
 */
fun BookConcept.toBestSBook(preferredFormat: String? = null): SBook? {
    val bestSource = getBestSource(preferredFormat) ?: return null
    return toSBookWithSource(bestSource)
}

/**
 * Convert BookConcept to SBook using a specific source
 */
fun BookConcept.toSBookWithSource(source: BookSource): SBook {
    return SBook().apply {
        // Core properties
        url = "/concept/${conceptId}/source/${source.md5}"
        title = this@toSBookWithSource.title
        author = this@toSBookWithSource.author
        thumbnail_url = this@toSBookWithSource.thumbnail
        description = buildDescription(source)
        genre = categories.joinToString(", ").takeIf { it.isNotEmpty() } ?: "Book"
        status = SBook.COMPLETED // Books are always complete
        initialized = true
        
        // Book-specific properties (if SBook supports them)
        // These will be available if Yokai has enhanced SBook model
        try {
            // Use reflection to set fields that might exist in enhanced SBook
            val publisherField = this::class.java.getDeclaredField("publisher")
            publisherField.isAccessible = true
            publisherField.set(this, this@toSBookWithSource.publisher)
        } catch (e: Exception) {
            // Field doesn't exist, skip
        }
        
        try {
            val formatField = this::class.java.getDeclaredField("format")
            formatField.isAccessible = true
            formatField.set(this, source.format)
        } catch (e: Exception) {
            // Field doesn't exist, skip
        }
        
        try {
            val md5Field = this::class.java.getDeclaredField("md5")
            md5Field.isAccessible = true
            md5Field.set(this, source.md5)
        } catch (e: Exception) {
            // Field doesn't exist, skip
        }
    }
}

/**
 * Build comprehensive description combining book info and source details
 */
private fun BookConcept.buildDescription(source: BookSource): String {
    val parts = mutableListOf<String>()
    
    // Add main description if available
    description?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    
    // Add enhanced description from metadata
    metadata?.enhancedDescription?.takeIf { it.isNotEmpty() }?.let { 
        if (it != description) parts.add(it) 
    }
    
    // Add source information
    val sourceInfo = mutableListOf<String>()
    source.fileSize?.let { sourceInfo.add("Size: $it") }
    source.quality?.let { sourceInfo.add("Quality: $it") }
    if (source.downloadMirrors.isNotEmpty()) {
        sourceInfo.add("${source.downloadMirrors.size} mirrors available")
    }
    sourceInfo.add("Reliability: ${(source.reliability * 100).toInt()}%")
    
    if (sourceInfo.isNotEmpty()) {
        parts.add("\n📄 Format: ${source.format.uppercase()}\n${sourceInfo.joinToString(" • ")}")
    }
    
    // Add alternative formats info if multiple sources
    if (sources.size > 1) {
        val otherFormats = sources.filter { it.md5 != source.md5 }
            .map { it.format.uppercase() }.distinct().sorted()
        if (otherFormats.isNotEmpty()) {
            parts.add("\n📚 Also available in: ${otherFormats.joinToString(", ")}")
        }
    }
    
    // Add metadata subjects/tags
    metadata?.subjects?.takeIf { it.isNotEmpty() }?.let { subjects ->
        parts.add("\n🏷️ Subjects: ${subjects.take(5).joinToString(", ")}")
    }
    
    return parts.joinToString("\n\n")
}

/**
 * Convert SBook back to BookConcept for caching purposes
 */
fun SBook.toBookConcept(): BookConcept {
    val conceptId = generateConceptId(title, author)
    
    // Try to extract additional fields if they exist
    var md5: String? = null
    var format: String? = null
    var publisher: String? = null
    
    try {
        val md5Field = this::class.java.getDeclaredField("md5")
        md5Field.isAccessible = true
        md5 = md5Field.get(this) as? String
    } catch (e: Exception) {
        // Field doesn't exist
    }
    
    try {
        val formatField = this::class.java.getDeclaredField("format")
        formatField.isAccessible = true
        format = formatField.get(this) as? String
    } catch (e: Exception) {
        // Field doesn't exist
    }
    
    try {
        val publisherField = this::class.java.getDeclaredField("publisher")
        publisherField.isAccessible = true
        publisher = publisherField.get(this) as? String
    } catch (e: Exception) {
        // Field doesn't exist
    }
    
    val source = BookSource(
        md5 = md5 ?: "",
        conceptId = conceptId,
        format = format ?: "unknown",
        fileSize = null,
        downloadMirrors = emptyList(),
        reliability = 0.5f,
        annasArchiveUrl = "https://annas-archive.org/md5/$md5"
    )
    
    return BookConcept(
        conceptId = conceptId,
        title = title,
        author = author,
        thumbnail = thumbnail_url,
        description = description,
        publisher = publisher,
        categories = if (genre.isNullOrEmpty()) emptyList() else listOf(genre!!),
        sources = listOf(source)
    )
}

/**
 * Generate consistent concept ID from title and author
 */
fun generateConceptId(title: String, author: String?): String {
    val normalized = normalizeForConceptId(title) + "|" + normalizeForConceptId(author ?: "")
    return hashString(normalized)
}

/**
 * Normalize text for concept ID generation
 */
private fun normalizeForConceptId(text: String): String {
    return text.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), "") // Remove special characters
        .replace(Regex("\\s+"), " ") // Normalize whitespace
        .trim()
}

/**
 * Hash string to create consistent ID
 */
private fun hashString(input: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }.take(12) // Use first 12 chars
}

/**
 * Determine mirror type from URL
 */
internal fun determineMirrorType(url: String): MirrorType = when {
    url.contains("ipfs://") || url.contains("gateway") -> MirrorType.IPFS
    url.contains("slow_download") -> MirrorType.SLOW_DOWNLOAD
    url.contains("libgen") || url.contains("z-lib") -> MirrorType.PARTNER
    else -> MirrorType.DIRECT
}

/**
 * Extract domain from URL
 */
internal fun extractDomain(url: String): String {
    return try {
        val cleanUrl = if (url.startsWith("//")) "https:$url" else url
        val domain = cleanUrl.substringAfter("://").substringBefore("/")
        domain.substringAfter("www.")
    } catch (e: Exception) {
        "unknown"
    }
}