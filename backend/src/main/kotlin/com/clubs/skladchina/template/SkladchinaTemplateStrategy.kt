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

/** Во что шаблон превращает запрос создания; движок персистит это как есть. */
data class TemplateResolution(
    val mode: SkladchinaMode,
    val totalGoalKopecks: Long?,
    val participants: List<Pair<UUID, Long?>>,   // (userId, expectedAmountKopecks)
    val eventId: UUID? = null,
    // Часть суммы, которую организатор закрыл своими деньгами ещё до сбора (split_bill + excludeSelf).
    // Движок сразу помечает его оплатившим на эту сумму; null = обычный сбор, все стартуют pending.
    val prepaidByCreatorKopecks: Long? = null,
)
