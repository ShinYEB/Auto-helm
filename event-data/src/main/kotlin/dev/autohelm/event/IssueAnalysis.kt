package dev.autohelm.event

import java.time.Instant

enum class IssueStatus { SCORED, NOT_AVAILABLE, ANALYSIS_FAILED }
enum class SearchStatus { SEARCH_PENDING, VERIFIED, SEARCH_FAILED, NOT_AVAILABLE }

data class IssueAnalysis(
    val analysisId: String,
    val environment: String,
    val eventId: String,
    val symbol: String,
    val analysisVersion: String,
    val status: IssueStatus,
    val relevance: Double?,
    val impact: Double?,
    val positiveProbability: Double?,
    val evidenceSufficient: Double?,
    val signedScore: Double?,
    val model: String?,
    val inputScope: String,
    val basis: String,
    val completedAt: Instant,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val attempt: Int = 1,
    val error: String? = null,
)

data class IssueSearchResult(
    val searchId: String,
    val environment: String,
    val eventId: String,
    val symbol: String,
    val policyVersion: String,
    val status: SearchStatus,
    val originalScore: Double,
    val verifiedScore: Double?,
    val sourceUrls: List<String>,
    val summary: String?,
    val model: String,
    val completedAt: Instant,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val error: String? = null,
    val attempt: Int = 1,
)
