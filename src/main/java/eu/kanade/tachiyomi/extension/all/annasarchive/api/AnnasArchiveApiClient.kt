package eu.kanade.tachiyomi.extension.all.annasarchive.api

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Client for interacting with Anna's Archive API endpoints
 * Handles all HTTP communication and response parsing
 */
class AnnasArchiveApiClient(private val client: OkHttpClient) {
    
    companion object {
        private const val BASE_URL = "https://annas-archive.org"
        private const val USER_AGENT = "Yokai-Extension/1.0 (https://github.com/null2264/yokai)"
        
        // API endpoints
        private const val SEARCH_COUNTS_ENDPOINT = "/dyn/search_counts.json"
        private const val ELASTICSEARCH_ENDPOINT = "/db/aarecord_elasticsearch/"  
        private const val SLOW_DOWNLOAD_ENDPOINT = "/slow_download/"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
    
    /**
     * Search for books using the Elasticsearch endpoint
     */
    suspend fun searchBooks(
        query: String,
        page: Int = 1,
        resultsPerPage: Int = 25,
        language: String? = null,
        format: List<String> = emptyList(),
        sort: String = "most_likely_language_desc"
    ): ElasticsearchResponse {
        val url = buildSearchUrl(query, page, resultsPerPage, language, format, sort)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
            
        return executeRequest(request) { response ->
            val responseBody = response.body?.string() 
                ?: throw IOException("Empty response body")
            json.decodeFromString<ElasticsearchResponse>(responseBody)
        }
    }
    
    /**
     * Get search result counts and aggregations
     */
    suspend fun getSearchCounts(
        query: String,
        language: String? = null,
        format: List<String> = emptyList()
    ): SearchCountsResponse {
        val url = buildSearchCountsUrl(query, language, format)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
            
        return executeRequest(request) { response ->
            val responseBody = response.body?.string()
                ?: throw IOException("Empty response body")
            json.decodeFromString<SearchCountsResponse>(responseBody)
        }
    }
    
    /**
     * Get download URLs for a specific book by MD5
     */
    suspend fun getDownloadLinks(md5: String): SlowDownloadResponse {
        val url = "$BASE_URL$SLOW_DOWNLOAD_ENDPOINT$md5"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", "$BASE_URL/md5/$md5")
            .build()
            
        return executeRequest(request) { response ->
            val responseBody = response.body?.string()
                ?: throw IOException("Empty response body")
            json.decodeFromString<SlowDownloadResponse>(responseBody)
        }
    }
    
    /**
     * Get book details from individual book page (fallback method)
     */
    suspend fun getBookDetails(md5: String): String {
        val url = "$BASE_URL/md5/$md5"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
            
        return executeRequest(request) { response ->
            response.body?.string() ?: throw IOException("Empty response body")
        }
    }
    
    /**
     * Build search URL with all parameters
     */
    private fun buildSearchUrl(
        query: String,
        page: Int,
        resultsPerPage: Int,
        language: String?,
        format: List<String>,
        sort: String
    ): HttpUrl {
        val urlBuilder = "$BASE_URL$ELASTICSEARCH_ENDPOINT".toHttpUrl().newBuilder()
            .addQueryParameter("index", "aarecords")
            .addQueryParameter("body", buildSearchBody(query, page, resultsPerPage, language, format, sort))
            
        return urlBuilder.build()
    }
    
    /**
     * Build search counts URL
     */
    private fun buildSearchCountsUrl(
        query: String,
        language: String?,
        format: List<String>
    ): HttpUrl {
        val urlBuilder = "$BASE_URL$SEARCH_COUNTS_ENDPOINT".toHttpUrl().newBuilder()
            .addQueryParameter("index", "aarecords")  
            .addQueryParameter("body", buildCountsBody(query, language, format))
            
        return urlBuilder.build()
    }
    
    /**
     * Build Elasticsearch query body for search
     */
    private fun buildSearchBody(
        query: String,
        page: Int,
        resultsPerPage: Int,
        language: String?,
        format: List<String>,
        sort: String
    ): String {
        val from = (page - 1) * resultsPerPage
        val must = mutableListOf<String>()
        val filter = mutableListOf<String>()
        
        // Add main query
        if (query.isNotBlank()) {
            must.add("""
                {
                    "bool": {
                        "should": [
                            {"match": {"search_only_fields.search_title": {"query": "$query", "boost": 2.0}}},
                            {"match": {"search_only_fields.search_author": {"query": "$query", "boost": 1.5}}},
                            {"match": {"search_only_fields.search_text": "$query"}}
                        ]
                    }
                }
            """.trimIndent())
        }
        
        // Add language filter
        language?.let {
            filter.add("""{"term": {"search_only_fields.search_most_likely_language_code": "$it"}}""")
        }
        
        // Add format filter
        if (format.isNotEmpty()) {
            val formatTerms = format.joinToString(",") { "\"$it\"" }
            filter.add("""{"terms": {"extension_best": [$formatTerms]}}""")
        }
        
        // Add quality filter (exclude very low quality entries)
        filter.add("""{"range": {"filesize_best": {"gte": 1024}}}""") // At least 1KB
        
        val boolQuery = StringBuilder()
        boolQuery.append("{\"bool\": {")
        
        if (must.isNotEmpty()) {
            boolQuery.append("\"must\": [${must.joinToString(",")}]")
            if (filter.isNotEmpty()) boolQuery.append(",")
        }
        
        if (filter.isNotEmpty()) {
            boolQuery.append("\"filter\": [${filter.joinToString(",")}]")
        }
        
        boolQuery.append("}}")
        
        return """
        {
            "query": $boolQuery,
            "sort": [{"$sort": {"order": "desc"}}],
            "from": $from,
            "size": $resultsPerPage,
            "_source": [
                "md5",
                "search_only_fields.search_title",
                "search_only_fields.search_author", 
                "search_only_fields.search_publisher",
                "search_only_fields.search_year",
                "search_only_fields.search_most_likely_language_code",
                "title_best",
                "author_best",
                "publisher_best", 
                "year_best",
                "filesize_best",
                "extension_best",
                "cover_url_best",
                "stripped_description_best",
                "libgen_id",
                "isbn13_best",
                "isbn10_best"
            ]
        }
        """.trimIndent()
    }
    
    /**
     * Build query body for search counts
     */
    private fun buildCountsBody(
        query: String,
        language: String?,
        format: List<String>
    ): String {
        // Simplified version of search body just for counts
        return """
        {
            "query": {
                "bool": {
                    "must": [
                        {"multi_match": {"query": "$query", "fields": ["search_only_fields.search_title", "search_only_fields.search_author"]}}
                    ]
                }
            },
            "aggs": {
                "search_only_fields.search_most_likely_language_code": {
                    "terms": {"field": "search_only_fields.search_most_likely_language_code", "size": 20}
                },
                "extension_best": {
                    "terms": {"field": "extension_best", "size": 20}
                }
            },
            "size": 0
        }
        """.trimIndent()
    }
    
    /**
     * Execute HTTP request with error handling
     */
    private suspend fun <T> executeRequest(request: Request, parser: (Response) -> T): T {
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }
                parser(response)
            }
        } catch (e: Exception) {
            when (e) {
                is IOException -> throw e
                else -> throw IOException("Request failed: ${e.message}", e)
            }
        }
    }
}