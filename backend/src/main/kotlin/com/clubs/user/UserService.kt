package com.clubs.user

import com.clubs.city.CityService
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.interest.InterestService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val onboardingTourRepository: OnboardingTourRepository,
    private val interestService: InterestService,
    // Резолвит cityId в город справочника: имя города и страна берутся оттуда, не от клиента.
    private val cityService: CityService
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun getUserById(id: UUID): UserDto {
        val record = userRepository.findById(id)
            ?: throw NotFoundException("User not found")
        return record.toDto(onboardingTourRepository.findCompleted(id))
    }

    @Transactional
    fun updateProfile(userId: UUID, request: UpdateMeRequest): UserDto {
        // Город только из справочника; null = человек очистил город в профиле.
        val city = request.cityId?.let { cityService.requireCity(it) }
        userRepository.updateProfileFields(
            userId = userId,
            city = city,
            bio = request.bio.blankToNull()
        )
        interestService.replaceUserInterests(userId, request.interests)
        // Профиль-квест: одноразовые вехи заполнения (+XP, кап 50) — после записи полей
        // и интересов, в той же транзакции. Уже достигнутые вехи UPDATE не трогает.
        userRepository.markQuestMilestones(userId)
        return getUserById(userId)
    }

    @Transactional(readOnly = true)
    fun getMyInterests(userId: UUID): List<String> = interestService.getUserInterests(userId)

    /**
     * Отмечает тур пройденным. Туры независимы: каждый экран закрывается своим ключом, и
     * пройденный тур клуба ничего не говорит про тур профиля.
     *
     * Повторный вызов — НЕ ошибка (в отличие от прежнего 409 на едином флаге): отметка
     * идемпотентна, а «уже пройден» и «отметили сейчас» для клиента означают одно и то же —
     * тур закрыт. Отличаются они только строчкой в логе.
     */
    @Transactional
    fun completeTour(userId: UUID, rawTour: String): UserDto {
        val tour = OnboardingTour.parse(rawTour)
            ?: throw ValidationException("Unknown onboarding tour: $rawTour")
        // Явная проверка, хотя внешний ключ и так не дал бы вставить строку: без неё
        // несуществующий пользователь получал бы 500 от нарушения FK вместо честного 404.
        userRepository.findById(userId) ?: throw NotFoundException("User not found")
        if (onboardingTourRepository.markCompleted(userId, tour)) {
            log.info("Onboarding tour completed: userId={} tour={}", userId, tour)
        }
        return getUserById(userId)
    }
}

private fun String?.blankToNull(): String? = this?.trim()?.ifEmpty { null }

/**
 * Туры приходят параметром, а не читаются здесь: маппер не должен ходить в базу, а оба
 * вызывающих (профиль и авторизация) всё равно достают их сами.
 */
fun UsersRecord.toDto(onboardingTours: Set<OnboardingTour>) = UserDto(
    id = id!!,
    telegramId = telegramId,
    telegramUsername = telegramUsername,
    firstName = firstName,
    lastName = lastName,
    avatarUrl = avatarUrl,
    city = city,
    country = country,
    cityId = cityId,
    bio = bio,
    onboardingTours = onboardingTours
)
