package com.clubs.user

import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.tables.records.UsersRecord
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserService(private val userRepository: UserRepository) {

    fun getUserById(id: UUID): UserDto {
        val record = userRepository.findById(id)
            ?: throw NotFoundException("User not found")
        return record.toDto()
    }
}

fun UsersRecord.toDto() = UserDto(
    id = id!!,
    telegramId = telegramId,
    telegramUsername = telegramUsername,
    firstName = firstName,
    lastName = lastName,
    avatarUrl = avatarUrl,
    city = city
)
