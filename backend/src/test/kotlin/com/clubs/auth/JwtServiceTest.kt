package com.clubs.auth

import com.clubs.common.security.AuthenticatedUser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtServiceTest {

    private lateinit var jwtService: JwtService

    // Must be at least 32 chars for HMAC-SHA256
    private val secret = "test-secret-key-that-is-at-least-32-characters-long"
    private val expirationMs = 86400000L // 24 hours

    @BeforeEach
    fun setUp() {
        jwtService = JwtService(secret, expirationMs)
    }

    @Test
    fun `generateToken should return non-empty string`() {
        val userId = UUID.randomUUID()
        val telegramId = 123456789L

        val token = jwtService.generateToken(userId, telegramId)

        assertTrue(token.isNotBlank(), "Token must not be blank")
        assertTrue(token.split(".").size == 3, "JWT must have 3 parts separated by dots")
    }

    @Test
    fun `parseToken should return Authentication with correct principal for valid token`() {
        val userId = UUID.randomUUID()
        val telegramId = 987654321L

        val token = jwtService.generateToken(userId, telegramId)
        val authentication = jwtService.parseToken(token)

        assertNotNull(authentication, "Authentication must not be null for valid token")
        assertTrue(authentication.isAuthenticated, "Authentication must be authenticated")

        val principal = authentication.principal as AuthenticatedUser
        assertEquals(userId, principal.userId, "User ID must match")
        assertEquals(telegramId, principal.telegramId, "Telegram ID must match")
    }

    @Test
    fun `parseToken should return null for invalid token`() {
        val result = jwtService.parseToken("this.is.invalid")

        assertNull(result, "Authentication must be null for invalid token")
    }

    @Test
    fun `parseToken should return null for completely malformed token`() {
        val result = jwtService.parseToken("garbage-token-value")

        assertNull(result, "Authentication must be null for malformed token")
    }

    @Test
    fun `parseToken should return null for expired token`() {
        // Create a JwtService with 0ms expiration so the token is immediately expired
        val expiredJwtService = JwtService(secret, 0L)
        val userId = UUID.randomUUID()
        val telegramId = 111222333L

        val token = expiredJwtService.generateToken(userId, telegramId)

        // The token should be expired because expirationMs = 0
        // This means exp = currentTime + 0 = currentTime, so by the time we parse it, it's expired
        // Adding a small sleep to guarantee expiration
        Thread.sleep(10)

        val result = jwtService.parseToken(token)

        assertNull(result, "Authentication must be null for expired token")
    }

    @Test
    fun `parseToken should return null for token signed with different key`() {
        val differentSecret = "a-completely-different-secret-key-with-32-chars!!"
        val otherJwtService = JwtService(differentSecret, expirationMs)

        val userId = UUID.randomUUID()
        val telegramId = 444555666L

        val tokenFromOtherService = otherJwtService.generateToken(userId, telegramId)
        val result = jwtService.parseToken(tokenFromOtherService)

        assertNull(result, "Authentication must be null for token signed with different key")
    }

    @Test
    fun `generateToken and parseToken should roundtrip multiple users correctly`() {
        val users = (1..5).map { UUID.randomUUID() to it.toLong() * 100 }

        users.forEach { (userId, telegramId) ->
            val token = jwtService.generateToken(userId, telegramId)
            val auth = jwtService.parseToken(token)
            assertNotNull(auth)
            val principal = auth.principal as AuthenticatedUser
            assertEquals(userId, principal.userId)
            assertEquals(telegramId, principal.telegramId)
        }
    }
}
