package com.clubs.user

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.interest.InterestService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val interestService: InterestService
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun getUserById(id: UUID): UserDto {
        val record = userRepository.findById(id)
            ?: throw NotFoundException("User not found")
        return record.toDto()
    }

    @Transactional
    fun updateProfile(userId: UUID, request: UpdateMeRequest): UserDto {
        userRepository.updateProfileFields(
            userId = userId,
            country = request.country.blankToNull(),
            city = request.city.blankToNull(),
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
     * Отмечает онбординг пройденным. Пройденным считается только выход через дверь —
     * тап главной кнопки слайда; «Пропустить» в потоке нет.
     *
     * Дверь никуда не сохраняется, только в лог: это единственная метрика намерения,
     * которая у нас есть, а колонка под неё была бы данными без потребителя.
     */
    @Transactional
    fun completeOnboarding(userId: UUID, door: OnboardingDoor): UserDto {
        if (!userRepository.markOnboarded(userId, OffsetDateTime.now())) {
            // Строку не тронули по одной из двух причин — различаем их, чтобы не отвечать
            // 409 «уже пройден» тому, кого вообще нет.
            userRepository.findById(userId) ?: throw NotFoundException("User not found")
            throw ConflictException("Onboarding already completed")
        }
        log.info("Onboarding completed: userId={} door={}", userId, door)
        return getUserById(userId)
    }
}

private fun String?.blankToNull(): String? = this?.trim()?.ifEmpty { null }

fun UsersRecord.toDto() = UserDto(
    id = id!!,
    telegramId = telegramId,
    telegramUsername = telegramUsername,
    firstName = firstName,
    lastName = lastName,
    avatarUrl = avatarUrl,
    city = city,
    country = country,
    bio = bio,
    onboardedAt = onboardedAt
)
