package com.clubs.clubquality

/**
 * Статистика клуба только для владельца (subject = place, anchor = club_id) — приватная панель
 * «Статистика» (§9 docs/modules/club-quality.md). Слой L1/L2: считается щедро, данные владельца
 * допустимы; анти-фарм защиты (distinct/absolutes/decay/min-K) относятся к скрытому рангу L3,
 * а НЕ сюда.
 *
 * Read-only агрегации по `transactions`, `membership_history`, `applications`, `event_responses`,
 * `events`, `skladchina_participants`, `skladchinas`, `memberships`, `clubs`. Ledger репутации
 * НЕ читается (правило §2): «споры по явке» считаются напрямую из `event_responses` (текущие
 * открытые споры), а не из ledger'а.
 *
 * Дизайн: docs/backlog/club-quality-gamification.md §11.3–§11.4 + final.html блок 3.
 */
data class ClubStats(
    val clubType: ClubType,
    // ---- Рычаги роста (growth levers) ----
    /** Доля продлений платного клуба за 30 дней (продления ÷ продления+отток), 0..100. Null для бесплатных клубов / нет данных. */
    val retentionPercent: Int?,
    val retentionTrend: Trend?,
    /** Участники, выбывшие (платные: ушли+истекли) / ушедшие (бесплатные) за последние 30 дней. */
    val churnedThisPeriod: Int,
    /** Участники, вернувшиеся (membership_history `rejoined`) за последние 30 дней. Отображается для бесплатных клубов. */
    val rejoinedThisPeriod: Int,
    /** Уникальные ответившие ÷ живые участники за 90 дней, 0..100 (то же определение, что и на карточке Discovery). */
    val engagementPercent: Int,
    val engagementTrend: Trend?,
    /** Доля оплативших среди урегулированных участников складчин за 90 дней, 0..100. Null, если закрытых складчин не было. */
    val skladchinaPaidPercent: Int?,
    val skladchinaPaidTrend: Trend?,
    /** Заявки, ожидающие решения. Null, если клуб не принимает заявки (не `closed`). */
    val pendingApplications: Int?,
    /** Подмножество [pendingApplications] старше 24ч (приближается к авто-отклонению за 48ч). Null, если не `closed`. */
    val stalePendingApplications: Int?,
    // ---- Зона внимания (owner-only «Надёжность организатора» negatives) ----
    /** Споры по явке, когда-либо поднятые против отметок клуба (кумулятивно — открытые + разрешённые). */
    val attendanceDisputes: Int,
    /** Все проведённые встречи за всё время — знаменатель-контекст для споров («N из M»). */
    val totalMeetings: Int,
    /** Заявки, авто-отклонённые за последние 90 дней. Null, если клуб не принимает заявки. */
    val autoRejectedApplications: Int?,
    /** События, отменённые за последние 90 дней. */
    val cancelledMeetings: Int,
)

enum class ClubType { paid, free }

enum class TrendDirection { up, down, flat }

/** Изменение процентной метрики окно-к-окну. [delta] — со знаком, в процентных пунктах. */
data class Trend(val direction: TrendDirection, val delta: Int)
