package com.clubs.club

import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import java.time.OffsetDateTime
import java.util.UUID

data class Club(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String,
    val category: ClubCategory,
    val accessType: AccessType,
    // Денормализованное имя города для показа. Источник правды — cityId; оба поля пишутся вместе.
    val city: String,
    // Город из справочника. null = легаси-клуб, город которого не распознался миграцией V74.
    val cityId: UUID? = null,
    val district: String?,
    val memberLimit: Int,
    val subscriptionPrice: Int,
    val avatarUrl: String?,
    // Обложка шапки страницы клуба — отдельная картинка от аватара (V70). Дефолт null, чтобы
    // не переписывать существующие тестовые билдеры Club(...); NULL = градиент по категории.
    val coverUrl: String? = null,
    val rules: String?,
    val applicationQuestion: String?,
    val inviteLink: String?,
    // Второй инвайт-код — «через заявку» (V71): по нему приглашение из Telegram в закрытом клубе
    // ведёт на одобрение организатором. Дефолт null, чтобы не переписывать тестовые билдеры Club(...).
    val applyInviteCode: String? = null,
    val memberCount: Int,
    val isActive: Boolean,
    // Реквизиты СБП для взносов (платёжные данные организатора вне платформы). Видны только участникам.
    // По умолчанию null, чтобы не пришлось обновлять все существующие тестовые билдеры Club(...);
    // в проде значения выставляются через mapper.
    val paymentLink: String? = null,
    val paymentMethodNote: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
