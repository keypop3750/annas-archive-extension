package eu.kanade.tachiyomi.extension.all.annasarchive.enhancement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Phase 5: Advanced Error Handler
 * Comprehensive error handling with automatic retry, fallback strategies, and user-friendly messaging
 */
class AdvancedErrorHandler {
    
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 10000L
        
        // Error categories for different handling strategies
        private val NETWORK_ERRORS = setOf(
            IOException::class,
            SocketTimeoutException::class,
            UnknownHostException::class,
            SSLException::class
        )
        
        private val RATE_LIMIT_INDICATORS = listOf(
            "rate limit",
            "too many requests",
            "429",
            "quota exceeded"
        )
        
        private val CAPTCHA_INDICATORS = listOf(
            "captcha",
            "verify you are human",
            "security check",
            "cloudflare"
        )
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val errorHistory = mutableListOf<ErrorEvent>()
    private val historyMutex = Mutex()
    
    /**
     * Handle error with automatic categorization and appropriate response
     */
    suspend fun <T> handleWithRetry(
        operation: suspend () -> T,
        context: String,
        maxRetries: Int = MAX_RETRY_ATTEMPTS
    ): Result<T> {
        var lastException: Exception? = null
        
        repeat(maxRetries + 1) { attempt ->
            try {
                val result = operation()
                
                // If we succeeded after retries, log recovery
                if (attempt > 0) {
                    logError(
                        RecoveredError(
                            originalError = lastException ?: Exception("Unknown error"),
                            context = context,
                            attemptsToRecover = attempt + 1
                        )
                    )
                }
                
                return Result.success(result)
                
            } catch (e: Exception) {
                lastException = e
                
                val errorCategory = categorizeError(e)
                val shouldRetry = shouldRetryError(errorCategory, attempt, maxRetries)
                
                if (!shouldRetry) {
                    break
                }
                
                // Calculate delay with exponential backoff
                val delay = calculateRetryDelay(attempt, errorCategory)
                if (delay > 0) {
                    kotlinx.coroutines.delay(delay)
                }
            }
        }
        
        // Log final failure
        val categorizedError = CategorizedError(
            originalError = lastException ?: Exception("Unknown error"),
            category = categorizeError(lastException),
            context = context,
            attemptsMade = maxRetries + 1
        )
        
        logError(categorizedError)
        return Result.failure(categorizedError)
    }
    
    /**
     * Handle error without retry (for operations that shouldn't be retried)
     */
    suspend fun handleImmediately(error: Exception, context: String): CategorizedError {
        val categorizedError = CategorizedError(
            originalError = error,
            category = categorizeError(error),
            context = context,
            attemptsMade = 1
        )
        
        logError(categorizedError)
        return categorizedError
    }
    
    /**
     * Categorize error for appropriate handling
     */
    private fun categorizeError(exception: Exception?): ErrorCategory {
        if (exception == null) return ErrorCategory.UNKNOWN
        
        val message = exception.message?.lowercase() ?: ""
        
        return when {
            // Network connectivity issues
            NETWORK_ERRORS.any { it.isInstance(exception) } -> ErrorCategory.NETWORK
            
            // Rate limiting
            RATE_LIMIT_INDICATORS.any { message.contains(it) } -> ErrorCategory.RATE_LIMIT
            
            // CAPTCHA challenges
            CAPTCHA_INDICATORS.any { message.contains(it) } -> ErrorCategory.CAPTCHA_REQUIRED
            
            // Anna's Archive specific errors
            message.contains("anna") && message.contains("archive") -> ErrorCategory.ANNAS_ARCHIVE
            
            // Parsing/data format errors
            message.contains("json") || message.contains("parse") -> ErrorCategory.DATA_FORMAT
            
            // Authentication/authorization
            message.contains("unauthorized") || message.contains("forbidden") -> ErrorCategory.AUTH
            
            // Server errors (5xx)
            message.contains("500") || message.contains("502") || message.contains("503") -> ErrorCategory.SERVER_ERROR
            
            // Client errors (4xx)
            message.contains("400") || message.contains("404") -> ErrorCategory.CLIENT_ERROR
            
            else -> ErrorCategory.UNKNOWN
        }
    }
    
    /**
     * Determine if error should be retried
     */
    private fun shouldRetryError(category: ErrorCategory, attempt: Int, maxRetries: Int): Boolean {
        if (attempt >= maxRetries) return false
        
        return when (category) {
            ErrorCategory.NETWORK -> true
            ErrorCategory.RATE_LIMIT -> true
            ErrorCategory.SERVER_ERROR -> true
            ErrorCategory.CAPTCHA_REQUIRED -> false // Needs special handling
            ErrorCategory.DATA_FORMAT -> false // Unlikely to resolve with retry
            ErrorCategory.AUTH -> false // Needs credential fix
            ErrorCategory.CLIENT_ERROR -> false // Bad request won't improve
            ErrorCategory.ANNAS_ARCHIVE -> attempt < 2 // Limited retries for site issues
            ErrorCategory.UNKNOWN -> attempt < 1 // One retry for unknown issues
        }
    }
    
    /**
     * Calculate retry delay with exponential backoff and jitter
     */
    private fun calculateRetryDelay(attempt: Int, category: ErrorCategory): Long {
        val baseDelay = when (category) {
            ErrorCategory.RATE_LIMIT -> BASE_RETRY_DELAY_MS * 3 // Longer for rate limits
            ErrorCategory.NETWORK -> BASE_RETRY_DELAY_MS
            ErrorCategory.SERVER_ERROR -> BASE_RETRY_DELAY_MS * 2
            else -> BASE_RETRY_DELAY_MS
        }
        
        val exponentialDelay = baseDelay * (1L shl attempt) // 2^attempt
        val jitter = (Math.random() * baseDelay * 0.1).toLong() // 10% jitter
        
        return (exponentialDelay + jitter).coerceAtMost(MAX_RETRY_DELAY_MS)
    }
    
    /**
     * Log error for analysis and monitoring
     */
    private suspend fun logError(error: ErrorEvent) {
        historyMutex.withLock {
            errorHistory.add(error)
            
            // Keep only recent errors (last 100)
            if (errorHistory.size > 100) {
                errorHistory.removeAt(0)
            }
        }
        
        // Log to system for debugging
        when (error) {
            is CategorizedError -> {
                println("AnnasArchive Error [${error.category}] in ${error.context}: ${error.userFriendlyMessage}")
            }
            is RecoveredError -> {
                println("AnnasArchive Recovered after ${error.attemptsToRecover} attempts in ${error.context}")
            }
        }
    }
    
    /**
     * Get recent error statistics
     */
    suspend fun getErrorStats(): ErrorStatistics {
        return historyMutex.withLock {
            val recent = errorHistory.takeLast(50)
            val byCategory = recent.filterIsInstance<CategorizedError>()
                .groupBy { it.category }
                .mapValues { it.value.size }
            
            val recoveryRate = recent.count { it is RecoveredError }.toFloat() / 
                             recent.count { it is CategorizedError }.coerceAtLeast(1)
            
            ErrorStatistics(
                totalErrors = recent.size,
                errorsByCategory = byCategory,
                recoveryRate = recoveryRate,
                mostCommonError = byCategory.maxByOrNull { it.value }?.key
            )
        }
    }
    
    /**
     * Clear error history
     */
    suspend fun clearHistory() {
        historyMutex.withLock {
            errorHistory.clear()
        }
    }
}

/**
 * Error categories for different handling strategies
 */
enum class ErrorCategory {
    NETWORK,
    RATE_LIMIT,
    CAPTCHA_REQUIRED,
    ANNAS_ARCHIVE,
    DATA_FORMAT,
    AUTH,
    SERVER_ERROR,
    CLIENT_ERROR,
    UNKNOWN
}

/**
 * Base class for error events
 */
sealed class ErrorEvent(
    open val timestamp: Long = System.currentTimeMillis(),
    open val context: String
)

/**
 * Categorized error with handling information
 */
data class CategorizedError(
    val originalError: Exception,
    val category: ErrorCategory,
    override val context: String,
    val attemptsMade: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : ErrorEvent(timestamp, context), Exception(originalError.message, originalError) {
    
    val userFriendlyMessage: String
        get() = when (category) {
            ErrorCategory.NETWORK -> "Connection problem. Please check your internet connection."
            ErrorCategory.RATE_LIMIT -> "Too many requests. Please wait a moment before trying again."
            ErrorCategory.CAPTCHA_REQUIRED -> "Security verification required. Please complete the verification."
            ErrorCategory.ANNAS_ARCHIVE -> "Anna's Archive is temporarily unavailable. Please try again later."
            ErrorCategory.DATA_FORMAT -> "Invalid data received. This may be a temporary issue."
            ErrorCategory.AUTH -> "Authentication required. Please check your credentials."
            ErrorCategory.SERVER_ERROR -> "Server is experiencing issues. Please try again in a few minutes."
            ErrorCategory.CLIENT_ERROR -> "Invalid request. Please check your search parameters."
            ErrorCategory.UNKNOWN -> "An unexpected error occurred. Please try again."
        }
    
    val isRetryable: Boolean
        get() = when (category) {
            ErrorCategory.NETWORK, 
            ErrorCategory.RATE_LIMIT, 
            ErrorCategory.SERVER_ERROR -> true
            else -> false
        }
}

/**
 * Error that was successfully recovered from
 */
data class RecoveredError(
    val originalError: Exception,
    override val context: String,
    val attemptsToRecover: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : ErrorEvent(timestamp, context)

/**
 * Error statistics for monitoring
 */
data class ErrorStatistics(
    val totalErrors: Int,
    val errorsByCategory: Map<ErrorCategory, Int>,
    val recoveryRate: Float,
    val mostCommonError: ErrorCategory?
)