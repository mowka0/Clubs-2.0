package com.clubs.clubquality

import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipEvent
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import com.clubs.generated.jooq.tables.references.APPLICATIONS
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.CLUB_RANK
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIP_HISTORY
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import com.clubs.generated.jooq.tables.references.SKLADCHINA_PARTICIPANTS
import com.clubs.generated.jooq.tables.references.TRANSACTIONS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Чтение/запись L3-ранга (хранилище джобы пересчёта). Существующие общие таблицы читает напрямую
 * (прецедент: [JooqClubQualityRepository]); единственное, что НЕ читается напрямую, —
 * `reputation_ledger`: он приходит через [com.clubs.reputation.LedgerReadPort]. Репозиторий только
 * ФОРМИРУЕТ данные; весь скоринг — в [ClubRankPolicy]. Негативные сигналы читаются БЕЗ выборки
 * личности диспутёра (дизайн §4: dispute-identity остаётся внутренней). Окна биндятся как
 * параметры, вычисленные в Kotlin.
 */
@Repository
class JooqClubRankRepository(private val dsl: DSLContext) : ClubRankRepository {

    private companion object {
        /** Единственный источник истины для ключа advisory-lock пересчёта — опечатка здесь молча
         *  взяла бы другой лок и сломала бы сериализацию между инстансами. */
        const val RANK_LOCK_KEY = "club_rank"
    }

    private data class ClubMeta(
        val ownerId: UUID,
        val category: ClubCategory,
        val isPaid: Boolean,
        val isClosed: Boolean,
        val createdAt: OffsetDateTime,
    )

    override fun findRankSignals(now: OffsetDateTime): List<ClubRankSignals> {
        val hardCutoff = now.minusDays(ClubRankPolicy.HARD_CUTOFF_DAYS)
        val window90 = now.minusDays(ClubRankPolicy.DEMAND_WINDOW_DAYS)

        val meta = clubMeta()
        if (meta.isEmpty()) return emptyList()

        val core = coreByClub(hardCutoff)
        val payers = payersByClub(hardCutoff, onlyRenewal = false)
        val renewers = payersByClub(hardCutoff, onlyRenewal = true)
        val leftExpired = leftExpiredByClubUser(hardCutoff)
        val voters = votersByClub(window90)
        val qualityEvents = qualityEventsByClub(hardCutoff, now)
        val disputes = disputesByClub(hardCutoff)
        val ghosting = ghostingByClub(hardCutoff, now)
        val autoRejects = autoRejectsByClub(hardCutoff)
        val skladchinaGhosts = skladchinaGhostsByClub(hardCutoff)

        return meta.map { (clubId, m) ->
            val clubPayers = payers[clubId].orEmpty()
            ClubRankSignals(
                clubId = clubId,
                ownerId = m.ownerId,
                category = m.category.name,
                isPaid = m.isPaid,
                clubCreatedAt = m.createdAt,
                core = core[clubId].orEmpty(),
                payers = clubPayers,
                scamPayers = scamPayers(clubPayers, leftExpired[clubId].orEmpty()),
                renewers = renewers[clubId].orEmpty(),
                voters = voters[clubId].orEmpty(),
                qualityEvents = qualityEvents[clubId].orEmpty(),
                disputes = disputes[clubId].orEmpty(),
                ghosting = ghosting[clubId].orEmpty(),
                autoRejects = if (m.isClosed) autoRejects[clubId].orEmpty() else emptyList(),
                skladchinaGhosts = skladchinaGhosts[clubId].orEmpty(),
                churnEvents90d = leftExpired[clubId].orEmpty().values.flatten().count { it >= window90 },
            )
        }
    }

    private fun clubMeta(): Map<UUID, ClubMeta> =
        dsl.select(CLUBS.ID, CLUBS.OWNER_ID, CLUBS.CATEGORY, CLUBS.SUBSCRIPTION_PRICE, CLUBS.ACCESS_TYPE, CLUBS.CREATED_AT)
            .from(CLUBS)
            .where(CLUBS.IS_ACTIVE.isTrue)
            .fetch()
            .associate {
                it.value1()!! to ClubMeta(
                    ownerId = it.value2()!!,
                    category = it.value3()!!,
                    isPaid = (it.value4() ?: 0) > 0,
                    isClosed = it.value5() == AccessType.closed,
                    createdAt = it.value6()!!,
                )
            }

    /**
     * Квалифицирующая посещаемость ядра: участник проголосовал (stage-1) до события, attendance =
     * attended, событие не отменено, внутри hard cutoff, пользователь НЕ владелец. Distinct-события
     * + временной разброс в 7 дней применяются в Kotlin (один «пакетный» день не может
     * изобразить разнообразие).
     */
    private fun coreByClub(hardCutoff: OffsetDateTime): Map<UUID, List<AccountOutcome>> =
        dsl.select(EVENTS.CLUB_ID, EVENT_RESPONSES.USER_ID, EVENTS.EVENT_DATETIME)
            .from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended))
                    .and(EVENT_RESPONSES.STAGE_1_TIMESTAMP.isNotNull)
                    .and(EVENT_RESPONSES.STAGE_1_TIMESTAMP.lt(EVENTS.EVENT_DATETIME))
                    .and(EVENTS.EVENT_DATETIME.ge(hardCutoff))
                    .and(EVENT_RESPONSES.USER_ID.ne(CLUBS.OWNER_ID)),
            )
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! })
            .mapValues { (_, userTimes) -> qualifyCore(userTimes) }

    /** Из строк (userId, eventDatetime) одного клуба: distinct-пользователи с ≥CORE_MIN_EVENTS
     *  событиями с разбросом ≥MIN_EVENT_GAP_DAYS; якорь = последнее квалифицирующее событие. */
    private fun qualifyCore(userTimes: List<Pair<UUID, OffsetDateTime>>): List<AccountOutcome> =
        userTimes.groupBy({ it.first }, { it.second }).mapNotNull { (userId, times) ->
            if (times.size < ClubRankPolicy.CORE_MIN_EVENTS) return@mapNotNull null
            val sorted = times.sorted()
            val spreadDays = ChronoUnit.DAYS.between(sorted.first(), sorted.last())
            if (spreadDays < ClubRankPolicy.MIN_EVENT_GAP_DAYS) null else AccountOutcome(userId, sorted.last())
        }

    /**
     * Distinct платящие аккаунты по клубам. [onlyRenewal]=false → плательщики первого платежа
     * (`subscription`); =true → непересекающееся множество `renewal` (бонус за лояльность). Вместе они
     * разбивают популяцию платежей так, что платёжная ось никогда не считает продлившего дважды.
     * `charge_id NOT NULL` обеспечивает инвариант «Stars proof» — реальными деньгами считается только
     * подписанный Telegram платёж (у него всегда есть charge id).
     */
    private fun payersByClub(hardCutoff: OffsetDateTime, onlyRenewal: Boolean): Map<UUID, List<AccountOutcome>> {
        val type = if (onlyRenewal) TransactionType.renewal else TransactionType.subscription
        return dsl.select(TRANSACTIONS.CLUB_ID, TRANSACTIONS.USER_ID, DSL.max(TRANSACTIONS.CREATED_AT))
            .from(TRANSACTIONS)
            .join(CLUBS).on(CLUBS.ID.eq(TRANSACTIONS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(TRANSACTIONS.STATUS.eq(TransactionStatus.completed))
                    .and(TRANSACTIONS.TYPE.eq(type))
                    .and(TRANSACTIONS.TELEGRAM_PAYMENT_CHARGE_ID.isNotNull)
                    .and(TRANSACTIONS.CREATED_AT.ge(hardCutoff)),
            )
            .groupBy(TRANSACTIONS.CLUB_ID, TRANSACTIONS.USER_ID)
            .fetch()
            .groupBy({ it.value1()!! }, { AccountOutcome(it.value2()!!, it.value3()!!) })
    }

    /** События членства `left`/`expired` по клубам, с ключом пользователь → времена его событий.
     *  Питает и счётчик оттока за 90 дней (аномалия), и проверку scam-плательщиков. */
    private fun leftExpiredByClubUser(hardCutoff: OffsetDateTime): Map<UUID, Map<UUID, List<OffsetDateTime>>> =
        dsl.select(MEMBERSHIP_HISTORY.CLUB_ID, MEMBERSHIP_HISTORY.USER_ID, MEMBERSHIP_HISTORY.OCCURRED_AT)
            .from(MEMBERSHIP_HISTORY)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIP_HISTORY.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(MEMBERSHIP_HISTORY.EVENT.`in`(MembershipEvent.left, MembershipEvent.expired))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.ge(hardCutoff)),
            )
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! })
            .mapValues { (_, pairs) -> pairs.groupBy({ it.first }, { it.second }) }

    /** Плательщики, ушедшие в течение SCAM_LEFT_WINDOW_DAYS после последнего платежа («заплатил и пропал»). */
    private fun scamPayers(
        payers: List<AccountOutcome>,
        leftByUser: Map<UUID, List<OffsetDateTime>>,
    ): Set<UUID> = payers.filter { payer ->
        leftByUser[payer.userId].orEmpty().any { leftAt ->
            leftAt.isAfter(payer.occurredAt) &&
                ChronoUnit.DAYS.between(payer.occurredAt, leftAt) <= ClubRankPolicy.SCAM_LEFT_WINDOW_DAYS
        }
    }.map { it.userId }.toSet()

    /** Distinct участники со stage-1 голосом (member-driven, владелец исключён) по неотменённым событиям, 90 дней. */
    private fun votersByClub(window90: OffsetDateTime): Map<UUID, List<AccountOutcome>> =
        dsl.select(EVENTS.CLUB_ID, EVENT_RESPONSES.USER_ID, DSL.max(EVENT_RESPONSES.STAGE_1_TIMESTAMP))
            .from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENT_RESPONSES.STAGE_1_TIMESTAMP.isNotNull)
                    .and(EVENT_RESPONSES.STAGE_1_TIMESTAMP.ge(window90))
                    .and(EVENT_RESPONSES.USER_ID.ne(CLUBS.OWNER_ID)),
            )
            .groupBy(EVENTS.CLUB_ID, EVENT_RESPONSES.USER_ID)
            .fetch()
            .groupBy({ it.value1()!! }, { AccountOutcome(it.value2()!!, it.value3()!!) })

    /** События с ≥EVENT_MIN_ATTENDEES distinct квалифицированными attended (голос участника до события,
     *  владелец исключён) — единственная устойчивая к накрутке владельцем форма «attended» в LiveActivity. */
    private fun qualityEventsByClub(hardCutoff: OffsetDateTime, now: OffsetDateTime): Map<UUID, List<OffsetDateTime>> =
        dsl.select(EVENTS.CLUB_ID, EVENTS.EVENT_DATETIME)
            .from(EVENTS)
            .join(EVENT_RESPONSES).on(EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID))
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lt(now))
                    .and(EVENTS.EVENT_DATETIME.ge(hardCutoff))
                    .and(EVENTS.ATTENDANCE_FINALIZED.isTrue)
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended))
                    .and(EVENT_RESPONSES.STAGE_1_TIMESTAMP.isNotNull)
                    .and(EVENT_RESPONSES.STAGE_1_TIMESTAMP.lt(EVENTS.EVENT_DATETIME))
                    .and(EVENT_RESPONSES.USER_ID.ne(CLUBS.OWNER_ID)),
            )
            .groupBy(EVENTS.CLUB_ID, EVENTS.ID, EVENTS.EVENT_DATETIME)
            .having(DSL.countDistinct(EVENT_RESPONSES.USER_ID).ge(ClubRankPolicy.EVENT_MIN_ATTENDEES))
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })

    /** Времена диспутного поведения (даты событий), накопительные маркеры, личность НЕ выбирается. */
    private fun disputesByClub(hardCutoff: OffsetDateTime): Map<UUID, List<OffsetDateTime>> =
        dsl.select(EVENTS.CLUB_ID, EVENTS.EVENT_DATETIME)
            .from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.ge(hardCutoff))
                    .and(
                        EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed)
                            .or(EVENT_RESPONSES.DISPUTE_TERMINAL.isTrue)
                            .or(EVENT_RESPONSES.DISPUTE_NOTE.isNotNull),
                    ),
            )
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })

    /** Ghosting организатора: прошедшее неотменённое событие, посещаемость финализирована, но так и не отмечена. */
    private fun ghostingByClub(hardCutoff: OffsetDateTime, now: OffsetDateTime): Map<UUID, List<OffsetDateTime>> =
        dsl.select(EVENTS.CLUB_ID, EVENTS.EVENT_DATETIME)
            .from(EVENTS)
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lt(now))
                    .and(EVENTS.EVENT_DATETIME.ge(hardCutoff))
                    .and(EVENTS.ATTENDANCE_FINALIZED.isTrue)
                    .and(EVENTS.ATTENDANCE_MARKED.isFalse),
            )
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })

    private fun autoRejectsByClub(hardCutoff: OffsetDateTime): Map<UUID, List<OffsetDateTime>> =
        dsl.select(APPLICATIONS.CLUB_ID, APPLICATIONS.RESOLVED_AT)
            .from(APPLICATIONS)
            .join(CLUBS).on(CLUBS.ID.eq(APPLICATIONS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(APPLICATIONS.STATUS.eq(ApplicationStatus.auto_rejected))
                    .and(APPLICATIONS.RESOLVED_AT.isNotNull)
                    .and(APPLICATIONS.RESOLVED_AT.ge(hardCutoff)),
            )
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })

    private fun skladchinaGhostsByClub(hardCutoff: OffsetDateTime): Map<UUID, List<OffsetDateTime>> =
        dsl.select(SKLADCHINAS.CLUB_ID, SKLADCHINAS.CLOSED_AT)
            .from(SKLADCHINA_PARTICIPANTS)
            .join(SKLADCHINAS).on(SKLADCHINAS.ID.eq(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID))
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .where(
                CLUBS.IS_ACTIVE.isTrue
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.expired_no_response))
                    .and(SKLADCHINAS.CLOSED_AT.isNotNull)
                    .and(SKLADCHINAS.CLOSED_AT.ge(hardCutoff)),
            )
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })

    override fun findUserProfiles(userIds: Collection<UUID>): Map<UUID, UserProfile> {
        if (userIds.isEmpty()) return emptyMap()
        return dsl.select(USERS.ID, USERS.CREATED_AT, USERS.TELEGRAM_USERNAME, USERS.AVATAR_URL)
            .from(USERS)
            .where(USERS.ID.`in`(userIds.toSet()))
            .fetch()
            .associate {
                it.value1()!! to UserProfile(
                    userId = it.value1()!!,
                    createdAt = it.value2()!!,
                    hasUsername = !it.value3().isNullOrBlank(),
                    hasAvatar = !it.value4().isNullOrBlank(),
                )
            }
    }

    override fun upsertRanks(ranks: List<ClubRank>) {
        // Сериализуем конкурентные пересчёты (мульти-инстанс / scheduler vs ручной) — лок отпускается на commit.
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext(?))", RANK_LOCK_KEY)
        val now = OffsetDateTime.now()
        ranks.forEach { r ->
            val score = BigDecimal.valueOf(r.rankScore)
            val k = BigDecimal.valueOf(r.effectiveK)
            dsl.insertInto(CLUB_RANK)
                .set(CLUB_RANK.CLUB_ID, r.clubId)
                .set(CLUB_RANK.OWNER_ID, r.ownerId)
                .set(CLUB_RANK.CATEGORY, ClubCategory.valueOf(r.category))
                .set(CLUB_RANK.RANK_SCORE, score)
                .set(CLUB_RANK.IS_RANKED, r.isRanked)
                .set(CLUB_RANK.EFFECTIVE_K, k)
                .set(CLUB_RANK.COMPUTED_AT, now)
                .onConflict(CLUB_RANK.CLUB_ID)
                .doUpdate()
                .set(CLUB_RANK.OWNER_ID, r.ownerId)
                .set(CLUB_RANK.CATEGORY, ClubCategory.valueOf(r.category))
                .set(CLUB_RANK.RANK_SCORE, score)
                .set(CLUB_RANK.IS_RANKED, r.isRanked)
                .set(CLUB_RANK.EFFECTIVE_K, k)
                .set(CLUB_RANK.COMPUTED_AT, now)
                .execute()
        }
    }

    override fun findRankedClubs(): List<RankedClub> =
        dsl.select(CLUB_RANK.CLUB_ID, CLUB_RANK.OWNER_ID, CLUB_RANK.CATEGORY, CLUB_RANK.RANK_SCORE)
            .from(CLUB_RANK)
            .where(CLUB_RANK.IS_RANKED.isTrue)
            .fetch()
            .map {
                RankedClub(
                    clubId = it.value1()!!,
                    ownerId = it.value2()!!,
                    category = it.value3()!!.name,
                    rankScore = it.value4()!!.toDouble(),
                )
            }
}
