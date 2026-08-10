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
import java.util.Locale
import java.util.UUID

/**
 * Подсказки мест для пикера события — продукт «API Геосаджеста» Яндекса, СЕРВЕРНОЙ стороной.
 *
 * Зачем отдельный сервис, а не расширение [GeocoderService]: это другой продукт кабинета —
 * другой эндпоинт, другой ключ, другой формат ответа. Общего у них только Referer.
 *
 * Геокодер организаций не знает принципиально: «Old Friends» он не найдёт, а найдёт улицу.
 * Саджест знает заведения, но НЕ отдаёт координат — только `uri`. Поэтому выбор места стоит
 * двух запросов: сюда за списком, затем в [GeocoderService.resolveUri] за точкой.
 *
 * Ключ живёт только на сервере: в бандл фронтенда он не попадает, ограничение по Referer
 * не зависит от того, какой бандл залип в кэше у пользователя (урок геокодера, 2026-08-05).
 */
@Service
class SuggestService(
    /**
     * Ключ продукта «API Геосаджеста». Пусто = сервис честно отвечает 503 с понятным логом:
     * пикер покажет «поиск временно недоступен», но точку по-прежнему можно поставить пином.
     */
    @Value("\${yandex.suggest-api-key:}") private val apiKey: String,
    /**
     * Referer переиспользуется общий с геокодером: белый список доменов в кабинете Яндекса
     * один и тот же, а заводить вторую настройку под то же значение — плодить рассинхрон.
     */
    @Value("\${yandex.geocoder-referer}") private val referer: String,
    private val cityCenterRepository: CityCenterRepository,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(SuggestService::class.java)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Ищет заведения и адреса по строке. Пустой список — валидный ответ («ничего не нашли»),
     * недоступность сервиса — [SuggestUnavailableException] (503): пользователю это разные
     * тексты, и склеивать их в один «пусто» нельзя.
     *
     * [clubId] сужает поиск до города клуба. Если со строгой рамкой не нашлось ничего —
     * один повторный запрос без рамки: загородная база отдыха иначе превращается
     * в «ничего не нашли». Стоит это до двух запросов и только в пустом случае.
     */
    fun suggest(rawQuery: String, clubId: UUID?): List<SuggestItemDto> {
        val query = rawQuery.trim()
        if (query.length < MIN_QUERY_LEN) throw ValidationException("Запрос слишком короткий")
        if (query.length > MAX_QUERY_LEN) throw ValidationException("Запрос слишком длинный")
        if (apiKey.isBlank()) {
            log.error("Suggest key is not configured: set YANDEX_SUGGEST_API_KEY")
            throw SuggestUnavailableException("Геосаджест не настроен")
        }

        val center = clubId?.let { cityCenterRepository.findByClubId(it) }
        val bounded = fetchSuggestions(query, center)
        if (bounded.isNotEmpty() || center == null) return bounded

        log.debug("Suggest found nothing within city bounds, retrying worldwide: query='{}'", query)
        return fetchSuggestions(query, null)
    }

    /** Один поход в Геосаджест. [center] = null — запрос без географической рамки. */
    private fun fetchSuggestions(query: String, center: CityCenter?): List<SuggestItemDto> {
        val request = HttpRequest.newBuilder(buildUrl(query, center))
            .timeout(REQUEST_TIMEOUT)
            .header("Referer", referer)
            .GET()
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            log.warn("Suggest request failed: query='{}' reason={}", query, e.message)
            throw SuggestUnavailableException("Геосаджест недоступен", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuggestUnavailableException("Геосаджест недоступен", e)
        }

        if (response.statusCode() !in 200..299) {
            // Тело пишем в лог: «домен не разрешён» и «исчерпан лимит» оба приходят как 403,
            // и различить их можно только по тексту ответа.
            log.error(
                "Suggest returned {}: query='{}' body={}",
                response.statusCode(), query, response.body().take(ERROR_BODY_LOG_LIMIT)
            )
            throw SuggestUnavailableException("Геосаджест ответил ${response.statusCode()}")
        }

        return parseSuggestions(response.body())
    }

    private fun buildUrl(query: String, center: CityCenter?): URI {
        val url = StringBuilder(SUGGEST_ENDPOINT)
            .append("?apikey=").append(enc(apiKey))
            .append("&text=").append(enc(query))
            .append("&lang=").append(LANG)
            .append("&results=").append(MAX_RESULTS)
            // print_address=1 — иначе адрес в ответе не приходит вовсе;
            // attrs=uri — без него нечем разрешать точку;
            // types=biz,geo — заведения и адреса вперемешку, как решил PO.
            .append("&print_address=1&attrs=uri&types=biz,geo")

        if (center != null) {
            // Locale.ROOT обязателен: под русской локалью формат дал бы «40,972935»,
            // и Яндекс вернул бы 400 — координаты разделяются как раз запятой.
            url.append("&ll=")
                .append(String.format(Locale.ROOT, "%.6f,%.6f", center.lon, center.lat))
                .append("&spn=").append(CITY_SPAN_DEGREES)
                // strict_bounds обязателен: без него ll+spn — лишь мягкое смещение, и запрос
                // «антикафе» рядом с Иваново вытаскивал Астану, Москву и Волгоград.
                .append("&strict_bounds=1")
        }
        return URI.create(url.toString())
    }

    private fun parseSuggestions(body: String): List<SuggestItemDto> {
        val root = try {
            objectMapper.readTree(body)
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            log.error("Suggest returned unparseable body: {}", e.message)
            throw SuggestUnavailableException("Геосаджест вернул неожиданный ответ", e)
        }
        return root.path("results").mapNotNull(::toItem)
    }

    /** Подсказка → DTO. null = строка бесполезна (нет `uri` или названия) и в выдачу не идёт. */
    private fun toItem(node: JsonNode): SuggestItemDto? {
        // Без uri координату не достать, а мёртвая строка в списке хуже её отсутствия.
        val uri = node.path("uri").asText("").trim()
        if (uri.isEmpty()) return null

        val title = node.path("title").path("text").asText("").trim()
        if (title.isEmpty()) return null

        // У заведения subtitle — рубрика («Кальян-бар»), у адреса — город с регионом.
        val rubric = node.path("subtitle").path("text").asText("").trim()
        val address = node.path("address").path("formatted_address").asText("").trim()
        val kind = detectKind(node.path("tags"))

        return SuggestItemDto(
            title = title,
            subtitle = buildSubtitle(kind, rubric, address),
            address = address,
            kind = kind,
            uri = uri
        )
    }

    /**
     * Вторая строка подсказки. Пикер показывает её целиком и больше ничего — адрес отдельной
     * строкой в списке не рисуется. Поэтому у заведения к рубрике приклеивается адрес: без него
     * два бара с одинаковым названием в списке неразличимы.
     *
     * У дома и улицы адрес не приклеиваем: он почти повторяет [title] («Пограничный переулок,
     * 62» + «Россия, Ивановская область, Иваново, Пограничный переулок, 62»), и строка
     * превращается в кашу. Там своего subtitle («Иваново, Ивановская область») достаточно.
     */
    private fun buildSubtitle(kind: String, rubric: String, address: String): String {
        if (kind == KIND_BUSINESS && rubric.isNotEmpty() && address.isNotEmpty()) {
            // ВАЖНО: при print_address=1 Яндекс сам кладёт в subtitle связку «рубрика · адрес»,
            // и вторая склейка давала дубль: «Кальян-бар · Иваново, Пограничный переулок, 62 ·
            // Иваново, Пограничный переулок, 62» (поймано живым запросом 2026-08-10 — на
            // синтетической фикстуре, где subtitle был просто «Кальян-бар», баг не виден).
            // Документацией это поведение не закреплено, поэтому склеиваем, только если адреса
            // в рубрике ещё нет: код корректен при обоих ответах.
            if (address in rubric) return rubric
            return "$rubric$SUBTITLE_SEPARATOR$address"
        }
        return rubric.ifEmpty { address }
    }

    /**
     * Тип подсказки для фронтенда: по нему выбирается иконка и собирается текст места
     * (у заведения к адресу спереди приклеивается название, у адреса — нет).
     * Яндекс отдаёт `tags` списком, порядок в нём не гарантирован — проверяем вхождением.
     */
    private fun detectKind(tags: JsonNode): String {
        val values = tags.map { it.asText("") }
        return when {
            TAG_BUSINESS in values -> KIND_BUSINESS
            TAG_HOUSE in values -> KIND_HOUSE
            TAG_STREET in values -> KIND_STREET
            else -> KIND_OTHER
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        // Документированный endpoint продукта «API Геосаджеста» — отдельного от Геокодера.
        private const val SUGGEST_ENDPOINT = "https://suggest-maps.yandex.ru/v1/suggest"
        // Язык выдачи подсказок.
        private const val LANG = "ru_RU"
        // Сколько подсказок просим: столько влезает в шторку списка без бесконечного скролла.
        private const val MAX_RESULTS = 10
        // Сторона рамки поиска в градусах, «ll ± spn/2». 0.6° ≈ 60 км — с запасом на пригород.
        private const val CITY_SPAN_DEGREES = "0.6,0.6"
        // Минимальная длина осмысленного запроса — как у геокодера.
        private const val MIN_QUERY_LEN = 3
        // Потолок длины запроса — защита от абсурдного тела.
        private const val MAX_QUERY_LEN = 200
        // Сколько символов тела ошибки писать в лог.
        private const val ERROR_BODY_LOG_LIMIT = 500
        // Сколько ждать соединения с Яндексом.
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        // Полный таймаут запроса: пикер не должен висеть в «Ищем…» дольше.
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)

        // Разделитель рубрики и адреса во второй строке подсказки (как в макете пикера).
        private const val SUBTITLE_SEPARATOR = " · "

        // Метки Яндекса в поле tags, по которым распознаётся тип подсказки.
        private const val TAG_BUSINESS = "business"
        private const val TAG_HOUSE = "house"
        private const val TAG_STREET = "street"

        // Значения поля kind в контракте /api/geo/suggest.
        private const val KIND_BUSINESS = "business"
        private const val KIND_HOUSE = "house"
        private const val KIND_STREET = "street"
        private const val KIND_OTHER = "other"
    }
}

/**
 * Геосаджест недоступен (сеть, таймаут, не-2xx, ключ не настроен, кривой ответ).
 * Наследник [GeocoderUnavailableException] намеренно: снаружи это ровно та же деградация
 * «поиск временно недоступен» с кодом 503, и существующий обработчик ловит подтип сам —
 * отдельная ветка в `GlobalExceptionHandler` не нужна, а в логах тип остаётся различимым.
 */
class SuggestUnavailableException(
    message: String,
    cause: Throwable? = null
) : GeocoderUnavailableException(message, cause)
