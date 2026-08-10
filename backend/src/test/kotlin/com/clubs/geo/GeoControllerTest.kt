package com.clubs.geo

import com.clubs.common.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP-контракт `/api/geo`: коды ответов и форма тела. Это то, на что завязан пикер места, и
 * различия здесь смысловые, а не косметические — «ничего не нашли» (200 с пустым списком),
 * «не разрешилось» (204) и «сервис лежит» (503) рисуются пользователю по-разному.
 *
 * Контроллер поднимается standalone, БЕЗ Spring-контекста и Docker: контекстные тесты в проекте
 * тянут Testcontainers ради БД, а здесь база не нужна вовсе. Сервисы при этом настоящие —
 * подменён только HTTP-клиент (см. `FakeHttpClient.kt`), так что проверяется весь путь от
 * параметров запроса до JSON, включая обработчик исключений.
 */
class GeoControllerTest {

    private val objectMapper = ObjectMapper()

    private val REFERER = "https://77-42-23-177.sslip.io"

    /** Какой clubId дошёл до сужения по городу; null — контроллер город не спрашивал. */
    private var askedClubId: UUID? = null

    /** Что справочник ответит про город клуба. null = у клуба города нет, ищем без рамки. */
    private var cityCenter: CityCenter? = null

    private val cityCenters = object : CityCenterRepository {
        override fun findByClubId(clubId: UUID): CityCenter? {
            askedClubId = clubId
            return cityCenter
        }
    }

    private val geocoderService = GeocoderService("test-key", "", REFERER, objectMapper)
    private val suggestService = SuggestService("test-key", REFERER, cityCenters, objectMapper)

    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(GeoController(geocoderService, suggestService))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private val SUGGEST_BODY = """
        {"results":[{
          "title":{"text":"Old Friends"},
          "subtitle":{"text":"Кальян-бар"},
          "address":{"formatted_address":"Иваново, Пограничный переулок, 62"},
          "tags":["business"],
          "uri":"ymapsbm1://org?oid=1024394521"
        }]}
    """.trimIndent()

    private val GEOCODE_BODY = """
        {"response":{"GeoObjectCollection":{"featureMember":[{"GeoObject":{
          "metaDataProperty":{"GeocoderMetaData":{"text":"Россия, Иваново, Old Friends"}},
          "Point":{"pos":"40.972935 57.012830"}
        }}]}}}
    """.trimIndent()

    private val EMPTY_SUGGEST = """{"results":[]}"""
    private val EMPTY_GEOCODE = """{"response":{"GeoObjectCollection":{"featureMember":[]}}}"""

    // ── /api/geo/suggest ───────────────────────────────────────────────────────────────

    @Test
    fun `подсказки приходят объектом с полем results`() {
        installFakeHttp(suggestService, listOf(ok(SUGGEST_BODY)))

        mockMvc.perform(get("/api/geo/suggest").param("q", "Иваново, Old Friends"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.results[0].title").value("Old Friends"))
            // Вторая строка списка: рубрика плюс адрес, иначе тёзок не различить.
            .andExpect(jsonPath("\$.results[0].subtitle").value("Кальян-бар · Иваново, Пограничный переулок, 62"))
            .andExpect(jsonPath("\$.results[0].address").value("Иваново, Пограничный переулок, 62"))
            .andExpect(jsonPath("\$.results[0].kind").value("business"))
            .andExpect(jsonPath("\$.results[0].uri").value("ymapsbm1://org?oid=1024394521"))
    }

    @Test
    fun `ничего не нашли — это 200 с пустым списком, а не 204`() {
        // У списочного эндпоинта пустой результат валиден: клиенту не приходится разбирать
        // пустое тело, чтобы отличить «нет подсказок» от «сервис не ответил».
        installFakeHttp(suggestService, listOf(ok(EMPTY_SUGGEST)))

        mockMvc.perform(get("/api/geo/suggest").param("q", "Блаблабла Пыщь"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.results").isArray)
            .andExpect(jsonPath("\$.results").isEmpty)
    }

    @Test
    fun `clubId доезжает до сужения по городу`() {
        val clubId = UUID.randomUUID()
        cityCenter = CityCenter(lat = 57.012830, lon = 40.972935)
        val http = installFakeHttp(suggestService, listOf(ok(SUGGEST_BODY)))

        mockMvc.perform(
            get("/api/geo/suggest")
                .param("q", "Old Friends")
                .param("clubId", clubId.toString())
        ).andExpect(status().isOk)

        assertEquals(clubId, askedClubId, "UUID из параметра запроса обязан доехать до справочника")
        assertTrue(http.urlOf(0).contains("strict_bounds=1"), "Поиск не сужен: ${http.urlOf(0)}")
    }

    @Test
    fun `без clubId город не спрашивается и рамки нет`() {
        val http = installFakeHttp(suggestService, listOf(ok(SUGGEST_BODY)))

        mockMvc.perform(get("/api/geo/suggest").param("q", "Old Friends")).andExpect(status().isOk)

        assertNull(askedClubId, "Клуб не передан — ходить в справочник городов не за чем")
        assertTrue(!http.urlOf(0).contains("&ll="), "Лишняя рамка: ${http.urlOf(0)}")
    }

    @Test
    fun `слишком короткий запрос — 400, а не поход в Яндекс`() {
        val http = installFakeHttp(suggestService, emptyList())

        mockMvc.perform(get("/api/geo/suggest").param("q", "ба"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("\$.error").value("VALIDATION_ERROR"))

        assertEquals(0, http.requestCount, "Короткий запрос обязан отсекаться до сети")
    }

    @Test
    fun `Геосаджест лежит — 503, и пикер оставляет пин рабочим`() {
        installFakeHttp(suggestService, listOf(failed(403, """{"message":"Invalid referer"}""")))

        mockMvc.perform(get("/api/geo/suggest").param("q", "бар"))
            .andExpect(status().isServiceUnavailable)
            // Подтип SuggestUnavailableException ловится обработчиком геокодера — отдельной
            // ветки в GlobalExceptionHandler для саджеста нет.
            .andExpect(jsonPath("\$.error").value("GEOCODER_UNAVAILABLE"))
    }

    // ── /api/geo/resolve ───────────────────────────────────────────────────────────────

    @Test
    fun `resolve отдаёт координаты выбранной подсказки`() {
        installFakeHttp(geocoderService, listOf(ok(GEOCODE_BODY)))

        mockMvc.perform(get("/api/geo/resolve").param("uri", "ymapsbm1://org?oid=1024394521"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("\$.lat").value(57.012830))
            .andExpect(jsonPath("\$.lon").value(40.972935))
            .andExpect(jsonPath("\$.address").value("Россия, Иваново, Old Friends"))
    }

    @Test
    fun `uri не разрешился — 204 без тела`() {
        installFakeHttp(geocoderService, listOf(ok(EMPTY_GEOCODE)))

        mockMvc.perform(get("/api/geo/resolve").param("uri", "ymapsbm1://org?oid=404"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `пустой uri — 400 до похода в сеть`() {
        val http = installFakeHttp(geocoderService, emptyList())

        mockMvc.perform(get("/api/geo/resolve").param("uri", " "))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("\$.error").value("VALIDATION_ERROR"))

        assertEquals(0, http.requestCount)
    }

    @Test
    fun `геокодер лежит — resolve отвечает 503`() {
        installFakeHttp(geocoderService, listOf(networkFailure()))

        mockMvc.perform(get("/api/geo/resolve").param("uri", "ymapsbm1://org?oid=1"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("\$.error").value("GEOCODER_UNAVAILABLE"))
    }

    // ── /api/geo/geocode ───────────────────────────────────────────────────────────────

    @Test
    fun `у поиска адреса 204 сохраняется — там ответ единственный объект`() {
        // Регрессия: /suggest перешёл на «пустой список = 200», а /geocode обязан остаться
        // с 204 — клиент по нему показывает «уточните запрос».
        installFakeHttp(geocoderService, listOf(ok(EMPTY_GEOCODE)))

        mockMvc.perform(get("/api/geo/geocode").param("q", "Блаблабла Пыщь"))
            .andExpect(status().isNoContent)
    }
}
