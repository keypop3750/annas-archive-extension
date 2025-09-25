package eu.kanade.tachiyomi.extension.all.annasarchive.selection

import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookConcept
import eu.kanade.tachiyomi.extension.all.annasarchive.model.BookSource
import eu.kanade.tachiyomi.extension.all.annasarchive.model.DownloadMirror
import eu.kanade.tachiyomi.extension.all.annasarchive.model.MirrorType
import java.util.Locale

/**
 * Phase 3: Source Selection Engine
 * Implements smart source grouping, recommendation, and comparison logic
 */
class SourceSelectionEngine {
    
    companion object {
        // Format priority for recommendations (higher = better)
        private val FORMAT_PRIORITY = mapOf(
            "epub" to 10,
            "pdf" to 9,
            "mobi" to 8,
            "azw3" to 7,
            "fb2" to 6,
            "txt" to 5,
            "doc" to 4,
            "docx" to 3,
            "djvu" to 2,
            "unknown" to 1
        )
        
        // File size quality thresholds
        private val QUALITY_THRESHOLDS = mapOf(
            "epub" to 5_000_000L,  // 5MB for good EPUB
            "pdf" to 25_000_000L,  // 25MB for good PDF
            "mobi" to 8_000_000L,  // 8MB for good MOBI
            "azw3" to 8_000_000L   // 8MB for good AZW3
        )
        
        // Mirror type reliability base scores
        private val MIRROR_TYPE_SCORES = mapOf(
            MirrorType.DIRECT to 0.95f,
            MirrorType.SLOW_DOWNLOAD to 0.85f,
            MirrorType.IPFS to 0.80f,
            MirrorType.PARTNER to 0.75f
        )
    }
    
    /**
     * Generate complete source selection data for a book concept
     */
    fun generateSourceSelectionData(
        bookConcept: BookConcept,
        userPreferences: UserPreferences = UserPreferences(),
        networkCondition: NetworkCondition = NetworkCondition.WIFI_FAST
    ): SourceSelectionData {
        
        // Group sources by format
        val formatGroups = createFormatGroups(bookConcept.sources)
        
        // Find overall best source
        val recommendedSource = findBestSource(bookConcept.sources, userPreferences, networkCondition)
        
        // Generate selection metadata
        val selectionMetadata = generateSelectionMetadata(bookConcept, userPreferences, networkCondition)
        
        return SourceSelectionData(
            bookConcept = bookConcept,
            formatGroups = formatGroups,
            recommendedSource = recommendedSource,
            totalSources = bookConcept.sources.size,
            availableFormats = bookConcept.sources.map { it.format.uppercase() }.distinct().sorted(),
            selectionMetadata = selectionMetadata
        )
    }
    
    /**
     * Create format groups with recommendations
     */
    private fun createFormatGroups(sources: List<BookSource>): List<FormatGroup> {
        return sources.groupBy { it.format.lowercase() }
            .map { (format, formatSources) ->
                val recommended = formatSources.maxByOrNull { calculateSourceScore(it) }
                val totalMirrors = formatSources.sumOf { it.downloadMirrors.size }
                val averageReliability = formatSources.map { it.reliability }.average().toFloat()
                val formatPriority = FORMAT_PRIORITY[format] ?: 0
                
                FormatGroup(
                    format = format.uppercase(),
                    sources = formatSources.sortedByDescending { calculateSourceScore(it) },
                    recommended = recommended,
                    totalMirrors = totalMirrors,
                    averageReliability = averageReliability,
                    formatPriority = formatPriority
                )
            }
            .sortedByDescending { it.formatPriority }
    }
    
    /**
     * Find the best source across all formats
     */
    private fun findBestSource(
        sources: List<BookSource>, 
        preferences: UserPreferences,
        networkCondition: NetworkCondition
    ): BookSource? {
        return sources.maxByOrNull { source ->
            calculateSourceScore(source, preferences, networkCondition)
        }
    }
    
    /**
     * Calculate comprehensive source score for ranking
     */
    private fun calculateSourceScore(
        source: BookSource,
        preferences: UserPreferences = UserPreferences(),
        networkCondition: NetworkCondition = NetworkCondition.WIFI_FAST
    ): Float {
        var score = 0f
        
        // Base reliability score (40% weight)
        score += source.reliability * 0.4f
        
        // Format preference score (25% weight)
        val formatPreferenceScore = when {
            preferences.preferredFormats.contains(source.format.lowercase()) -> 1.0f
            FORMAT_PRIORITY[source.format.lowercase()] != null -> 
                FORMAT_PRIORITY[source.format.lowercase()]!! / 10.0f
            else -> 0.3f
        }
        score += formatPreferenceScore * 0.25f
        
        // File size optimization (15% weight)
        val fileSizeScore = calculateFileSizeScore(source, networkCondition)
        score += fileSizeScore * 0.15f
        
        // Mirror count and diversity (10% weight)
        val mirrorScore = (source.downloadMirrors.size.coerceAtMost(5) / 5.0f) *
                (source.downloadMirrors.map { it.type }.distinct().size / 4.0f)
        score += mirrorScore * 0.1f
        
        // Quality indicators (10% weight)
        val qualityScore = calculateQualityScore(source)
        score += qualityScore * 0.1f
        
        return score.coerceIn(0f, 1f)
    }
    
    /**
     * Calculate file size score based on network condition
     */
    private fun calculateFileSizeScore(source: BookSource, networkCondition: NetworkCondition): Float {
        val fileSizeBytes = parseFileSize(source.fileSize)
        if (fileSizeBytes == null) return 0.5f // Unknown size gets neutral score
        
        val formatThreshold = QUALITY_THRESHOLDS[source.format.lowercase()] ?: 10_000_000L
        
        return when (networkCondition) {
            NetworkCondition.WIFI_FAST -> {
                // On fast WiFi, prefer good quality (larger files up to threshold)
                if (fileSizeBytes > formatThreshold) 0.8f else (fileSizeBytes / formatThreshold.toFloat())
            }
            NetworkCondition.WIFI_SLOW, NetworkCondition.MOBILE_FAST -> {
                // On slower connections, balance size vs quality
                val sizeRatio = fileSizeBytes / formatThreshold.toFloat()
                when {
                    sizeRatio > 2.0f -> 0.3f // Too large
                    sizeRatio > 1.0f -> 0.8f // Good size
                    sizeRatio > 0.5f -> 1.0f // Optimal size
                    else -> 0.6f // Might be too small
                }
            }
            NetworkCondition.MOBILE_SLOW -> {
                // On slow mobile, strongly prefer smaller files
                val sizeMB = fileSizeBytes / (1024f * 1024f)
                when {
                    sizeMB > 50f -> 0.2f
                    sizeMB > 20f -> 0.5f
                    sizeMB > 10f -> 0.8f
                    sizeMB > 2f -> 1.0f
                    else -> 0.7f
                }
            }
            NetworkCondition.OFFLINE -> 0f // Can't download anyway
        }
    }
    
    /**
     * Calculate quality score based on various indicators
     */
    private fun calculateQualityScore(source: BookSource): Float {
        var score = 0.5f // Base score
        
        // Quality field bonus
        when (source.quality?.lowercase()) {
            "hd", "high", "good" -> score += 0.3f
            "standard", "medium" -> score += 0.1f
            "low", "poor" -> score -= 0.2f
        }
        
        // Mirror diversity bonus
        val mirrorTypes = source.downloadMirrors.map { it.type }.distinct()
        score += mirrorTypes.size * 0.05f
        
        // Size reasonableness check
        val fileSize = parseFileSize(source.fileSize)
        if (fileSize != null) {
            val threshold = QUALITY_THRESHOLDS[source.format.lowercase()]
            if (threshold != null) {
                val ratio = fileSize / threshold.toFloat()
                if (ratio in 0.5f..2.0f) score += 0.1f // Reasonable size
                else if (ratio < 0.1f) score -= 0.3f // Suspiciously small
            }
        }
        
        return score.coerceIn(0f, 1f)
    }
    
    /**
     * Generate selection metadata with recommendations
     */
    private fun generateSelectionMetadata(
        bookConcept: BookConcept,
        preferences: UserPreferences,
        networkCondition: NetworkCondition
    ): SelectionMetadata {
        val recommendations = generateRecommendations(bookConcept, preferences, networkCondition)
        
        return SelectionMetadata(
            preferredFormats = preferences.preferredFormats,
            deviceCompatibility = generateDeviceCompatibility(bookConcept.sources),
            networkCondition = networkCondition,
            storageAvailable = null, // Would be provided by Yokai app
            recommendations = recommendations
        )
    }
    
    /**
     * Generate smart recommendations
     */
    private fun generateRecommendations(
        bookConcept: BookConcept,
        preferences: UserPreferences,
        networkCondition: NetworkCondition
    ): List<SelectionReason> {
        val recommendations = mutableListOf<SelectionReason>()
        
        // Find best overall source
        val bestSource = findBestSource(bookConcept.sources, preferences, networkCondition)
        bestSource?.let { source ->
            recommendations.add(
                SelectionReason(
                    type = ReasonType.BEST_QUALITY,
                    message = "Best overall choice: ${source.format.uppercase()} with ${(source.reliability * 100).toInt()}% reliability",
                    weight = 1.0f
                )
            )
        }
        
        // Find smallest file if on mobile
        if (networkCondition in listOf(NetworkCondition.MOBILE_SLOW, NetworkCondition.MOBILE_FAST)) {
            val smallestSource = bookConcept.sources.minByOrNull { parseFileSize(it.fileSize) ?: Long.MAX_VALUE }
            smallestSource?.let { source ->
                recommendations.add(
                    SelectionReason(
                        type = ReasonType.SMALLEST_SIZE,
                        message = "Smallest file: ${source.format.uppercase()} (${source.fileSize})",
                        weight = 0.8f
                    )
                )
            }
        }
        
        // Find most reliable
        val mostReliable = bookConcept.sources.maxByOrNull { it.reliability }
        mostReliable?.let { source ->
            if (source.reliability > 0.9f) {
                recommendations.add(
                    SelectionReason(
                        type = ReasonType.MOST_RELIABLE,
                        message = "Most reliable: ${source.format.uppercase()} with ${(source.reliability * 100).toInt()}% success rate",
                        weight = 0.9f
                    )
                )
            }
        }
        
        // Format compatibility recommendations
        preferences.preferredFormats.forEach { preferredFormat ->
            val matchingSources = bookConcept.sources.filter { it.format.lowercase() == preferredFormat }
            if (matchingSources.isNotEmpty()) {
                val bestMatch = matchingSources.maxByOrNull { it.reliability }
                bestMatch?.let { source ->
                    recommendations.add(
                        SelectionReason(
                            type = ReasonType.FORMAT_COMPATIBILITY,
                            message = "Your preferred format: ${source.format.uppercase()}",
                            weight = 0.7f
                        )
                    )
                }
            }
        }
        
        return recommendations.sortedByDescending { it.weight }
    }
    
    /**
     * Generate device compatibility information
     */
    private fun generateDeviceCompatibility(sources: List<BookSource>): Map<String, Boolean> {
        return sources.map { it.format.lowercase() }.distinct().associateWith { format ->
            when (format) {
                "epub", "pdf", "txt" -> true // Universally supported
                "mobi", "azw3" -> true // Kindle and most readers
                "fb2" -> true // Most readers support this
                "djvu" -> false // Limited support
                "doc", "docx" -> false // Not ideal for reading
                else -> false
            }
        }
    }
    
    /**
     * Assess individual mirrors
     */
    fun assessMirrors(source: BookSource): List<MirrorAssessment> {
        return source.downloadMirrors.map { mirror ->
            val reliabilityScore = calculateMirrorReliability(mirror)
            val qualityIndicators = generateQualityIndicators(mirror)
            val riskFactors = generateRiskFactors(mirror)
            
            MirrorAssessment(
                mirror = mirror,
                reliabilityScore = reliabilityScore,
                speedEstimate = estimateSpeed(mirror),
                qualityIndicators = qualityIndicators,
                riskFactors = riskFactors
            )
        }
    }
    
    /**
     * Calculate mirror reliability score
     */
    private fun calculateMirrorReliability(mirror: DownloadMirror): Float {
        var score = MIRROR_TYPE_SCORES[mirror.type] ?: 0.5f
        
        // Adjust based on domain reputation
        when {
            mirror.domain.contains("libgen") -> score += 0.1f
            mirror.domain.contains("archive.org") -> score += 0.15f
            mirror.domain.contains("ipfs") -> score += 0.05f
            mirror.requiresCaptcha -> score -= 0.1f
        }
        
        return score.coerceIn(0f, 1f)
    }
    
    /**
     * Generate quality indicators for a mirror
     */
    private fun generateQualityIndicators(mirror: DownloadMirror): List<QualityIndicator> {
        val indicators = mutableListOf<QualityIndicator>()
        
        when (mirror.type) {
            MirrorType.DIRECT -> indicators.add(
                QualityIndicator(IndicatorType.FAST_DOWNLOAD, "Direct download - typically fastest", true)
            )
            MirrorType.IPFS -> indicators.add(
                QualityIndicator(IndicatorType.MULTIPLE_SOURCES, "IPFS - distributed and reliable", true)
            )
            else -> {}
        }
        
        if (!mirror.requiresCaptcha) {
            indicators.add(
                QualityIndicator(IndicatorType.FAST_DOWNLOAD, "No CAPTCHA required", true)
            )
        }
        
        return indicators
    }
    
    /**
     * Generate risk factors for a mirror
     */
    private fun generateRiskFactors(mirror: DownloadMirror): List<RiskFactor> {
        val risks = mutableListOf<RiskFactor>()
        
        if (mirror.requiresCaptcha) {
            risks.add(
                RiskFactor(RiskType.REQUIRES_CAPTCHA, "May require solving CAPTCHA", Severity.MEDIUM)
            )
        }
        
        if (mirror.type == MirrorType.PARTNER) {
            risks.add(
                RiskFactor(RiskType.UNTESTED_MIRROR, "External partner site", Severity.LOW)
            )
        }
        
        return risks
    }
    
    /**
     * Estimate download speed category
     */
    private fun estimateSpeed(mirror: DownloadMirror): String {
        return when (mirror.type) {
            MirrorType.DIRECT -> "Fast"
            MirrorType.SLOW_DOWNLOAD -> "Medium"
            MirrorType.IPFS -> "Medium"
            MirrorType.PARTNER -> "Variable"
        }
    }
    
    /**
     * Compare multiple sources side by side
     */
    fun compareSources(sources: List<BookSource>): SourceComparison {
        val comparisonMetrics = generateComparisonMetrics(sources)
        val recommendation = generateComparisonRecommendation(sources, comparisonMetrics)
        
        return SourceComparison(
            sources = sources,
            comparisonMetrics = comparisonMetrics,
            recommendation = recommendation
        )
    }
    
    /**
     * Generate comparison metrics
     */
    private fun generateComparisonMetrics(sources: List<BookSource>): List<ComparisonMetric> {
        return listOf(
            // File size comparison
            ComparisonMetric(
                name = "File Size",
                values = sources.associate { it.md5 to (it.fileSize ?: "Unknown") },
                bestMd5 = sources.minByOrNull { parseFileSize(it.fileSize) ?: Long.MAX_VALUE }?.md5
            ),
            // Reliability comparison
            ComparisonMetric(
                name = "Reliability",
                values = sources.associate { it.md5 to "${(it.reliability * 100).toInt()}%" },
                bestMd5 = sources.maxByOrNull { it.reliability }?.md5
            ),
            // Mirror count comparison
            ComparisonMetric(
                name = "Mirrors",
                values = sources.associate { it.md5 to "${it.downloadMirrors.size} mirrors" },
                bestMd5 = sources.maxByOrNull { it.downloadMirrors.size }?.md5
            ),
            // Quality comparison
            ComparisonMetric(
                name = "Quality",
                values = sources.associate { it.md5 to (it.quality ?: "Standard") },
                bestMd5 = sources.find { it.quality?.lowercase() in listOf("hd", "high", "good") }?.md5
            )
        )
    }
    
    /**
     * Generate comparison recommendation
     */
    private fun generateComparisonRecommendation(
        sources: List<BookSource>,
        metrics: List<ComparisonMetric>
    ): ComparisonRecommendation {
        val winner = sources.maxByOrNull { calculateSourceScore(it) }!!
        
        val reasons = mutableListOf<String>()
        metrics.forEach { metric ->
            if (metric.bestMd5 == winner.md5) {
                reasons.add("Best ${metric.name.lowercase()}")
            }
        }
        
        val alternatives = sources.filter { it.md5 != winner.md5 }
            .map { source ->
                val advantages = metrics.filter { it.bestMd5 == source.md5 }
                    .map { "best ${it.name.lowercase()}" }
                
                AlternativeOption(
                    md5 = source.md5,
                    reason = if (advantages.isNotEmpty()) "If you prefer ${advantages.joinToString(" and ")}" 
                            else "Alternative ${source.format.uppercase()} option",
                    tradeoffs = generateTradeoffs(winner, source, metrics)
                )
            }
        
        return ComparisonRecommendation(
            winnerMd5 = winner.md5,
            reasons = reasons,
            alternativeOptions = alternatives
        )
    }
    
    /**
     * Generate tradeoffs between sources
     */
    private fun generateTradeoffs(winner: BookSource, alternative: BookSource, metrics: List<ComparisonMetric>): List<String> {
        val tradeoffs = mutableListOf<String>()
        
        // Compare reliability
        if (winner.reliability > alternative.reliability) {
            val diff = ((winner.reliability - alternative.reliability) * 100).toInt()
            tradeoffs.add("${diff}% lower reliability")
        }
        
        // Compare file sizes
        val winnerSize = parseFileSize(winner.fileSize)
        val altSize = parseFileSize(alternative.fileSize)
        if (winnerSize != null && altSize != null) {
            when {
                altSize > winnerSize * 1.5 -> tradeoffs.add("Larger file size")
                altSize < winnerSize * 0.5 -> tradeoffs.add("Smaller file size (may affect quality)")
            }
        }
        
        // Compare mirror counts
        if (winner.downloadMirrors.size > alternative.downloadMirrors.size) {
            tradeoffs.add("Fewer download mirrors")
        }
        
        return tradeoffs
    }
    
    /**
     * Parse file size string to bytes
     */
    private fun parseFileSize(fileSizeStr: String?): Long? {
        if (fileSizeStr.isNullOrBlank()) return null
        
        return try {
            val regex = Regex("([0-9.]+)\\s*(GB|MB|KB|B)", RegexOption.IGNORE_CASE)
            val match = regex.find(fileSizeStr) ?: return null
            
            val size = match.groupValues[1].toFloat()
            val unit = match.groupValues[2].uppercase(Locale.getDefault())
            
            when (unit) {
                "GB" -> (size * 1024 * 1024 * 1024).toLong()
                "MB" -> (size * 1024 * 1024).toLong()
                "KB" -> (size * 1024).toLong()
                "B" -> size.toLong()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * User preferences for source selection
 */
data class UserPreferences(
    val preferredFormats: List<String> = listOf("epub", "pdf"),
    val maxFileSize: Long? = null,
    val prioritizeSpeed: Boolean = false,
    val allowCaptcha: Boolean = true
)