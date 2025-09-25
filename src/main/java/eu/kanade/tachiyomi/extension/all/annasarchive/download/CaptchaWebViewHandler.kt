package eu.kanade.tachiyomi.extension.all.annasarchive.download

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 4: WebView CAPTCHA Handler
 * Handles CAPTCHA challenges and download link resolution
 */
class CaptchaWebViewHandler {
    
    companion object {
        private const val USER_AGENT = "Yokai-Extension/1.0 (https://github.com/null2264/yokai)"
        private const val TIMEOUT_MS = 30_000L // 30 seconds
    }
    
    /**
     * Resolve download URL through WebView CAPTCHA handling
     */
    suspend fun resolveDownloadUrl(
        webView: WebView,
        initialUrl: String,
        expectedPattern: Regex = Regex("https?://[^/]+/.*\\.(pdf|epub|mobi|azw3|fb2|djvu|txt)")
    ): String = suspendCancellableCoroutine { continuation ->
        
        val completionSignal = CompletableDeferred<String>()
        
        // Configure WebView
        webView.settings.apply {
            javaScriptEnabled = true
            userAgentString = USER_AGENT
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        
        // Set up WebView client to intercept download links
        webView.webViewClient = object : WebViewClient() {
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Check if this is a download link
                if (expectedPattern.matches(url)) {
                    if (!completionSignal.isCompleted) {
                        completionSignal.complete(url)
                    }
                    return true // Don't navigate to download
                }
                
                // Allow normal navigation for CAPTCHA pages
                return false
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // Check if page contains direct download links
                view?.evaluateJavascript("""
                    (function() {
                        var links = document.querySelectorAll('a[href*=".pdf"], a[href*=".epub"], a[href*=".mobi"]');
                        if (links.length > 0) {
                            return links[0].href;
                        }
                        return null;
                    })();
                """) { result ->
                    if (result != "null" && result.isNotEmpty()) {
                        val downloadUrl = result.trim('"')
                        if (expectedPattern.matches(downloadUrl) && !completionSignal.isCompleted) {
                            completionSignal.complete(downloadUrl)
                        }
                    }
                }
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.url?.toString() == initialUrl && !completionSignal.isCompleted) {
                    completionSignal.completeExceptionally(
                        Exception("WebView error: ${error?.description}")
                    )
                }
            }
        }
        
        // Set up timeout
        val timeoutRunnable = Runnable {
            if (!completionSignal.isCompleted) {
                completionSignal.completeExceptionally(
                    Exception("CAPTCHA resolution timeout after ${TIMEOUT_MS}ms")
                )
            }
        }
        
        webView.postDelayed(timeoutRunnable, TIMEOUT_MS)
        
        // Handle completion
        completionSignal.invokeOnCompletion { exception ->
            webView.removeCallbacks(timeoutRunnable)
            
            if (exception != null) {
                continuation.resumeWithException(exception)
            } else {
                try {
                    continuation.resume(completionSignal.getCompleted())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
        
        // Start loading the initial URL
        webView.loadUrl(initialUrl)
        
        // Handle cancellation
        continuation.invokeOnCancellation {
            webView.removeCallbacks(timeoutRunnable)
            webView.stopLoading()
            if (!completionSignal.isCompleted) {
                completionSignal.cancel()
            }
        }
    }
    
    /**
     * Resolve multiple download URLs in parallel
     */
    suspend fun resolveMultipleDownloadUrls(
        webView: WebView,
        urls: List<String>,
        maxConcurrent: Int = 3
    ): List<String> {
        val results = mutableListOf<String>()
        
        // Process URLs in batches to avoid overwhelming the server
        urls.chunked(maxConcurrent).forEach { batch ->
            batch.forEach { url ->
                try {
                    val resolvedUrl = resolveDownloadUrl(webView, url)
                    results.add(resolvedUrl)
                } catch (e: Exception) {
                    // Log error but continue with other URLs
                    println("Failed to resolve $url: ${e.message}")
                }
            }
        }
        
        return results
    }
    
    /**
     * Check if URL requires CAPTCHA resolution
     */
    fun requiresCaptcha(url: String): Boolean {
        return when {
            url.contains("slow_download") -> true
            url.contains("captcha") -> true
            url.contains("cloudflare") -> true
            url.contains("annas-archive.org") && !isDirectDownload(url) -> true
            else -> false
        }
    }
    
    /**
     * Check if URL is a direct download link
     */
    private fun isDirectDownload(url: String): Boolean {
        val directPatterns = listOf(
            Regex(".*\\.(pdf|epub|mobi|azw3|fb2|djvu|txt)$"),
            Regex(".*download.*\\.(pdf|epub|mobi|azw3|fb2|djvu|txt)"),
            Regex(".*file.*\\.(pdf|epub|mobi|azw3|fb2|djvu|txt)")
        )
        
        return directPatterns.any { it.matches(url.lowercase()) }
    }
    
    /**
     * Clean up WebView resources
     */
    fun cleanup(webView: WebView) {
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
        webView.webViewClient = null
    }
}