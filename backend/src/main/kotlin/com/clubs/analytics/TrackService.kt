package com.clubs.analytics

import com.clubs.analytics.dto.TrackEventRequest
import com.clubs.analytics.dto.TrackRequest
import com.clubs.common.exception.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Приём пачки событий от Mini App (product-analytics.md § 4): whitelist видов и ключей props,
 * размеры, запись одной транзакцией. Любое нарушение — 400 на всю пачку (fail-closed: частичная
 * запись скрыла бы ошибку клиента).
 */
@Service
class TrackService(private val productEventRepository: ProductEventRepository) {

    private val log = LoggerFactory.getLogger(TrackService::class.java)

    @Transactional
    fun track(userId: UUID, request: TrackRequest) {
        val events = request.events.map { toEvent(userId, it) }
        productEventRepository.recordAll(events)
        log.debug("Tracked {} app event(s): userId={}", events.size, userId)
    }

    private fun toEvent(userId: UUID, raw: TrackEventRequest): NewProductEvent {
        val kind = AppEventKind.fromKind(raw.kind) ?: throw ValidationException("Unknown event kind: ${raw.kind.take(48)}")
        val props = raw.props?.let { validateProps(kind, it) }
        return NewProductEvent(kind = kind.event, userId = userId, clubId = raw.clubId, props = props)
    }

    private fun validateProps(kind: AppEventKind, props: Map<String, Any?>): Map<String, Any?> {
        if (props.size > MAX_PROPS) throw ValidationException("props: at most $MAX_PROPS keys")
        props.forEach { (key, value) ->
            if (!PROP_KEY.matches(key)) throw ValidationException("props: invalid key")
            if (key !in kind.allowedProps) throw ValidationException("props: key '$key' is not allowed for ${kind.event.kind}")
            when (value) {
                null, is Boolean, is Number -> Unit
                is String -> if (value.length > MAX_PROP_STRING) throw ValidationException("props: value of '$key' is too long")
                else -> throw ValidationException("props: value of '$key' must be a string, number or boolean")
            }
        }
        return props
    }

    companion object {
        // Пределы props (product-analytics.md § 4): плоский объект под ответы на вопросы, а не свалка.
        private const val MAX_PROPS = 10
        private const val MAX_PROP_STRING = 100
        private val PROP_KEY = Regex("^[a-zA-Z]{1,32}$")
    }
}
