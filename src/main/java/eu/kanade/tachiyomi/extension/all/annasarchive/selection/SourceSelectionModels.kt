package eu.kanade.tachiyomi.extension.all.annasarchive.selection

import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookConcept
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookSource
import eu.kanade.tachiyomi.extension.all.annasarchive.model.DownloadMirror
import eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType

/**
 * Data models for Phase 3: Source Selection UI
 * These provide structured data for Yokai's source selection interface
 */

/**
 * Grouped sources by format for source selection UI
 */
data class FormatGroup(
    val format: String, // "PDF", "EPUB", "MOBI", etc.
    val sources: List<BookSource>,
    val recommended: BookSource?, // Best source in this format
    val totalMirrors: Int,
    val averageReliability: Float,
    val formatPriority: Int // For sorting groups
)

/**
 * Complete source selection data for a book concept
 */
data class SourceSelectionData(
    val bookConcept: BookConcept,
    val formatGroups: List<FormatGroup>,
    val recommendedSource: BookSource?, // Overall best source
    val totalSources: Int,
    val availableFormats: List<String>,
    val selectionMetadata: SelectionMetadata
)

/**
 * Metadata for smart selection recommendations
 */
data class SelectionMetadata(
    val preferredFormats: List<String>, // User's format preferences
    val deviceCompatibility: Map<String, Boolean>, // Format compatibility
    val networkCondition: NetworkCondition,
    val storageAvailable: Long?,
    val recommendations: List<SelectionReason>
)

/**
 * Reasons for source recommendations
 */
data class SelectionReason(
    val type: ReasonType,
    val message: String,
    val weight: Float // Importance weight
)

enum class ReasonType {
    BEST_QUALITY,
    SMALLEST_SIZE,
    MOST_RELIABLE,
    FORMAT_COMPATIBILITY,
    NETWORK_OPTIMIZED
}

enum class NetworkCondition {
    WIFI_FAST,
    WIFI_SLOW,
    MOBILE_FAST,
    MOBILE_SLOW,
    OFFLINE
}

/**
 * Mirror quality assessment
 */
data class MirrorAssessment(
    val mirror: DownloadMirror,
    val reliabilityScore: Float, // 0.0-1.0
    val speedEstimate: String?, // "Fast", "Medium", "Slow"
    val qualityIndicators: List<QualityIndicator>,
    val riskFactors: List<RiskFactor>
)

data class QualityIndicator(
    val type: IndicatorType,
    val description: String,
    val positive: Boolean
)

data class RiskFactor(
    val type: RiskType,
    val description: String,
    val severity: Severity
)

enum class IndicatorType {
    HIGH_SUCCESS_RATE,
    FAST_DOWNLOAD,
    RECENT_SUCCESS,
    MULTIPLE_SOURCES,
    VERIFIED_CONTENT
}

enum class RiskType {
    REQUIRES_CAPTCHA,
    SLOW_RESPONSE,
    FREQUENT_FAILURES,
    UNTESTED_MIRROR,
    SUSPICIOUS_DOMAIN
}

enum class Severity {
    LOW, MEDIUM, HIGH
}

/**
 * Source comparison data for side-by-side analysis
 */
data class SourceComparison(
    val sources: List<BookSource>,
    val comparisonMetrics: List<ComparisonMetric>,
    val recommendation: ComparisonRecommendation
)

data class ComparisonMetric(
    val name: String, // "File Size", "Reliability", "Mirrors", etc.
    val values: Map<String, String>, // MD5 -> value
    val bestMd5: String? // Which source is best for this metric
)

data class ComparisonRecommendation(
    val winnerMd5: String,
    val reasons: List<String>,
    val alternativeOptions: List<AlternativeOption>
)

data class AlternativeOption(
    val md5: String,
    val reason: String, // "If you prefer smaller file size", "If you need EPUB format"
    val tradeoffs: List<String>
)