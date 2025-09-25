package eu.kanade.tachiyomi.extension.all.annasarchive.enhancement

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 5: User Preferences Manager
 * Handles user configuration for Anna's Archive extension with reactive updates
 */
class UserPreferencesManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "annas_archive_prefs"
        
        // Preference keys
        private const val KEY_PREFERRED_FORMATS = "preferred_formats"
        private const val KEY_MIRROR_PREFERENCE = "mirror_preference"
        private const val KEY_DOWNLOAD_TIMEOUT = "download_timeout"
        private const val KEY_CAPTCHA_AUTO_RESOLVE = "captcha_auto_resolve"
        private const val KEY_CACHE_SIZE_MB = "cache_size_mb"
        private const val KEY_PERFORMANCE_MODE = "performance_mode"
        private const val KEY_SHOW_FILE_SIZES = "show_file_sizes"
        private const val KEY_AGGRESSIVE_CACHING = "aggressive_caching"
        private const val KEY_PARALLEL_DOWNLOADS = "parallel_downloads"
        private const val KEY_BANDWIDTH_LIMIT = "bandwidth_limit"
        private const val KEY_RETRY_FAILED_DOWNLOADS = "retry_failed_downloads"
        private const val KEY_MIRROR_TIMEOUT_SEC = "mirror_timeout_sec"
        
        // Default values
        private val DEFAULT_PREFERRED_FORMATS = setOf("epub", "pdf")
        private const val DEFAULT_MIRROR_PREFERENCE = "fast"
        private const val DEFAULT_DOWNLOAD_TIMEOUT = 300 // 5 minutes
        private const val DEFAULT_CAPTCHA_AUTO_RESOLVE = true
        private const val DEFAULT_CACHE_SIZE_MB = 100
        private const val DEFAULT_PERFORMANCE_MODE = "balanced"
        private const val DEFAULT_SHOW_FILE_SIZES = true
        private const val DEFAULT_AGGRESSIVE_CACHING = false
        private const val DEFAULT_PARALLEL_DOWNLOADS = 2
        private const val DEFAULT_BANDWIDTH_LIMIT = 0 // No limit
        private const val DEFAULT_RETRY_FAILED_DOWNLOADS = true
        private const val DEFAULT_MIRROR_TIMEOUT_SEC = 30
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Reactive state flows
    private val _preferencesState = MutableStateFlow(loadPreferences())
    val preferencesState: StateFlow<UserPreferences> = _preferencesState.asStateFlow()
    
    /**
     * Load current preferences
     */
    private fun loadPreferences(): UserPreferences {
        return UserPreferences(
            preferredFormats = prefs.getStringSet(KEY_PREFERRED_FORMATS, DEFAULT_PREFERRED_FORMATS)?.toSet() 
                ?: DEFAULT_PREFERRED_FORMATS,
            mirrorPreference = MirrorPreference.valueOf(
                prefs.getString(KEY_MIRROR_PREFERENCE, DEFAULT_MIRROR_PREFERENCE)?.uppercase() ?: DEFAULT_MIRROR_PREFERENCE.uppercase()
            ),
            downloadTimeoutSeconds = prefs.getInt(KEY_DOWNLOAD_TIMEOUT, DEFAULT_DOWNLOAD_TIMEOUT),
            captchaAutoResolve = prefs.getBoolean(KEY_CAPTCHA_AUTO_RESOLVE, DEFAULT_CAPTCHA_AUTO_RESOLVE),
            cacheSizeMB = prefs.getInt(KEY_CACHE_SIZE_MB, DEFAULT_CACHE_SIZE_MB),
            performanceMode = PerformanceMode.valueOf(
                prefs.getString(KEY_PERFORMANCE_MODE, DEFAULT_PERFORMANCE_MODE)?.uppercase() ?: DEFAULT_PERFORMANCE_MODE.uppercase()
            ),
            showFileSizes = prefs.getBoolean(KEY_SHOW_FILE_SIZES, DEFAULT_SHOW_FILE_SIZES),
            aggressiveCaching = prefs.getBoolean(KEY_AGGRESSIVE_CACHING, DEFAULT_AGGRESSIVE_CACHING),
            maxParallelDownloads = prefs.getInt(KEY_PARALLEL_DOWNLOADS, DEFAULT_PARALLEL_DOWNLOADS),
            bandwidthLimitKBps = prefs.getInt(KEY_BANDWIDTH_LIMIT, DEFAULT_BANDWIDTH_LIMIT),
            retryFailedDownloads = prefs.getBoolean(KEY_RETRY_FAILED_DOWNLOADS, DEFAULT_RETRY_FAILED_DOWNLOADS),
            mirrorTimeoutSeconds = prefs.getInt(KEY_MIRROR_TIMEOUT_SEC, DEFAULT_MIRROR_TIMEOUT_SEC)
        )
    }
    
    /**
     * Update preferred book formats
     */
    fun setPreferredFormats(formats: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_PREFERRED_FORMATS, formats)
            .apply()
        updateState()
    }
    
    /**
     * Update mirror preference
     */
    fun setMirrorPreference(preference: MirrorPreference) {
        prefs.edit()
            .putString(KEY_MIRROR_PREFERENCE, preference.name.lowercase())
            .apply()
        updateState()
    }
    
    /**
     * Update download timeout
     */
    fun setDownloadTimeout(timeoutSeconds: Int) {
        prefs.edit()
            .putInt(KEY_DOWNLOAD_TIMEOUT, timeoutSeconds.coerceIn(30, 1800)) // 30s to 30min
            .apply()
        updateState()
    }
    
    /**
     * Update CAPTCHA auto-resolve setting
     */
    fun setCaptchaAutoResolve(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CAPTCHA_AUTO_RESOLVE, enabled)
            .apply()
        updateState()
    }
    
    /**
     * Update cache size limit
     */
    fun setCacheSizeMB(sizeMB: Int) {
        prefs.edit()
            .putInt(KEY_CACHE_SIZE_MB, sizeMB.coerceIn(10, 1000)) // 10MB to 1GB
            .apply()
        updateState()
    }
    
    /**
     * Update performance mode
     */
    fun setPerformanceMode(mode: PerformanceMode) {
        prefs.edit()
            .putString(KEY_PERFORMANCE_MODE, mode.name.lowercase())
            .apply()
        updateState()
    }
    
    /**
     * Update file size display setting
     */
    fun setShowFileSizes(show: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SHOW_FILE_SIZES, show)
            .apply()
        updateState()
    }
    
    /**
     * Update aggressive caching setting
     */
    fun setAggressiveCaching(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AGGRESSIVE_CACHING, enabled)
            .apply()
        updateState()
    }
    
    /**
     * Update parallel downloads limit
     */
    fun setMaxParallelDownloads(count: Int) {
        prefs.edit()
            .putInt(KEY_PARALLEL_DOWNLOADS, count.coerceIn(1, 5))
            .apply()
        updateState()
    }
    
    /**
     * Update bandwidth limit
     */
    fun setBandwidthLimit(limitKBps: Int) {
        prefs.edit()
            .putInt(KEY_BANDWIDTH_LIMIT, limitKBps.coerceAtLeast(0))
            .apply()
        updateState()
    }
    
    /**
     * Update retry failed downloads setting
     */
    fun setRetryFailedDownloads(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_RETRY_FAILED_DOWNLOADS, enabled)
            .apply()
        updateState()
    }
    
    /**
     * Update mirror timeout
     */
    fun setMirrorTimeout(timeoutSeconds: Int) {
        prefs.edit()
            .putInt(KEY_MIRROR_TIMEOUT_SEC, timeoutSeconds.coerceIn(10, 120)) // 10s to 2min
            .apply()
        updateState()
    }
    
    /**
     * Get current preferences (non-reactive)
     */
    fun getCurrentPreferences(): UserPreferences {
        return _preferencesState.value
    }
    
    /**
     * Reset all preferences to defaults
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        updateState()
    }
    
    /**
     * Export preferences as JSON string
     */
    fun exportPreferences(): String {
        val prefs = getCurrentPreferences()
        return """
            {
                "preferred_formats": ${prefs.preferredFormats.joinToString(",") { "\"$it\"" }},
                "mirror_preference": "${prefs.mirrorPreference.name.lowercase()}",
                "download_timeout": ${prefs.downloadTimeoutSeconds},
                "captcha_auto_resolve": ${prefs.captchaAutoResolve},
                "cache_size_mb": ${prefs.cacheSizeMB},
                "performance_mode": "${prefs.performanceMode.name.lowercase()}",
                "show_file_sizes": ${prefs.showFileSizes},
                "aggressive_caching": ${prefs.aggressiveCaching},
                "max_parallel_downloads": ${prefs.maxParallelDownloads},
                "bandwidth_limit_kbps": ${prefs.bandwidthLimitKBps},
                "retry_failed_downloads": ${prefs.retryFailedDownloads},
                "mirror_timeout_seconds": ${prefs.mirrorTimeoutSeconds}
            }
        """.trimIndent()
    }
    
    private fun updateState() {
        _preferencesState.value = loadPreferences()
    }
}

/**
 * User preferences data class
 */
data class UserPreferences(
    val preferredFormats: Set<String> = setOf("epub", "pdf"),
    val mirrorPreference: MirrorPreference = MirrorPreference.FAST,
    val downloadTimeoutSeconds: Int = 300,
    val captchaAutoResolve: Boolean = true,
    val cacheSizeMB: Int = 100,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val showFileSizes: Boolean = true,
    val aggressiveCaching: Boolean = false,
    val maxParallelDownloads: Int = 2,
    val bandwidthLimitKBps: Int = 0, // 0 = no limit
    val retryFailedDownloads: Boolean = true,
    val mirrorTimeoutSeconds: Int = 30
) {
    
    /**
     * Get cache size in bytes
     */
    val cacheSizeBytes: Long
        get() = cacheSizeMB * 1024L * 1024L
    
    /**
     * Get download timeout in milliseconds
     */
    val downloadTimeoutMs: Long
        get() = downloadTimeoutSeconds * 1000L
    
    /**
     * Get mirror timeout in milliseconds
     */
    val mirrorTimeoutMs: Long
        get() = mirrorTimeoutSeconds * 1000L
    
    /**
     * Check if format is preferred
     */
    fun isFormatPreferred(format: String): Boolean {
        return preferredFormats.any { it.equals(format, ignoreCase = true) }
    }
    
    /**
     * Get format priority (lower number = higher priority)
     */
    fun getFormatPriority(format: String): Int {
        val index = preferredFormats.indexOfFirst { it.equals(format, ignoreCase = true) }
        return if (index >= 0) index else Int.MAX_VALUE
    }
}

/**
 * Mirror selection preferences
 */
enum class MirrorPreference {
    FAST,       // Prioritize speed
    RELIABLE,   // Prioritize success rate
    BALANCED    // Balance speed and reliability
}

/**
 * Performance modes
 */
enum class PerformanceMode {
    FAST,       // Maximum speed, higher resource usage
    BALANCED,   // Good balance of speed and resource usage
    BATTERY     // Lower resource usage, may be slower
}