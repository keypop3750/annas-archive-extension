package eu.kanade.tachiyomi.extension.all.annasarchive

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.BookCatalogueSource
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*

class AnnasArchiveSource : BookCatalogueSource {
    override val name = "Anna's Archive"
    override val baseUrl = "https://annas-archive.li"
    override val lang = "en"
    override val supportsLatest = true

    override fun searchBooksRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "") // Default sorting by relevance (_score)
            .addQueryParameter("desc", "0") // Don't search descriptions by default
            
        // Add file format filters for books
        val bookFormats = listOf("pdf", "epub", "mobi", "azw3", "djvu", "fb2")
        bookFormats.forEach { format ->
            url.addQueryParameter("ext", format)
        }
        
        // Filter for book content types
        url.addQueryParameter("content", "book_nonfiction")
        url.addQueryParameter("content", "book_fiction") 
        url.addQueryParameter("content", "book_unknown")
        
        // Filter for downloadable content
        url.addQueryParameter("acc", "aa_download")
        
        return Request.Builder()
            .url(url.build())
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("DNT", "1")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
    }

    override fun searchBooksParse(response: Response): BooksPage {
        val document = Jsoup.parse(response.body!!.string())
        val books = mutableListOf<SBook>()
        
        // Anna's Archive uses CSS selectors based on the HTML structure we analyzed
        // Look for book result containers - these contain the structured book data
        val bookElements = document.select("div[class*='js-vim-focus'], div[class*='mb-'], article, .search-result, div:has(a[href*='/md5/'])")
        
        for (element in bookElements) {
            try {
                val book = parseBookElement(element)
                if (book != null) {
                    books.add(book)
                }
            } catch (e: Exception) {
                // Skip malformed entries
                continue
            }
        }
        
        // If we didn't find books in containers, try direct links
        if (books.isEmpty()) {
            val directLinks = document.select("a[href*='/md5/']")
            for (link in directLinks) {
                try {
                    val book = parseDirectBookLink(link)
                    if (book != null) {
                        books.add(book)
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }
        
        // Check if there are more pages by looking for pagination
        val hasNextPage = document.select("a[href*='page=']").any { link ->
            val text = link.text().lowercase()
            text.contains("next") || text.contains("»") || text.contains("→") ||
            link.attr("href").contains("page=${response.request.url.queryParameter("page")?.toIntOrNull()?.plus(1) ?: 2}")
        }
        
        return BooksPage(books, hasNextPage)
    }
    
    private fun parseBookElement(element: Element): SBook? {
        try {
            // Try to find MD5 link - this is the primary identifier in Anna's Archive
            val md5Link = element.selectFirst("a[href*='/md5/']") ?: return null
            val md5 = md5Link.attr("href").substringAfter("/md5/").substringBefore("/").substringBefore("?")
            if (md5.isEmpty() || md5.length != 32) return null
            
            val book = SBook.create()
            book.url = "/md5/$md5"
            
            // Extract title - Anna's Archive uses font-semibold class for titles
            val titleElement = element.selectFirst("a[href*='/md5/'].font-semibold") ?: md5Link
            book.title = titleElement.text().trim().takeIf { it.isNotEmpty() } ?: "Unknown Title"
            
            // Extract author - look for author links (usually contain /search?q= with author info)
            val authorElements = element.select("a[href*='/search?q=']").filter { link ->
                val href = link.attr("href")
                // Filter for author-type searches (not just any search)
                href.contains("author") || link.text().matches(Regex("[A-Z][a-z]+ [A-Z][a-z]+")) ||
                link.parent()?.text()?.lowercase()?.contains("author") == true
            }
            
            book.author = authorElements.joinToString(", ") { it.text().trim() }
                .takeIf { it.isNotEmpty() } ?: "Unknown Author"
            
            // Extract metadata from surrounding text
            val metaText = element.text()
            
            // Extract file format
            book.genre = extractFileFormat(metaText)
            
            // Extract file size and other metadata for description
            val metadata = mutableListOf<String>()
            val sizeMatch = Regex("([0-9.]+)\\s*(MB|GB|KB|TB)", RegexOption.IGNORE_CASE).find(metaText)
            if (sizeMatch != null) {
                metadata.add("Size: ${sizeMatch.value}")
            }
            
            val yearMatch = Regex("\\b(19|20)\\d{2}\\b").find(metaText)
            if (yearMatch != null) {
                metadata.add("Year: ${yearMatch.value}")
            }
            
            book.description = metadata.joinToString(" • ")
            
            // Try to extract thumbnail - Anna's Archive sometimes has cover images
            val thumbnail = element.selectFirst("img")?.attr("src")
            if (!thumbnail.isNullOrEmpty() && !thumbnail.contains("placeholder")) {
                book.thumbnail_url = if (thumbnail.startsWith("http")) thumbnail 
                    else "$baseUrl$thumbnail"
            }
            
            return book
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun parseDirectBookLink(link: Element): SBook? {
        try {
            val md5 = link.attr("href").substringAfter("/md5/").substringBefore("/").substringBefore("?")
            if (md5.isEmpty() || md5.length != 32) return null
            
            val book = SBook.create()
            book.url = "/md5/$md5"
            book.title = link.text().trim().takeIf { it.isNotEmpty() } ?: "Unknown Title"
            book.author = "Unknown Author"
            book.genre = "book"
            
            return book
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun extractFileFormat(text: String): String {
        val formats = listOf("pdf", "epub", "mobi", "azw3", "djvu", "fb2", "txt", "doc", "docx")
        return formats.find { text.lowercase().contains(".$it") || text.lowercase().contains(" $it ") } ?: "book"
    }

    override fun getBookDetails(book: SBook): SBook {
        val response = client.newCall(
            Request.Builder()
                .url("$baseUrl${book.url}")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
        ).execute()
        
        val document = Jsoup.parse(response.body!!.string())
        
        // Extract enhanced metadata from the book details page
        book.description = extractDescription(document)
        book.genre = extractGenre(document) ?: book.genre
        
        // Extract additional metadata that Anna's Archive provides
        extractAdditionalMetadata(document, book)
        
        book.status = SBook.COMPLETED // Books are complete by definition
        
        return book
    }
    
    private fun extractDescription(document: Document): String {
        val descriptions = mutableListOf<String>()
        
        // Look for description in various possible locations
        val descSelectors = listOf(
            "div:contains('Description')",
            ".description",
            "div:contains('Abstract')",
            "div:contains('Summary')",
            "[class*='description']",
            "[class*='summary']"
        )
        
        for (selector in descSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.ownText()
                if (text.length > 50) {
                    descriptions.add(text.trim())
                }
            }
        }
        
        // If no structured description, look for substantial text blocks
        if (descriptions.isEmpty()) {
            val textBlocks = document.select("div, p").mapNotNull { div ->
                val text = div.ownText()
                if (text.length > 100 && text.split(" ").size > 15) text.trim() else null
            }
            descriptions.addAll(textBlocks.take(3))
        }
        
        return descriptions.joinToString("\n\n").takeIf { it.isNotEmpty() } ?: "No description available"
    }
    
    private fun extractGenre(document: Document): String? {
        val genreSelectors = listOf(
            "div:contains('Subject')",
            "div:contains('Category')", 
            "div:contains('Topic')",
            ".genre",
            ".subject",
            ".category"
        )
        
        for (selector in genreSelectors) {
            val genre = document.selectFirst(selector)?.text()?.trim()
            if (!genre.isNullOrEmpty()) {
                return genre
            }
        }
        
        return null
    }
    
    private fun extractAdditionalMetadata(document: Document, book: SBook) {
        val metadataElements = document.select("div, span, td").filter { element ->
            val text = element.text().lowercase()
            text.contains("publisher") || text.contains("isbn") || text.contains("year") ||
            text.contains("pages") || text.contains("language") || text.contains("edition")
        }
        
        val metadata = mutableListOf<String>()
        
        // Try to extract publisher
        metadataElements.find { it.text().lowercase().contains("publisher") }?.let { element ->
            val publisher = element.text().substringAfter(":").trim()
            if (publisher.isNotEmpty()) {
                metadata.add("Publisher: $publisher")
            }
        }
        
        // Try to extract ISBN
        val isbnPattern = Regex("\\b(?:978|979)?[0-9]{9,12}[0-9Xx]\\b")
        val isbnMatch = isbnPattern.find(document.text())
        if (isbnMatch != null) {
            metadata.add("ISBN: ${isbnMatch.value}")
        }
        
        // Add existing description if any
        if (book.description.isNotEmpty()) {
            metadata.add(0, book.description)
        }
        
        book.description = metadata.joinToString("\n")
    }

    override fun getDownloadLink(book: SBook): String? {
        val response = client.newCall(
            Request.Builder()
                .url("$baseUrl${book.url}")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
        ).execute()
        
        val document = Jsoup.parse(response.body!!.string())
        
        // Anna's Archive typically shows multiple download mirrors
        // Look for download links in order of preference
        val downloadSelectors = listOf(
            "a[href*='download'][href*='$baseUrl']", // Internal download links
            "a:contains('Download')",
            "a[href*='libgen']", // Libgen mirrors
            "a[href*='z-lib']", // Z-Library mirrors
            "a[href*='sci-hub']", // Sci-Hub mirrors
            "a[href*='.pdf']",
            "a[href*='.epub']",
            ".download-link",
            "a[class*='download']",
            "a[href*='mirror']"
        )
        
        for (selector in downloadSelectors) {
            val downloadLinks = document.select(selector)
            for (downloadLink in downloadLinks) {
                val href = downloadLink.attr("href")
                if (href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript:")) {
                    val finalUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                    // Validate that this looks like a legitimate download URL
                    if (finalUrl.contains(Regex("\\.(pdf|epub|mobi|azw3|djvu|fb2)($|\\?)", RegexOption.IGNORE_CASE)) ||
                        finalUrl.contains("download") ||
                        finalUrl.contains("libgen") ||
                        finalUrl.contains("z-lib")) {
                        return finalUrl
                    }
                }
            }
        }
        
        // If no direct download link found, return the book page URL
        // The user can manually navigate to download options from there
        return "$baseUrl${book.url}"
    }

    override fun latestUpdatesRequest(page: Int): Request {
        // Anna's Archive doesn't have a traditional "latest" endpoint,
        // so we'll search with newest_added sort to get recently added books
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "") // Empty query to get all results
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "newest_added")
            
        // Add file format filters for books
        val bookFormats = listOf("pdf", "epub", "mobi", "azw3")
        bookFormats.forEach { format ->
            url.addQueryParameter("ext", format)
        }
        
        // Filter for book content types
        url.addQueryParameter("content", "book_nonfiction")
        url.addQueryParameter("content", "book_fiction")
        url.addQueryParameter("content", "book_unknown")
        
        // Filter for downloadable content
        url.addQueryParameter("acc", "aa_download")

        return Request.Builder()
            .url(url.build())
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
    }

    override fun latestUpdatesParse(response: Response): BooksPage {
        return searchBooksParse(response)
    }
}