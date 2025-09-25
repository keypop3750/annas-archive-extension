package eu.kanade.tachiyomi.extension.all.annasarchive.download

import android.webkit.WebView
import eu.kanade.tachiyomi.extension.all.annasarchive.api.AnnasArchiveApiClient
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookSource
import eu.kanade.tachiyomi.extension.all.annasarchive.model.DownloadMirror
import eu.kanade.tachiyomi.source.model.SBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Phase 4: Download Orchestrator
 * Coordinates the complete download flow with CAPTCHA handling and mirror testing
 */
class DownloadOrchestrator(
    private val apiClient: AnnasArchiveApiClient,
    private val client: OkHttpClient,
    private val captchaHandler: CaptchaWebViewHandler,
    private val mirrorTester: MirrorAvailabilityTester
) {
    
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
    }
    
    /**
     * Complete download flow for a book source
     */
    suspend fun initiateDownload(
        bookSource: BookSource,
        webView: WebView? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        
        try {
            // Step 1: Get fresh download links from API
            val downloadResponse = apiClient.getDownloadLinks(bookSource.md5)
            val mirrors = bookSource.downloadMirrors.ifEmpty { 
                // If no cached mirrors, extract from API response
                extractMirrorsFromResponse(downloadResponse.mirrors)
            }
            
            if (mirrors.isEmpty()) {
                return@withContext DownloadResult.Error("No download mirrors available")
            }
            
            // Step 2: Test mirror availability
            val bestMirror = mirrorTester.findBestMirror(mirrors)
                ?: return@withContext DownloadResult.Error("No available mirrors found")
            
            // Step 3: Handle CAPTCHA if needed
            val finalDownloadUrl = if (bestMirror.requiresCaptcha && webView != null) {
                resolveCaptchaUrl(bestMirror, webView)
            } else {
                bestMirror.url
            }
            
            // Step 4: Validate final URL
            val validatedUrl = validateDownloadUrl(finalDownloadUrl)
            
            DownloadResult.Success(
                downloadUrl = validatedUrl,
                mirror = bestMirror,
                bookSource = bookSource,
                fileSize = bookSource.fileSize,
                format = bookSource.format
            )
            
        } catch (e: Exception) {
            DownloadResult.Error("Download initiation failed: ${e.message}")
        }
    }
    
    /**
     * Resolve CAPTCHA challenge and get final download URL
     */
    private suspend fun resolveCaptchaUrl(
        mirror: DownloadMirror,
        webView: WebView
    ): String = withContext(Dispatchers.Main) {
        
        var lastException: Exception? = null
        
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                return@withContext captchaHandler.resolveDownloadUrl(webView, mirror.url)
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        
        throw lastException ?: Exception("CAPTCHA resolution failed after $MAX_RETRY_ATTEMPTS attempts")
    }
    
    /**
     * Validate that the download URL is accessible
     */
    private suspend fun validateDownloadUrl(url: String): String {
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .head() // HEAD request to check availability
                .header("User-Agent", "Yokai-Extension/1.0")
                .build()
            
            val response = client.newCall(request).execute()
            response.use {
                if (response.isSuccessful || response.code == 206) {
                    url
                } else {
                    throw Exception("URL not accessible: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            throw Exception("URL validation failed: ${e.message}")
        }
    }
    
    /**
     * Extract mirrors from API response
     */
    private fun extractMirrorsFromResponse(apiMirrors: List<String>): List<DownloadMirror> {
        return apiMirrors.mapIndexed { index, url ->
            DownloadMirror(
                url = url,
                type = determineMirrorType(url),
                domain = extractDomain(url),
                requiresCaptcha = captchaHandler.requiresCaptcha(url),
                estimatedSpeed = null,
                priority = index
            )
        }
    }
    
    /**
     * Determine mirror type from URL
     */
    private fun determineMirrorType(url: String): eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType {
        return when {
            url.contains("ipfs://") || url.contains("gateway") -> 
                eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType.IPFS
            url.contains("slow_download") -> 
                eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType.SLOW_DOWNLOAD
            url.contains("libgen") || url.contains("z-lib") -> 
                eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType.PARTNER
            else -> 
                eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType.DIRECT
        }
    }
    
    /**
     * Extract domain from URL
     */
    private fun extractDomain(url: String): String {
        return try {
            val cleanUrl = if (url.startsWith("//")) "https:$url" else url
            val domain = cleanUrl.substringAfter("://").substringBefore("/")
            domain.substringAfter("www.")
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Get download progress information
     */
    suspend fun getDownloadProgress(bookSource: BookSource): DownloadProgress? {
        // This would integrate with Yokai's download manager
        // For now, return null (not implemented)
        return null
    }
    
    /**
     * Cancel an ongoing download
     */
    suspend fun cancelDownload(bookSource: BookSource): Boolean {
        // This would integrate with Yokai's download manager
        // For now, return false (not implemented)
        return false
    }
    
    /**
     * Retry a failed download
     */
    suspend fun retryDownload(
        bookSource: BookSource,
        webView: WebView? = null
    ): DownloadResult {
        return initiateDownload(bookSource, webView)
    }
}

/**
 * Result of download initiation
 */
sealed class DownloadResult {
    data class Success(
        val downloadUrl: String,
        val mirror: DownloadMirror,
        val bookSource: BookSource,
        val fileSize: String?,
        val format: String
    ) : DownloadResult()
    
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : DownloadResult()
}

/**
 * Download progress information
 */
data class DownloadProgress(
    val bookSource: BookSource,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progress: Float,
    val status: DownloadStatus
)

/**
 * Download status
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    ERROR,
    CANCELLED
}