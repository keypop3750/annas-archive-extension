package eu.kanade.tachiyomi.extension.all.annasarchive

import eu.kanade.tachiyomi.extension.all.annasarchive.aggregation.BookConceptAggregator
import eu.kanade.tachiyomi.extension.all.annasarchive.api.AnnasArchiveApiClient
import eu.kanade.tachiyomi.extension.all.annasarchive.cache.BookConceptCache
import eu.kanade.tachiyomi.extension.all.annasarchive.download.CaptchaWebViewHandler
import eu.kanade.tachiyomi.extension.all.annasarchive.download.DownloadOrchestrator
import eu.kanade.tachiyomi.extension.all.annasarchive.download.MirrorAvailabilityTester
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookConcept
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookSource
import eu.kanade.tachiyomi.extension.all.annasarchive.model.DownloadMirror
import eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType
import eu.kanade.tachiyomi.extension.all.annasarchive.model.determineMirrorType
import eu.kanade.tachiyomi.extension.all.annasarchive.model.extractDomain
import eu.kanade.tachiyomi.extension.all.annasarchive.model.toBestSBook
import eu.kanade.tachiyomi.extension.all.annasarchive.model.toBookConcept
import eu.kanade.tachiyomi.extension.all.annasarchive.selection.SourceSelectionEngine
import eu.kanade.tachiyomi.extension.all.annasarchive.selection.SourceSelectionData
import eu.kanade.tachiyomi.extension.all.annasarchive.selection.NetworkCondition
import eu.kanade.tachiyomi.extension.all.annasarchive.selection.UserPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SBook
import eu.kanade.tachiyomi.source.model.SBooksPage
import eu.kanade.tachiyomi.source.online.BookCatalogueSource
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable

/**
 * Anna's Archive extension for Yokai manga reader
 * Provides access to books (PDF/EPUB/MOBI) from Anna's Archive shadow library
 */
class AnnasArchiveSource : BookCatalogueSource {
    
    override val name = "Anna's Archive"
    override val baseUrl = "https://annas-archive.org"
    override val lang = "all"
    override val supportsLatest = true
    
    override val client: OkHttpClient = network.cloudflareClient
    
    private val apiClient = AnnasArchiveApiClient(client)
    private val aggregator = BookConceptAggregator()
    private val cache = BookConceptCache()
    private val sourceSelectionEngine = SourceSelectionEngine()
    
    // Phase 4: Download Integration Components
    private val captchaHandler = eu.kanade.tachiyomi.extension.all.annasarchive.download.CaptchaWebViewHandler()
    private val mirrorTester = eu.kanade.tachiyomi.extension.all.annasarchive.download.MirrorAvailabilityTester(client)
    private val downloadOrchestrator = eu.kanade.tachiyomi.extension.all.annasarchive.download.DownloadOrchestrator(
        apiClient, client, captchaHandler, mirrorTester
    )
    
    // Popular/Latest book queries
    private val popularQueries = listOf(
        "fiction", "novel", "programming", "science", "history", 
        "philosophy", "psychology", "business", "art", "cooking"
    )
    
    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("User-Agent", "Yokai-Extension/1.0 (https://github.com/null2264/yokai)")
            .add("Accept-Language", "en-US,en;q=0.9")
    }
    
    // === Popular Books ===
    
    override fun popularBooksRequest(page: Int): Request {
        // Use rotating popular queries to simulate "popular" books
        val queryIndex = (page - 1) % popularQueries.size
        val query = popularQueries[queryIndex]
        return GET("$baseUrl/search?q=$query&page=$page", headers)
    }
    
    override fun popularBooksParse(response: Response): SBooksPage {
        return runBlocking {
            try {
                val page = extractPageFromUrl(response.request.url.toString())
                val queryIndex = (page - 1) % popularQueries.size
                val query = popularQueries[queryIndex]
                
                // Check cache first
                cache.getSearchResults(query, page)?.let { cachedResults ->
                    val sBooks = cachedResults.mapNotNull { it.toBestSBook() }
                    return@runBlocking SBooksPage(sBooks, hasNextPage = true)
                }
                
                // Fetch from API
                val searchResponse = apiClient.searchBooks(
                    query = query,
                    page = page,
                    resultsPerPage = 25
                )
                
                val concepts = aggregator.aggregateBooks(searchResponse.parseBooks())
                cache.putSearchResults(query, page, concepts)
                
                val sBooks = concepts.mapNotNull { it.toBestSBook() }
                SBooksPage(sBooks, hasNextPage = sBooks.size >= 25)
                
            } catch (e: Exception) {
                SBooksPage(emptyList(), hasNextPage = false)
            }
        }
    }
    
    // === Latest Books ===
    
    override fun latestUpdatesRequest(page: Int): Request {
        // Use recent queries with date sorting
        return GET("$baseUrl/search?q=*&sort=date_added&page=$page", headers)
    }
    
    override fun latestUpdatesParse(response: Response): SBooksPage {
        return runBlocking {
            try {
                val page = extractPageFromUrl(response.request.url.toString())
                
                // Check cache first
                val cacheKey = "latest"
                cache.getSearchResults(cacheKey, page)?.let { cachedResults ->
                    val sBooks = cachedResults.mapNotNull { it.toBestSBook() }
                    return@runBlocking SBooksPage(sBooks, hasNextPage = true)
                }
                
                // Fetch from API with date sorting
                val searchResponse = apiClient.searchBooks(
                    query = "",
                    page = page,
                    resultsPerPage = 25,
                    sort = "date_added_to_annas_archive_desc"
                )
                
                val concepts = aggregator.aggregateBooks(searchResponse.parseBooks())
                cache.putSearchResults(cacheKey, page, concepts)
                
                val sBooks = concepts.mapNotNull { it.toBestSBook() }
                SBooksPage(sBooks, hasNextPage = sBooks.size >= 25)
                
            } catch (e: Exception) {
                SBooksPage(emptyList(), hasNextPage = false)
            }
        }
    }
    
    // === Search ===
    
    override fun searchBooksRequest(page: Int, query: String, filters: FilterList): Request {
        val languageFilter = filters.find { it is LanguageFilter } as? LanguageFilter
        val formatFilter = filters.find { it is FormatFilter } as? FormatFilter
        
        val url = buildString {
            append("$baseUrl/search?q=")
            append(query.trim().ifEmpty { "*" })
            append("&page=$page")
            
            languageFilter?.let { filter ->
                if (filter.state != 0) {
                    append("&lang=${filter.values[filter.state]}")
                }
            }
            
            formatFilter?.let { filter ->
                val selectedFormats = filter.state
                    .mapIndexedNotNull { index, selected -> 
                        if (selected) filter.values[index] else null 
                    }
                if (selectedFormats.isNotEmpty()) {
                    append("&ext=${selectedFormats.joinToString(",")}")
                }
            }
        }
        
        return GET(url, headers)
    }
    
    override fun searchBooksParse(response: Response): SBooksPage {
        return runBlocking {
            try {
                val url = response.request.url
                val query = url.queryParameter("q") ?: ""
                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                val language = url.queryParameter("lang")
                val formats = url.queryParameter("ext")?.split(",") ?: emptyList()
                
                // Check cache first
                cache.getSearchResults(query, page)?.let { cachedResults ->
                    val sBooks = cachedResults.mapNotNull { it.toBestSBook() }
                    return@runBlocking SBooksPage(sBooks, hasNextPage = true)
                }
                
                // Fetch from API
                val searchResponse = apiClient.searchBooks(
                    query = query,
                    page = page,
                    resultsPerPage = 25,
                    language = language,
                    format = formats
                )
                
                val concepts = aggregator.aggregateBooks(searchResponse.parseBooks())
                cache.putSearchResults(query, page, concepts)
                
                val sBooks = concepts.mapNotNull { it.toBestSBook() }
                SBooksPage(sBooks, hasNextPage = sBooks.size >= 25)
                
            } catch (e: Exception) {
                SBooksPage(emptyList(), hasNextPage = false)
            }
        }
    }
    
    // === Book Details ===
    
    override fun bookDetailsRequest(book: SBook): Request {
        return GET(book.url, headers)
    }
    
    override fun bookDetailsParse(response: Response): SBook {
        return runBlocking {
            try {
                // Extract concept ID and source MD5 from URL
                val url = response.request.url.toString()
                val conceptId = extractConceptIdFromUrl(url)
                val sourceMd5 = extractSourceMd5FromUrl(url)
                
                // Try to get from cache first
                cache.getBookConcept(conceptId)?.let { concept ->
                    val source = concept.sources.find { it.md5 == sourceMd5 }
                        ?: concept.getBestSource()
                    source?.let { 
                        return@runBlocking concept.toSBookWithSource(it)
                    }
                }
                
                // If not in cache, we need to reconstruct from the request
                // This shouldn't normally happen if the flow is correct
                throw Exception("Book not found in cache")
                
            } catch (e: Exception) {
                // Return minimal book data
                SBook().apply {
                    url = response.request.url.toString()
                    title = "Unknown Book"
                    author = "Unknown Author"
                    status = SBook.COMPLETED
                    initialized = true
                }
            }
        }
    }
    
    // === Download Links ===
    
    override fun getBookUrl(book: SBook): String {
        return runBlocking {
            try {
                val sourceMd5 = extractSourceMd5FromUrl(book.url) ?: throw Exception("No MD5 found")
                
                // Check cache first
                cache.getDownloadUrls(sourceMd5)?.let { cachedUrls ->
                    return@runBlocking cachedUrls.firstOrNull() ?: throw Exception("No cached URLs")
                }
                
                // Fetch download URLs from API
                val downloadResponse = apiClient.getDownloadLinks(sourceMd5)
                val urls = downloadResponse.downloadUrls.map { it.url }
                
                if (urls.isEmpty()) {
                    throw Exception("No download URLs available")
                }
                
                // Cache the URLs
                cache.putDownloadUrls(sourceMd5, urls)
                
                // Return the first (usually best) URL
                urls.first()
                
            } catch (e: Exception) {
                throw Exception("Failed to get download URL: ${e.message}")
            }
        }
    }
    
    // === Filters ===
    
    override fun getFilterList(): FilterList {
        return FilterList(
            LanguageFilter(),
            FormatFilter()
        )
    }
    
    // === Filter Classes ===
    
    private class LanguageFilter : Filter.Select<String>(
        "Language", 
        arrayOf("All", "English", "Spanish", "French", "German", "Italian", "Portuguese", "Russian", "Chinese", "Japanese")
    ) {
        val values = arrayOf("", "en", "es", "fr", "de", "it", "pt", "ru", "zh", "ja")
    }
    
    private class FormatFilter : Filter.Group<Filter.CheckBox>(
        "Format",
        listOf(
            Filter.CheckBox("PDF"),
            Filter.CheckBox("EPUB"), 
            Filter.CheckBox("MOBI"),
            Filter.CheckBox("AZW3"),
            Filter.CheckBox("FB2"),
            Filter.CheckBox("TXT")
        )
    ) {
        val values = arrayOf("pdf", "epub", "mobi", "azw3", "fb2", "txt")
    }
    
    // === Helper Functions ===
    
    private fun extractPageFromUrl(url: String): Int {
        return Regex("page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }
    
    private fun extractConceptIdFromUrl(url: String): String {
        return Regex("/concept/([a-f0-9]+)").find(url)?.groupValues?.get(1) ?: ""
    }
    
    private fun extractSourceMd5FromUrl(url: String): String? {
        return Regex("/source/([a-f0-9]{32})").find(url)?.groupValues?.get(1)
    }
    
    // === Unused Methods (required by interface) ===
    
    override fun fetchPopularBooks(page: Int): Observable<SBooksPage> {
        return Observable.fromCallable { popularBooksParse(popularBooksRequest(page).let { client.newCall(it).execute() }) }
    }
    
    override fun fetchLatestUpdates(page: Int): Observable<SBooksPage> {
        return Observable.fromCallable { latestUpdatesParse(latestUpdatesRequest(page).let { client.newCall(it).execute() }) }
    }
    
    override fun fetchSearchBooks(page: Int, query: String, filters: FilterList): Observable<SBooksPage> {
        return Observable.fromCallable { searchBooksParse(searchBooksRequest(page, query, filters).let { client.newCall(it).execute() }) }
    }
    
    override fun fetchBookDetails(book: SBook): Observable<SBook> {
        return Observable.fromCallable { bookDetailsParse(bookDetailsRequest(book).let { client.newCall(it).execute() }) }
    }
    
    // === Phase 3: Source Selection Methods ===
    
    /**
     * Get all available sources for a book concept (for source selection UI)
     * This method is called by Yokai when user wants to see all format options
     */
    suspend fun getBookSources(conceptId: String): List<BookSource> {
        return cache.getBookConcept(conceptId)?.sources ?: emptyList()
    }
    
    /**
     * Generate source selection data for Yokai's source selection UI
     */
    suspend fun generateSourceSelectionData(
        conceptId: String,
        userPreferences: UserPreferences = UserPreferences(),
        networkCondition: NetworkCondition = NetworkCondition.WIFI_FAST
    ): SourceSelectionData? {
        val bookConcept = cache.getBookConcept(conceptId) ?: return null
        return sourceSelectionEngine.generateSourceSelectionData(bookConcept, userPreferences, networkCondition)
    }
    
    /**
     * Get download URL for a specific source (MD5)
     * This is called when user selects a specific format/source
     */
    suspend fun getDownloadUrlForSource(md5: String): String? {
        return runBlocking {
            try {
                // Check cache first
                cache.getDownloadUrls(md5)?.let { cachedUrls ->
                    return@runBlocking cachedUrls.firstOrNull()
                }
                
                // Fetch download URLs from API
                val downloadResponse = apiClient.getDownloadLinks(md5)
                val urls = downloadResponse.downloadUrls.map { it.url }
                
                if (urls.isEmpty()) {
                    return@runBlocking null
                }
                
                // Cache the URLs
                cache.putDownloadUrls(md5, urls)
                
                // Return the first (usually best) URL
                urls.first()
                
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Update source reliability based on download success/failure
     * This is called by Yokai after download attempts to improve recommendations
     */
    suspend fun updateSourceReliability(md5: String, success: Boolean) {
        cache.getBookConcept("")?.let { concept ->
            val updatedSources = concept.sources.map { source ->
                if (source.md5 == md5) {
                    // Simple reliability update algorithm
                    val newReliability = if (success) {
                        (source.reliability + 0.1f).coerceAtMost(1.0f)
                    } else {
                        (source.reliability - 0.1f).coerceAtLeast(0.1f)
                    }
                    source.copy(
                        reliability = newReliability,
                        lastVerified = System.currentTimeMillis()
                    )
                } else {
                    source
                }
            }
            
            val updatedConcept = concept.copy(sources = updatedSources)
            cache.updateBookConceptSources(concept.conceptId, updatedConcept)
        }
    }
    
    /**
     * Get enhanced book details with all sources for source selection
     * This provides the complete data needed for the source selection UI
     */
    suspend fun getEnhancedBookDetails(conceptId: String): BookConcept? {
        return cache.getBookConcept(conceptId)?.let { concept ->
            // Ensure all sources have up-to-date download mirrors
            val enhancedSources = concept.sources.map { source ->
                try {
                    val downloadResponse = apiClient.getDownloadLinks(source.md5)
                    val mirrors = downloadResponse.downloadUrls.map { downloadUrl ->
                        DownloadMirror(
                            url = downloadUrl.url,
                            type = determineMirrorType(downloadUrl.url),
                            domain = extractDomain(downloadUrl.url),
                            requiresCaptcha = downloadUrl.url.contains("slow_download"),
                            priority = when {
                                downloadUrl.url.contains("ipfs") -> 1
                                downloadUrl.url.contains("libgen") -> 2
                                else -> 3
                            }
                        )
                    }
                    source.copy(downloadMirrors = mirrors)
                } catch (e: Exception) {
                    source // Return original if mirror update fails
                }
            }
            
            val enhancedConcept = concept.copy(sources = enhancedSources)
            
            // Update cache with enhanced data
            cache.updateBookConceptSources(conceptId, enhancedConcept)
            
            enhancedConcept
        }
    }
    
    /**
     * Refresh source data for a book concept
     * This can be called to get the latest download mirrors and reliability data
     */
    suspend fun refreshBookSources(conceptId: String): Boolean {
        return try {
            getEnhancedBookDetails(conceptId) != null
        } catch (e: Exception) {
            false
        }
    }
    
    // Page list methods (not used for books)
    override fun pageListRequest(book: SBook): Request = throw UnsupportedOperationException("Books don't have pages")
    override fun pageListParse(response: Response): List<Page> = emptyList()
    override fun imageUrlRequest(page: Page): Request = throw UnsupportedOperationException("Books don't have image pages")
    override fun imageUrlParse(response: Response): String = ""
}