package eu.kanade.tachiyomi.extension.all.annasarchive.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Raw API response models for Anna's Archive API endpoints
 */

@Serializable
data class SearchCountsResponse(
    @SerialName("aggregations")
    val aggregations: JsonObject
)

@Serializable
data class ElasticsearchResponse(
    @SerialName("hits")
    val hits: HitsContainer
)

@Serializable
data class HitsContainer(
    @SerialName("hits")
    val hits: List<SearchHit>
)

@Serializable
data class SearchHit(
    @SerialName("_source")
    val source: JsonObject,
    @SerialName("_score")
    val score: Float? = null
)

@Serializable
data class SlowDownloadResponse(
    @SerialName("download_urls")
    val downloadUrls: List<DownloadUrl>
)

@Serializable
data class DownloadUrl(
    @SerialName("url")
    val url: String,
    @SerialName("description")
    val description: String? = null
)

/**
 * Intermediate parsing models for processing raw API data
 */

data class RawBookData(
    val md5: String,
    val title: String,
    val author: String,
    val publisher: String? = null,
    val year: String? = null,
    val language: String? = null,
    val filesize: Long? = null,
    val extension: String? = null,
    val coverUrl: String? = null,
    val libgenId: String? = null,
    val description: String? = null,
    val isbn: String? = null,
    val categories: List<String> = emptyList(),
    val quality: String? = null,
    val originalSource: String? = null,
    val score: Float? = null
)

/**
 * Extension functions for parsing raw API responses
 */

/**
 * Parse search counts aggregation for quick stats
 */
fun SearchCountsResponse.parseLanguageCounts(): Map<String, Int> {
    return try {
        val langBuckets = aggregations["search_only_fields.search_most_likely_language_code"]
            ?.let { it as? JsonObject }
            ?.get("buckets")
            ?.let { it as? JsonArray }
            ?: return emptyMap()
            
        langBuckets.mapNotNull { bucket ->
            val bucketObj = bucket as? JsonObject ?: return@mapNotNull null
            val key = (bucketObj["key"] as? JsonPrimitive)?.content
            val docCount = (bucketObj["doc_count"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (key != null && docCount != null) key to docCount else null
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * Parse search hits into structured book data
 */
fun ElasticsearchResponse.parseBooks(): List<RawBookData> {
    return hits.hits.mapNotNull { hit ->
        try {
            parseBookFromHit(hit)
        } catch (e: Exception) {
            null // Skip malformed entries
        }
    }
}

/**
 * Parse individual search hit into book data
 */
private fun parseBookFromHit(hit: SearchHit): RawBookData? {
    val source = hit.source
    
    // Extract MD5 - required field
    val md5 = source.extractString("md5") ?: return null
    
    // Extract basic fields
    val title = source.extractString("search_only_fields.search_title") 
        ?: source.extractString("title_best") 
        ?: "Unknown Title"
        
    val author = source.extractString("search_only_fields.search_author")
        ?: source.extractString("author_best")
        ?: "Unknown Author"
        
    val publisher = source.extractString("search_only_fields.search_publisher")
        ?: source.extractString("publisher_best")
        
    val year = source.extractString("search_only_fields.search_year")
        ?: source.extractString("year_best")
        
    val language = source.extractString("search_only_fields.search_most_likely_language_code")
        ?: source.extractString("language_codes")?.split(",")?.firstOrNull()
        
    // Extract file information
    val filesize = source.extractString("filesize_best")?.toLongOrNull()
    val extension = source.extractString("extension_best")
    val coverUrl = source.extractString("cover_url_best")
    
    // Extract identifiers
    val libgenId = source.extractString("libgen_id")
    val isbn = extractIsbn(source)
    
    // Extract description
    val description = source.extractString("stripped_description_best")
        ?: source.extractString("search_only_fields.search_text")?.take(500)
        
    // Extract categories/subjects
    val categories = extractCategories(source)
    
    // Determine quality and source
    val quality = determineQuality(source)
    val originalSource = determineOriginalSource(source)
    
    return RawBookData(
        md5 = md5,
        title = title,
        author = author,
        publisher = publisher,
        year = year,
        language = language,
        filesize = filesize,
        extension = extension,
        coverUrl = coverUrl,
        libgenId = libgenId,
        description = description,
        isbn = isbn,
        categories = categories,
        quality = quality,
        originalSource = originalSource,
        score = hit.score
    )
}

/**
 * Extract string value from nested JSON structure
 */
private fun JsonObject.extractString(path: String): String? {
    return try {
        val parts = path.split(".")
        var current: JsonElement = this
        
        for (part in parts) {
            current = when (current) {
                is JsonObject -> current[part] ?: return null
                is JsonArray -> current.firstOrNull() ?: return null
                else -> return null
            }
        }
        
        when (current) {
            is JsonPrimitive -> current.content.takeIf { it.isNotEmpty() }
            is JsonArray -> (current.firstOrNull() as? JsonPrimitive)?.content
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Extract ISBN from various possible fields
 */
private fun extractIsbn(source: JsonObject): String? {
    val possibleFields = listOf(
        "search_only_fields.search_isbn13",
        "isbn13_best",
        "search_only_fields.search_isbn10", 
        "isbn10_best"
    )
    
    for (field in possibleFields) {
        source.extractString(field)?.let { isbn ->
            if (isbn.replace("-", "").length in 10..13) {
                return isbn
            }
        }
    }
    
    return null
}

/**
 * Extract categories/subjects from source
 */
private fun extractCategories(source: JsonObject): List<String> {
    val categories = mutableSetOf<String>()
    
    // Check various category fields
    listOf(
        "search_only_fields.search_topic",
        "topic_best",
        "search_only_fields.search_series",
        "series_best"
    ).forEach { field ->
        source.extractString(field)?.let { value ->
            categories.addAll(value.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() })
        }
    }
    
    return categories.toList()
}

/**
 * Determine quality based on available metadata
 */
private fun determineQuality(source: JsonObject): String? {
    // Check for quality indicators
    val hasGoodMetadata = listOf(
        "publisher_best",
        "year_best", 
        "isbn13_best",
        "stripped_description_best"
    ).count { source.extractString(it) != null } >= 2
    
    val filesize = source.extractString("filesize_best")?.toLongOrNull()
    val hasReasonableSize = filesize != null && filesize > 1024 * 100 // > 100KB
    
    return when {
        hasGoodMetadata && hasReasonableSize -> "Good"
        hasGoodMetadata || hasReasonableSize -> "Fair"
        else -> "Basic"
    }
}

/**
 * Determine original source of the book
 */
private fun determineOriginalSource(source: JsonObject): String? {
    return when {
        source.extractString("libgen_id") != null -> "Library Genesis"
        source.extractString("zlib_id") != null -> "Z-Library"
        source.extractString("ia_id") != null -> "Internet Archive"
        else -> "Anna's Archive"
    }
}