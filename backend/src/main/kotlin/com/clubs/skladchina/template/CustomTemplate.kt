package com.clubs.skladchina.template

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.CreateSkladchinaRequest
import com.clubs.skladchina.SkladchinaRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Универсальная складчина, управляемая организатором — поведение Phase A, теперь выраженное
 * как шаблон по умолчанию. Организатор выбирает режим, участников и суммы; оплата
 * самозаявляется по honor-system (→ итоги НЕ верифицируются).
 */
@Component
class CustomTemplate(
    private val skladchinaRepository: SkladchinaRepository
) : SkladchinaTemplateStrategy {

    override val type = SkladchinaTemplate.custom
    override val outcomesVerified = false
    override val declinePolicy = DeclinePolicy.FREE

    override fun resolveCreation(clubId: UUID, creatorId: UUID, request: CreateSkladchinaRequest): TemplateResolution {
        val mode = SkladchinaMode.values().find { it.literal == request.paymentMode }
            ?: throw ValidationException("Invalid payment mode: ${request.paymentMode}")
        validateAmounts(request, mode)

        if (request.participants.isEmpty()) throw ValidationException("Нужен хотя бы один участник")
        val userIds = request.participants.map { it.userId }
        if (userIds.distinct().size != userIds.size) {
            throw ValidationException("Duplicate userId in participants list")
        }
        val notActive = skladchinaRepository.findNonActiveMembers(clubId, userIds)
        if (notActive.isNotEmpty()) {
            throw ForbiddenException("Some participants are not active members of this club")
        }

        val participants: List<Pair<UUID, Long?>> = when (mode) {
            SkladchinaMode.voluntary -> request.participants.map { it.userId to null }
            SkladchinaMode.fixed_individual -> request.participants.map { it.userId to it.expectedAmountKopecks }
            SkladchinaMode.fixed_equal ->
                SkladchinaShares.equal(request.totalGoalKopecks!!, userIds).map { it.first to (it.second as Long?) }
        }
        val totalGoal = when (mode) {
            SkladchinaMode.voluntary, SkladchinaMode.fixed_equal -> request.totalGoalKopecks
            SkladchinaMode.fixed_individual -> participants.sumOf { it.second ?: 0L }
        }
        return TemplateResolution(mode, totalGoal, participants, eventId = null)
    }

    private fun validateAmounts(request: CreateSkladchinaRequest, mode: SkladchinaMode) {
        when (mode) {
            SkladchinaMode.fixed_equal -> {
                val total = request.totalGoalKopecks
                    ?: throw ValidationException("totalGoalKopecks required for fixed_equal mode")
                if (total <= 0) throw ValidationException("totalGoalKopecks must be positive")
            }
            SkladchinaMode.fixed_individual -> request.participants.forEach { p ->
                if (p.expectedAmountKopecks == null || p.expectedAmountKopecks <= 0) {
                    throw ValidationException("expectedAmountKopecks required for each participant in fixed_individual mode")
                }
            }
            SkladchinaMode.voluntary -> {
                // Опциональная ориентировочная цель; ожидаемые суммы участников игнорируются.
                if (request.totalGoalKopecks != null && request.totalGoalKopecks <= 0) {
                    throw ValidationException("totalGoalKopecks must be positive")
                }
            }
        }
    }
}
