package com.clubs.user

import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.interest.InterestService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val interestService: InterestService
) {

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
        return getUserById(userId)
    }

    @Transactional(readOnly = true)
    fun getMyInterests(userId: UUID): List<String> = interestService.getUserInterests(userId)
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
    bio = bio
)
