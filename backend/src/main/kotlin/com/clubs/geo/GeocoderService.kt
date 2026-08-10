package com.clubs.geo

import com.clubs.common.exception.ValidationException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Прямой геокодинг адреса через Яндекс — СЕРВЕРНОЙ стороной.
 *
 * Раньше фронтенд ходил в geocode-maps.yandex.ru сам, с ключом из `VITE_YANDEX_GEOCODER_API_KEY`.
 * Это давало три беды сразу, и все три вылезли на проде 2026-08-05:
 *  1. Ключ лежал в клиентском бандле и был виден любому — единственной защитой оставалось
 *     ограничение по HTTP Referer, которое подделывается одной строкой в curl;
 *  2. То же ограничение по Referer роняло поиск на проде, пока домен не внесли в кабинет,
 *     а раскатка заняла час;
 *  3. Пользователь с залипшим в кэше старым бандлом (assets отдаются `immutable` на год)
 *     продолжал слать СТАРЫЙ ключ, и починить это со стороны сервера было нечем.
 *
 * С сервера ключ не публикуется, Referer подставляем сами, а клиент ходит на наш же домен —
 * ни кэш бандла, ни ограничения Яндекса на клиента больше не влияют.
 */
@Service
class GeocoderService(
    @Value("\${yandex.geocoder-api-key:}") private val configuredKey: String,
    /**
     * Совместимость на время переезда: тем же ключом раньше собирался фронтенд, и в окружении
     * Coolify он уже лежит под VITE_-именем. Берём его, только если серверного нет.
     *
     * Проверяем именно на ПУСТОТУ, а не на отсутствие: docker-compose объявляет переменную как
     * `${YANDEX_GEOCODER_API_KEY:-}`, то есть она всегда существует, просто пустая — и
     * spring-дефолт `${a:${b:}}` в этом случае не срабатывает, подставляя пустую строку.
     */
    @Value("\${yandex.geocoder-api-key-fallback:}") private val fallbackKey: String,
    /**
     * Referer, с которым идём в Яндекс. Ключ ограничен списком доменов в кабинете, и запрос
     * БЕЗ этого заголовка получает 403 — проверено с самого сервера.
     *
     * Отдельная настройка, а НЕ `telegram.webapp-base-url`: на проде та переменная указывает на
     * технический домен Coolify (`u342…sslip.io`), которого в белом списке Яндекса нет и быть не
     * должно. На staging она случайно совпадала с публичным доменом — из-за этого поиск работал
     * там и падал на проде уже после переезда геокодера на сервер.
     *
     * Значение не обязано совпадать с доменом, откуда пришёл пользователь: проверяется только
     * вхождение в белый список, а запрос всё равно server-to-server.
     */
    @Value("\${yandex.geocoder-referer}") private val geocoderReferer: String,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(GeocoderService::class.java)

    private val apiKey: String = configuredKey.ifBlank { fallbackKey }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Ищет адрес по строке. Возвращает null, если Яндекс ничего не нашёл (это не ошибка —
     * пользователю показывается «уточните запрос»), и бросает [GeocoderUnavailableException],
     * если сам сервис недоступен: клиенту важно различать эти случаи разными текстами.
     */
    fun geocode(rawQuery: String): GeocodeResultDto? {
        val query = rawQuery.trim()
        if (query.length < MIN_QUERY_LEN) throw ValidationException("Запрос слишком короткий")
        if (query.length > MAX_QUERY_LEN) throw ValidationException("Запрос слишком длинный")
        if (apiKey.isBlank()) {
            log.error("Geocoder key is not configured: set YANDEX_GEOCODER_API_KEY")
            throw GeocoderUnavailableException("Геокодер не настроен")
        }

        val url = URI.create(
            "$GEOCODER_ENDPOINT?apikey=${enc(apiKey)}&geocode=${enc(query)}" +
                "&format=json&results=1&lang=ru_RU"
        )
        val request = HttpRequest.newBuilder(url)
            .timeout(REQUEST_TIMEOUT)
            .header("Referer", geocoderReferer)
            .GET()
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            log.warn("Geocoder request failed: query='{}' reason={}", query, e.message)
            throw GeocoderUnavailableException("Геокодер недоступен", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GeocoderUnavailableException("Геокодер недоступен", e)
        }

        if (response.statusCode() !in 200..299) {
            // Тело Яндекса пишем в лог целиком: только по нему отличают «домен не разрешён»
            // от «исчерпан лимит» — оба приходят как 403 с одинаковым текстом наружу.
            log.error(
                "Geocoder returned {}: query='{}' body={}",
                response.statusCode(), query, response.body().take(ERROR_BODY_LOG_LIMIT)
            )
            throw GeocoderUnavailableException("Геокодер ответил ${response.statusCode()}")
        }

        return parseFirstResult(response.body())
    }

    private fun parseFirstResult(body: String): GeocodeResultDto? {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            log.error("Geocoder returned unparseable body: {}", e.message)
            throw GeocoderUnavailableException("Геокодер вернул неожиданный ответ", e)
        }

        val member = root.path("response").path("GeoObjectCollection").path("featureMember")
        val geoObject = member.firstOrNull()?.path("GeoObject") ?: return null

        // pos у Яндекса — строка «lon lat» (порядок обратный привычному), см. yandexMaps.ts.
        val pos = geoObject.path("Point").path("pos").asText("").split(" ")
        if (pos.size != 2) return null
        val lon = pos[0].toDoubleOrNull() ?: return null
        val lat = pos[1].toDoubleOrNull() ?: return null

        return GeocodeResultDto(address = displayAddress(geoObject), lat = lat, lon = lon)
    }

    /** Человеческий адрес: полный текст геокодера, с фолбэком на имя объекта. */
    private fun displayAddress(geoObject: JsonNode): String {
        val meta = geoObject.path("metaDataProperty").path("GeocoderMetaData")
        val text = meta.path("text").asText("")
        if (text.isNotBlank()) return text
        return geoObject.path("name").asText("")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        // Документированный endpoint продукта «API Геокодера».
        private const val GEOCODER_ENDPOINT = "https://geocode-maps.yandex.ru/v1/"
        // Минимальная длина осмысленного адресного запроса.
        private const val MIN_QUERY_LEN = 3
        // Потолок длины запроса — защита от абсурдного тела, у Яндекса свои лимиты строки.
        private const val MAX_QUERY_LEN = 200
        // Сколько ждать соединения с Яндексом.
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        // Полный таймаут запроса: пикер не должен висеть в «Ищем…» дольше.
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)
        // Сколько символов тела ошибки писать в лог.
        private const val ERROR_BODY_LOG_LIMIT = 500
    }
}

/** Геокодер недоступен (сеть, таймаут, не-2xx от Яндекса, кривой ответ). Мапится в 503. */
class GeocoderUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
