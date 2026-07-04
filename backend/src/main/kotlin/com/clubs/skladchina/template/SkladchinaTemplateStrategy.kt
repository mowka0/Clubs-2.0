package com.clubs.skladchina.template

import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.CreateSkladchinaRequest
import java.util.UUID

/**
 * Политика для конкретного шаблона (Strategy). Движок (SkladchinaService) владеет жизненным
 * циклом — проверка клуба/владельца, границы дедлайна, гейты репутации, персистентность, DM —
 * и делегирует специфичные для шаблона решения сюда. Новый шаблон = новый @Component,
 * реализующий этот интерфейс; без правок движка (Open/Closed). Интерфейс растёт ТОЛЬКО когда
 * этого требует шаблон (v1 продиктован split_bill).
 *
 * См. docs/backlog/skladchina-templates-architecture.md.
 */
interface SkladchinaTemplateStrategy {
    /** Шаблон, который обслуживает эта стратегия; реестр ключуется по нему. */
    val type: SkladchinaTemplate

    /**
     * Как участник может отказаться. FREE = мгновенный, бесплатный `decline` (custom/voluntary —
     * отказ здесь желаемое поведение). REQUIRES_APPROVAL = обоснованный запрос, который организатор
     * одобряет/отклоняет (split_bill — благо уже потреблено, поэтому бесплатный отказ был бы
     * безбилетничеством). V28.
     */
    val declinePolicy: DeclinePolicy

    /**
     * Верифицированы ли исходы репутации от этого шаблона организатором/рельсой (в противовес
     * honor-system самозаявлению). Мостик к будущему выводу «финансовой ответственности»: только
     * верифицированные исходы достаточно надёжны, чтобы их оценивать. (Ещё не персистится — ни один
     * шаблон не эмитит строки ledger с этим полем до переработки репутации; хранится здесь, чтобы
     * контракт был явным начиная с шаблона №1.)
     */
    val outcomesVerified: Boolean

    /**
     * Валидирует запрос создания для этого шаблона и определяет режим оплаты, общую цель,
     * участников (userId → ожидаемая доля) и опциональное исходное событие. Бросает
     * ValidationException (400) / ForbiddenException (403) / NotFoundException (404) при невалидном вводе.
     */
    fun resolveCreation(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest): TemplateResolution
}

/** Как работает отказ для шаблона (V28). */
enum class DeclinePolicy {
    /** Мгновенный, бесплатный отказ, контролируемый участником. */
    FREE,
    /** Обоснованный запрос, который организатор одобряет (→ declined) или отклоняет (→ нужно платить). */
    REQUIRES_APPROVAL,
}

/** Во что шаблон превращает запрос создания; движок персистит это как есть. */
data class TemplateResolution(
    val mode: SkladchinaMode,
    val totalGoalKopecks: Long?,
    val participants: List<Pair<UUID, Long?>>,   // (userId, expectedAmountKopecks)
    val eventId: UUID? = null,
)
