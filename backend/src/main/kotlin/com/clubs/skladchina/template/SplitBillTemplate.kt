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
        val attended = attendedAll.filter { it !in notActive }

        val prepaid = resolvePrepaid(request, creatorId, attended, bill)
        // "Исключить себя": организатор был на событии, но с него денег не берут — убрать его,
        // чтобы равная доля делилась между остальными и ему не показывалась панель оплаты.
        val payers = attended.filter { !request.excludeSelf || it != creatorId }
        // С предоплатой сбор осмыслен и с одним должником («я заплатил 2000, ты должен 1000»):
        // участников всё равно двое, просто один уже отмечен оплатившим.
        val minPayers = if (prepaid != null) MIN_PAYERS_WITH_PREPAY else MIN_ATTENDED
        if (payers.size < minPayers) {
            throw ValidationException(
                if (minPayers == MIN_PAYERS_WITH_PREPAY) {
                    "Нужен хотя бы один участник к оплате — отметьте, кто пришёл на событие"
                } else {
                    "Нужно минимум $MIN_ATTENDED пришедших участника для разделения счёта"
                }
            )
        }

        // Часть счёта, закрытая организатором из своего кармана, до остальных не доезжает — делим остаток.
        // fixed_equal: назначенная сервером равная доля. voluntary: доля не назначается — каждый
        // вводит свою сумму при оплате; счёт остаётся целью, к которой заполняется бар.
        val toSplit = bill - (prepaid ?: 0L)
        // Каждому должнику должна достаться хотя бы копейка: нулевую долю отвергает CHECK в БД,
        // и вместо понятной ошибки формы пользователь получил бы 500.
        if (mode != SkladchinaMode.voluntary && toSplit < payers.size) {
            throw ValidationException("Сумма к разделу слишком мала — на каждого не выходит и копейки")
        }
        val payerShares: List<Pair<UUID, Long?>> =
            if (mode == SkladchinaMode.voluntary) payers.map { it to null }
            else SkladchinaShares.equal(toSplit, payers).map { it.first to (it.second as Long?) }
        // Предоплативший организатор возвращается в состав — движок сразу пометит его оплатившим,
        // чтобы взнос был виден и в прогрессе («скинулись N из M»), и в собранной сумме.
        val participants =
            if (prepaid == null) payerShares
            else payerShares + (creatorId to prepaid.takeIf { mode != SkladchinaMode.voluntary })
        return TemplateResolution(mode, bill, participants, eventId, prepaid)
    }

    /**
     * Проверяет «я уже внёс N» организатора. Поле имеет смысл только вместе с "исключить себя":
     * иначе доля организатора посчиталась бы дважды — и в его взносе, и в назначенной ему доле.
     * Величина взноса меньше счёта строго: равный счёту взнос не оставляет остальным что собирать.
     */
    private fun resolvePrepaid(
        request: CreateSkladchinaRequest,
        creatorId: UUID,
        attended: List<UUID>,
        bill: Long,
    ): Long? {
        val prepaid = request.selfPaidKopecks ?: return null
        if (!request.excludeSelf) {
            throw ValidationException("Сумму «я уже внёс» можно указать, только исключив себя из счёта")
        }
        if (creatorId !in attended) {
            throw ValidationException("Вас нет среди пришедших на событие — засчитывать взнос некуда")
        }
        if (prepaid >= bill) {
            throw ValidationException("Ваша сумма должна быть меньше суммы чека — иначе собирать нечего")
        }
        return prepaid
    }

    companion object {
        // Максимальный возраст события, за которое ещё можно разделить счёт.
        // Не private: тот же порог отбирает события в списке «по чему можно разделить счёт»,
        // и разъезд правил дал бы событие, которое видно в списке, но не принимается при создании.
        const val MAX_EVENT_AGE_DAYS = 30L
        // Минимальное число пришедших участников для разделения счёта.
        const val MIN_ATTENDED = 2
        // Минимум должников, когда часть счёта организатор уже закрыл сам: сбор «я заплатил, ты должен»
        // осмыслен и вдвоём — второй участник (сам организатор) в сборе уже есть, только оплаченный.
        private const val MIN_PAYERS_WITH_PREPAY = 1
    }
}
