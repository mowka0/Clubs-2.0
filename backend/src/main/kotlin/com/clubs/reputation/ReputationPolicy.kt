package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.ReputationKind
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.Stage_1Vote

/**
 * Чистое отображение исхода поведения в kind + очки `reputation_ledger`.
 * Единственный источник истины для таблицы посещаемости из PRD §4.4.4 и
 * финансовых дельт складчины. См. docs/modules/reputation-v2.md.
 *
 * Очки привязаны только к обязательству этапа 2: подтверждённая бронь,
 * которую посетили/пропустили, оценивается одинаково независимо от голоса
 * этапа 1. Голос этапа 1 всё же определяет kind (ironclad vs spontaneous,
 * no_show vs spectator) — kind питает отображаемые признаки вроде
 * spontaneity_count, но не влияет на очки.
 */
object ReputationPolicy {

    /**
     * "Право на ошибку": ниже этого числа исходов в леджере клуба UI показывает
     * "Новичок" (без числа), чтобы один ранний промах не клеймил новичка.
     * Кэш всё равно хранит настоящий индекс — этот порог влияет только на отображение.
     */
    const val MIN_OUTCOMES_FOR_DISPLAY = 3

    /**
     * Подтверждённый ответ → kind посещаемости. Обязательство — это САМ ФАКТ подтверждения
     * (final_status=confirmed), а не голос Этапа 1: с фичей «Этап 2 открыт всем» подтвердиться
     * может и not_going, и не голосовавший (stage_1_vote NULL). Поэтому пришёл → +100, не пришёл
     * → −200 для ЛЮБОГО подтвердившегося. Голос Этапа 1 лишь выбирает лейбл вида (ironclad vs
     * spontaneous / no_show vs spectator) — очки одинаковые. disputed / null attendance →
     * confirmed_unresolved (терминальный, 0 очков).
     */
    fun attendanceKind(stage1Vote: Stage_1Vote?, attendance: AttendanceStatus?): ReputationKind = when (attendance) {
        AttendanceStatus.attended ->
            if (stage1Vote == Stage_1Vote.going) ReputationKind.ironclad else ReputationKind.spontaneous
        AttendanceStatus.absent ->
            if (stage1Vote == Stage_1Vote.going) ReputationKind.no_show else ReputationKind.spectator
        else -> ReputationKind.confirmed_unresolved
    }

    // Открытая встреча (V62) репутацию НЕ трогает вообще (решение PO 2026-07-21, итерация 2) —
    // конвейер пропускает такие события целиком (ReputationService.processFinalizedEvent),
    // отдельного маппинга исходов у формата нет.

    /**
     * Терминальный статус участника складчины → финансовый kind. Null для статусов
     * без влияния на репутацию (см. docs/backlog/skladchina-reputation-redesign.md):
     *  - declined: явный отказ — это ЖЕЛАЕМОЕ поведение ("не могу заплатить — скажи
     *    сразу") и свободный выход из штрафующей складчины. Строка не создаётся вообще
     *    (не строка с 0 очков): три отказа в один тап не должны выводить пользователя
     *    из "Новичок" (раздувание outcome_count). Исторические строки skladchina_declined
     *    сохраняют свои очки; kind остаётся в enum, но больше никогда не выдаётся.
     *  - released: складчина закрылась ДО дедлайна (F5-02). Обещание было "ответить
     *    до дедлайна", а дедлайн так и не наступил — обещание не нарушено.
     */
    fun financeKind(status: SkladchinaParticipantStatus): ReputationKind? = when (status) {
        SkladchinaParticipantStatus.paid -> ReputationKind.skladchina_paid
        SkladchinaParticipantStatus.expired_no_response -> ReputationKind.skladchina_expired
        SkladchinaParticipantStatus.declined -> null
        SkladchinaParticipantStatus.released -> null
        SkladchinaParticipantStatus.pending -> null
    }

    fun pointsFor(kind: ReputationKind): Int = when (kind) {
        ReputationKind.ironclad -> 100
        // Подтверждённая бронь, которую пропустили, сжигает слот и план организатора:
        // один no-show стоит двух посещений (точка безубыточности посещаемости = 67%).
        ReputationKind.no_show -> -200
        // То же, что ironclad/no_show: раз бронь этапа 2 подтверждена, обещание
        // (и ущерб от его нарушения) не зависит от голоса этапа 1.
        ReputationKind.spontaneous -> 100
        ReputationKind.spectator -> -200
        ReputationKind.confirmed_unresolved -> 0
        // 1/10 от ironclad (+100): посещение подтверждает организатор, оплату
        // декларирует сам участник. Символический плюс до появления org-подтверждения (P2).
        ReputationKind.skladchina_paid -> 10
        // Исторический kind — больше не выдаётся (financeKind(declined) = null с
        // редизайна 2026-06-12). Старые строки с -5 на staging сохраняют свои очки;
        // леджер читает сохранённые очки, а не эту функцию, поэтому 0 здесь лишь
        // защищает гипотетического будущего вызывающего.
        ReputationKind.skladchina_declined -> 0
        // 1/5 от no_show (-200): вред сопоставим (сгоревшая бронь), но обязательство
        // наложил организатор — участник никогда не нажимал "подтвердить", как на
        // этапе 2 события. Точка безубыточности ≈ 80% оплат, немного выше метрики
        // успеха "≥70% платят вовремя".
        ReputationKind.skladchina_expired -> -40
        // Отказ от подтверждённого места на Этапе 2 без замены в очереди — половина no_show.
        // Начисляется только когда waitlist пуст (иначе первый из очереди сразу закрывает слот,
        // ущерба нет). Предупредил заранее (отказ разрешён лишь ≥ порога до старта), но дыру
        // оставил → мягче молчаливой неявки, но строго дороже нуля, чтобы честный отказ был
        // выгоднее «пропасть молча» (−200), а не наоборот.
        ReputationKind.abandoned_slot -> -100
        // ЗАРЕЗЕРВИРОВАН, не выдаётся (как skladchina_declined): вид добавлен V63 под штраф
        // молчаливой неявки на открытую встречу, но PO (2026-07-21, итерация 2) вывел открытые
        // встречи из репутации целиком — «наказание без награды» делало подтверждение невыгодным.
        // Величина оставлена под возможный будущий «строгий режим» (половина no_show: слот не сгорел).
        ReputationKind.open_no_show -> -100
    }

    /** Презентационный гейт: показывать реальный индекс только при наличии истории. */
    fun isShown(outcomeCount: Int): Boolean = outcomeCount >= MIN_OUTCOMES_FOR_DISPLAY
}
