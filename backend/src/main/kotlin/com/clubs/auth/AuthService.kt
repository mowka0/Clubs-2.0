package com.clubs.auth

import com.clubs.common.exception.ValidationException
import com.clubs.user.UserRepository
import com.clubs.user.toDto
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Service
class AuthService(
    private val validator: TelegramInitDataValidator,
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper
) {

    fun authenticate(request: AuthRequest): AuthResponse {
        if (!validator.validate(request.initData)) {
            throw ValidationException("Invalid Telegram initData signature")
        }

        val params = request.initData.split("&")
            .map { it.split("=", limit = 2) }
            .filter { it.size == 2 }
            .associate { it[0] to URLDecoder.decode(it[1], StandardCharsets.UTF_8) }

        val userJson = params["user"]
            ?: throw ValidationException("Missing user data in initData")

        val userData = objectMapper.readTree(userJson)
        val telegramId = userData["id"]?.asLong()
            ?: throw ValidationException("Missing user id in initData")
        val firstName = userData["first_name"]?.asText()
            ?: throw ValidationException("Missing first_name in initData")
        val lastName = userData["last_name"]?.asText()
        val username = userData["username"]?.asText()
        val photoUrl = userData["photo_url"]?.asText()

        val userRecord = userRepository.upsert(
            telegramId = telegramId,
            telegramUsername = username,
            firstName = firstName,
            lastName = lastName,
            avatarUrl = photoUrl
        )

        val token = jwtService.generateToken(userRecord.id!!, telegramId)
        return AuthResponse(token = token, user = userRecord.toDto())
    }
}
