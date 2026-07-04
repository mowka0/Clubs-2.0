package com.clubs.skladchina.template

import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.CreateSkladchinaRequest
import com.clubs.skladchina.SkladchinaRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * "Разделить счёт" — делит счёт за прошедшее событие между теми, кто РЕАЛЬНО его посетил.
 *
 * Единственный шаблон с VERIFIED-якорем: набор участников берётся из отметок посещаемости,
 * проставленных организатором, а не из его свободного выбора — так что случайных людей нельзя
 * мобилизовать, а благо уже потреблено (ты там был), что убирает лазейку безбилетника.
 * `totalGoalKopecks` — это сумма счёта (= цель, к которой заполняется прогресс-бар) в обоих режимах.
 * Намеренное чтение через модули (skladchina → event) — сплит по своей сути про событие;
 * зависимость односторонняя.
 *
 * Два режима сплита (выбор организатора в форме):
 *  - `fixed_equal`  — счёт делится поровну между пришедшими; каждый платит назначенную сервером долю.
 *  - `voluntary`    — каждый пришедший вводит свою сумму; бар заполняется к сумме счёта тем, что
 *                     люди вносят (неравные суммы). `fixed_individual` НЕ предлагается — в сплите
 *                     организатор никогда не назначает суммы на человека заранее.
 */
@Component
class SplitBillTemplate(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val skladchinaRepository: SkladchinaRepository,
) : SkladchinaTemplateStrategy {

    override val type = SkladchinaTemplate.split_bill
    override val outcomesVerified = true
    // Благо уже потреблено (ты был на событии) — бесплатный отказ был бы безбилетничеством,
    // поэтому отказ должен быть обоснован и одобрен организатором (V28).
    override val declinePolicy = DeclinePolicy.REQUIRES_APPROVAL

    override fun resolveCreation(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest): TemplateResolution {
        val eventId = request.eventId
            ?: throw ValidationException("Не указано событие для разделения счёта")
        val event = eventRepository.findById(eventId)
            ?: throw NotFoundException("Событие не найдено")
        if (event.clubId != clubId) {
            throw ValidationException("Событие принадлежит другому клубу")
        }
        if (!event.attendanceMarked) {
            throw ValidationException("Сначала отметьте, кто пришёл на событие")
        }
        if (event.eventDatetime.isBefore(OffsetDateTime.now().minusDays(MAX_EVENT_AGE_DAYS))) {
            throw ValidationException("Событие старше $MAX_EVENT_AGE_DAYS дней — счёт уже не разделить")
        }
        // Один сплит на событие: активный блокирует (вместо создания — открыть его), успешно закрытый
        // тоже блокирует (уже собрано). Проваленный/отменённый сплит НЕ блокирует — организатор повторяет.
        skladchinaRepository.findBlockingByEventId(eventId)?.let { existing ->
            throw if (existing.status == SkladchinaStatus.active) {
                ValidationException("По этому событию уже есть активный сбор — откройте его")
            } else {
                ValidationException("По этому событию счёт уже собран")
            }
        }

        // Сплит предлагает ровно два режима; fixed_individual (организатор назначает суммы на человека)
        // не имеет смысла, когда якорь — сам счёт, поэтому отклоняется, а не тихо приводится к другому.
        val mode = when (request.paymentMode) {
            SkladchinaMode.fixed_equal.literal -> SkladchinaMode.fixed_equal
            SkladchinaMode.voluntary.literal -> SkladchinaMode.voluntary
            else -> throw ValidationException("Для счёта выберите режим: поровну или каждый сам")
        }

        val bill = request.totalGoalKopecks
            ?: throw ValidationException("Укажите сумму чека")
        if (bill <= 0) throw ValidationException("Сумма чека должна быть положительной")

        val attendedAll = eventResponseRepository.findAttendedUserIds(eventId)
        // В складчине могут участвовать только всё ещё активные участники (доступ к странице + DM).
        // Тот, кто был на событии, но с тех пор покинул клуб, тихо отбрасывается (его долю здесь не собрать).
        val notActive = skladchinaRepository.findNonActiveMembers(clubId, attendedAll)
        val attended = attendedAll
            .filter { it !in notActive }
            // "Исключить себя": организатор был на событии, но с него денег не берут — убрать его,
            // чтобы равная доля делилась между остальными и ему не показывалась панель оплаты (не участник).
            .filter { !request.excludeSelf || it != creatorId }
        if (attended.size < MIN_ATTENDED) {
            throw ValidationException("Нужно минимум $MIN_ATTENDED пришедших участника для разделения счёта")
        }

        // fixed_equal: назначенная сервером равная доля. voluntary: доля не назначается — каждый
        // вводит свою сумму при оплате; счёт остаётся целью, к которой заполняется бар.
        val participants: List<Pair<UUID, Long?>> =
            if (mode == SkladchinaMode.voluntary) attended.map { it to null }
            else SkladchinaShares.equal(bill, attended).map { it.first to (it.second as Long?) }
        return TemplateResolution(mode, bill, participants, eventId)
    }

    companion object {
        // Максимальный возраст события, за которое ещё можно разделить счёт.
        private const val MAX_EVENT_AGE_DAYS = 30L
        // Минимальное число пришедших участников для разделения счёта.
        private const val MIN_ATTENDED = 2
    }
}
