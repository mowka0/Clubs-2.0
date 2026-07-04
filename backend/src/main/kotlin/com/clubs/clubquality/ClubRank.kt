package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Вклад одного достоверного аккаунта в distinct-ось: пользователь и время поведения, к которому
 * привязан recency-decay (последнее качественное событие / завершённый платёж / голос). Только
 * абсолюты — считаем distinct-аккаунты, никогда сырые события или доли (анти-фарм §3.4).
 */
data class AccountOutcome(val userId: UUID, val occurredAt: OffsetDateTime)

/**
 * Пер-аккаунтные входы веса достоверности (credibility) L3, читаются один раз на пересчёт (не на
 * каждый клуб). Все четыре входа owner-устойчивы: возраст аккаунта, полнота профиля и кросс-OWNER
 * footprint по ledger (distinct независимых owner'ов, у которых аккаунт заработал сдержанное
 * обещание — Sybil-налог, делающий марионетку дорогой). [footprintByOwner] — отображение
 * `ownerId → distinct клубов с kept-исходом`.
 */
data class CredibilityInput(
    val userId: UUID,
    val createdAt: OffsetDateTime,
    val hasUsername: Boolean,
    val hasAvatar: Boolean,
    val footprintByOwner: Map<UUID, Int>,
)

/**
 * Сырые пер-клубные сигналы, которые репозиторий собирает по одному клубу. В счёт их превращает
 * политика; репозиторий скорингом НЕ занимается. Каждый distinct-account список уже отфильтрован на
 * уровне запроса как member-driven и owner-excluded (например, [core] требует голос УЧАСТНИКА на
 * Этапе 1 до события, так что одна лишь owner-отметка посещаемости аккаунт не квалифицирует).
 * [category] — имя enum-значения (хранится простым String, чтобы политика оставалась без jOOQ
 * и юнит-тестируемой).
 */
data class ClubRankSignals(
    val clubId: UUID,
    val ownerId: UUID,
    val category: String,
    val isPaid: Boolean,
    val clubCreatedAt: OffsetDateTime,
    /** Distinct-ядро: ≥2 явки на разных событиях, голос участника до события, разброс ≥7 дней (§3.1). */
    val core: List<AccountOutcome>,
    /** Distinct Stars-плательщики из `transactions.completed` (§3.2). Для бесплатных клубов пуст. */
    val payers: List<AccountOutcome>,
    /** Плательщики, ушедшие ≤14 дней после оплаты — сигнатура скама, их вклад ополовинивается (§3.2). */
    val scamPayers: Set<UUID>,
    /** Distinct-аккаунты с продлением (`transactions.renewal`) — абсолют, НЕ доля (§3.2). */
    val renewers: List<AccountOutcome>,
    /** Distinct участники, голосовавшие на Этапе 1 по неотменённым событиям, окно 90 дней (§3.3). */
    val voters: List<AccountOutcome>,
    /** Времена поведения событий с ≥4 distinct квалифицированными явками — объём LiveActivity (§3.4). */
    val qualityEvents: List<OffsetDateTime>,
    /** Времена поведения споров (даты событий), личность спорящего НЕ читается (§4). */
    val disputes: List<OffsetDateTime>,
    /** Времена поведения организаторского ghosting (finalized ∧ ¬marked) (§4). */
    val ghosting: List<OffsetDateTime>,
    /** Времена resolved авто-отклонённых заявок (только закрытые клубы) (§4). */
    val autoRejects: List<OffsetDateTime>,
    /** Времена закрытия складчин с исходом `expired_no_response` (§4). */
    val skladchinaGhosts: List<OffsetDateTime>,
    /** События членства `left`+`expired` за окно 90 дней — вход детектора аномалии «слишком чисто» (§4). */
    val churnEvents90d: Int,
)

/**
 * Сохранённый L3-результат по одному клубу. INTERNAL — [rankScore]/[effectiveK] никогда не покидают
 * сервер. Единственная внешне видимая производная — булев бейдж «★ Топ-5 в категории».
 */
data class ClubRank(
    val clubId: UUID,
    val ownerId: UUID,
    val category: String,
    val rankScore: Double,
    val isRanked: Boolean,
    val effectiveK: Double,
)

/** Ranked-клуб глазами категорийного лидерборда — вход вычисления бейджа «★ Топ-5». */
data class RankedClub(
    val clubId: UUID,
    val ownerId: UUID,
    val category: String,
    val rankScore: Double,
)
