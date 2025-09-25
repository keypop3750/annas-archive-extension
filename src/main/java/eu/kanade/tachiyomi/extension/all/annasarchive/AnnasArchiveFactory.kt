package eu.kanade.tachiyomi.extension.all.annasarchive

import android.content.Context
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import okhttp3.OkHttpClient

class AnnasArchiveFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        // Use enhanced version with all Phase 1-5 features
        createEnhancedSource()
    )

    private fun createEnhancedSource(): Source {
        // Note: In a real extension, context would be injected properly
        // This is a simplified version for demonstration
        return try {
            // Try to create enhanced version with context
            val context = getApplicationContext()
            EnhancedAnnasArchiveSource(context, client)
        } catch (e: Exception) {
            // Fallback to basic version if enhanced features unavailable
            AnnasArchiveSource(client)
        }
    }
    
    // This would be properly injected in a real Tachiyomi extension
    private fun getApplicationContext(): Context {
        // In actual implementation, this would be provided by the extension framework
        throw UnsupportedOperationException("Context injection not implemented in this demo")
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Tachiyomi Anna's Archive Extension/1.0 (Enhanced)")
                .header("Accept", "application/json, text/html, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()
}