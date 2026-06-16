package com.clubs.skladchina.template

import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.CreateSkladchinaRequest
import java.util.UUID

/**
 * Per-template policy (Strategy). The engine (SkladchinaService) owns the lifecycle — club/owner
 * check, deadline bounds, reputation gates, persistence, DM — and delegates the template-specific
 * decisions here. New template = new @Component implementing this; no edits to the engine
 * (Open/Closed). The interface grows ONLY as a template needs it (split_bill drives v1).
 *
 * See docs/backlog/skladchina-templates-architecture.md.
 */
interface SkladchinaTemplateStrategy {
    /** The template this strategy serves; the registry keys on it. */
    val type: SkladchinaTemplate

    /**
     * Whether reputation outcomes from this template are organizer/rail-VERIFIED (vs honor-system
     * self-declared). Bridges to the future "financial responsibility" derivation: only verified
     * outcomes are trustworthy enough to score. (Not yet persisted — no template emits ledger rows
     * with it until reputation rework; kept here so the contract is explicit from template #1.)
     */
    val outcomesVerified: Boolean

    /**
     * Validate the create request for this template and resolve the payment mode, total goal,
     * participants (userId → expected share), and optional source event. Throws ValidationException
     * (400) / ForbiddenException (403) / NotFoundException (404) on invalid input.
     */
    fun resolveCreation(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest): TemplateResolution
}

/** What a template resolves a create request into; the engine persists this verbatim. */
data class TemplateResolution(
    val mode: SkladchinaMode,
    val totalGoalKopecks: Long?,
    val participants: List<Pair<UUID, Long?>>,   // (userId, expectedAmountKopecks)
    val eventId: UUID? = null,
)
