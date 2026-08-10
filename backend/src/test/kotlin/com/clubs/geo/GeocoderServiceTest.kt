package com.clubs.geo

import com.clubs.common.exception.ValidationException
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор ответа геокодера и режим «ключ не настроен». Сетевые сценарии (403/таймаут) здесь
 * не гоняются — они проверяются на staging живым запросом; тут важно, что сервис не падает
 * молча и различает «не найдено» и «недоступен».
 */
class GeocoderServiceTest {

    private val objectMapper = ObjectMapper()

    // Referer берётся из отдельной настройки, а не из webapp-base-url: на проде там технический
    // домен Coolify, которого нет в белом списке ключа Яндекса (инцидент 2026-08-10).
    private val REFERER = "https://77-42-23-177.sslip.io"

    private fun service(apiKey: String = "test-key", fallbackKey: String = "") =
        GeocoderService(apiKey, fallbackKey, REFERER, objectMapper)

    @Test
    fun `пустой серверный ключ подхватывает фолбэк, а не ложится`() {
        // На проде YANDEX_GEOCODER_API_KEY объявлена, но пустая, а рабочий ключ лежит под
        // VITE_-именем: раньше это давало 503 на ровном месте.
        val svc = service(apiKey = "", fallbackKey = "legacy-key")
        // Ключ есть → до сети дойдёт (упадёт уже на сетевом вызове, а не на «не настроен»).
        val e = assertFailsWith<GeocoderUnavailableException> { svc.geocode("Москва, Тверская 1") }
        assertTrue(
            e.message?.contains("не настроен") != true,
            "Ключ из фолбэка обязан использоваться, получили: ${e.message}"
        )
    }

    @Test
    fun `пустой ключ — недоступность, а не тихое молчание`() {
        // Клуб создать всё ещё можно (место ставится пином), но поиск обязан честно сказать 503.
        assertFailsWith<GeocoderUnavailableException> { service(apiKey = "").geocode("Москва") }
    }

    @Test
    fun `слишком короткий запрос отклоняется до похода в сеть`() {
        // Граница включающая: 3 символа («дом») — уже валидный запрос, отклоняются 2 и меньше.
        val e = assertFailsWith<ValidationException> { service().geocode("ул") }
        assertEquals("Запрос слишком короткий", e.message)
    }

    @Test
    fun `слишком длинный запрос отклоняется до похода в сеть`() {
        assertFailsWith<ValidationException> { service().geocode("а".repeat(201)) }
    }

    // ── разбор ответа ──────────────────────────────────────────────────────────────────
    // parseFirstResult приватный, поэтому проверяем через рефлексию: это единственная
    // нетривиальная логика класса (порядок координат у Яндекса обратный — «lon lat»).

    private fun parse(body: String): GeocodeResultDto? {
        val m = GeocoderService::class.java.getDeclaredMethod("parseFirstResult", String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(service(), body) as GeocodeResultDto?
    }

    @Test
    fun `порядок координат Яндекса «lon lat» разворачивается правильно`() {
        val body = """
            {"response":{"GeoObjectCollection":{"featureMember":[{"GeoObject":{
              "metaDataProperty":{"GeocoderMetaData":{"text":"Россия, Иваново, Палехская улица, 13"}},
              "Point":{"pos":"40.973 56.999"}
            }}]}}}
        """.trimIndent()

        val result = parse(body)!!
        assertEquals("Россия, Иваново, Палехская улица, 13", result.address)
        // Первое число у Яндекса — долгота, второе — широта. Перепутать = поставить точку в океан.
        assertEquals(56.999, result.lat)
        assertEquals(40.973, result.lon)
    }

    @Test
    fun `пустая выдача — это null, а не ошибка`() {
        assertNull(parse("""{"response":{"GeoObjectCollection":{"featureMember":[]}}}"""))
    }

    @Test
    fun `объект без координат не роняет разбор`() {
        val body = """
            {"response":{"GeoObjectCollection":{"featureMember":[{"GeoObject":{
              "metaDataProperty":{"GeocoderMetaData":{"text":"Где-то"}}
            }}]}}}
        """.trimIndent()
        assertNull(parse(body))
    }

    @Test
    fun `нечитаемое тело — недоступность, а не NPE`() {
        // Вызов идёт через рефлексию, поэтому настоящая причина лежит под InvocationTargetException.
        val e = assertFailsWith<java.lang.reflect.InvocationTargetException> { parse("не json") }
        assertTrue(
            e.cause is GeocoderUnavailableException,
            "Ожидали GeocoderUnavailableException, получили ${e.cause}"
        )
    }

    // ── координаты по uri подсказки ────────────────────────────────────────────────────
    // Геосаджест координат не отдаёт принципиально, поэтому выбор места в пикере стоит второго
    // запроса — сюда. HTTP-клиент подменяется фейком (FakeHttpClient.kt), сеть не гоняется.

    /** Ответ геокодера с одной точкой — форма та же, что у поиска по адресу. */
    private val ONE_POINT_BODY = """
        {"response":{"GeoObjectCollection":{"featureMember":[{"GeoObject":{
          "metaDataProperty":{"GeocoderMetaData":{"text":"Россия, Иваново, Old Friends"}},
          "Point":{"pos":"40.972935 57.012830"}
        }}]}}}
    """.trimIndent()

    @Test
    fun `uri уходит своим параметром, а не подставляется в geocode`() {
        val svc = service()
        val http = installFakeHttp(svc, listOf(ok(ONE_POINT_BODY)))

        val result = svc.resolveUri("ymapsbm1://org?oid=1024394521")!!

        assertEquals(57.012830, result.lat)
        assertEquals(40.972935, result.lon)
        // Идентификатор непрозрачный: уедь он в geocode= — Яндекс искал бы адрес по строке
        // «ymapsbm1://…» и не нашёл бы ничего.
        assertTrue(http.urlOf(0).contains("&uri=ymapsbm1"), "uri не ушёл: ${http.urlOf(0)}")
        assertTrue(!http.urlOf(0).contains("geocode="), "Лишний geocode=: ${http.urlOf(0)}")
        assertEquals(REFERER, http.headerOf(0, "Referer"))
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `uri не разрешился — null, то есть 204, а не ошибка`() {
        // Клиент по 204 оставляет пин там, где он стоял; ошибку показывать не за что.
        val svc = service()
        installFakeHttp(svc, listOf(ok("""{"response":{"GeoObjectCollection":{"featureMember":[]}}}""")))

        assertNull(svc.resolveUri("ymapsbm1://org?oid=404"))
    }

    @Test
    fun `не-2xx на resolve — недоступность`() {
        val svc = service()
        installFakeHttp(svc, listOf(failed(403, """{"message":"Invalid referer"}""")))

        assertFailsWith<GeocoderUnavailableException> { svc.resolveUri("ymapsbm1://org?oid=1") }
    }

    @Test
    fun `обрыв сети на resolve — недоступность`() {
        val svc = service()
        installFakeHttp(svc, listOf(networkFailure()))

        assertFailsWith<GeocoderUnavailableException> { svc.resolveUri("ymapsbm1://org?oid=1") }
    }

    @Test
    fun `пустой uri отклоняется до похода в сеть`() {
        val e = assertFailsWith<ValidationException> { service().resolveUri("   ") }
        assertEquals("Идентификатор места пуст", e.message)
    }

    @Test
    fun `слишком длинный uri отклоняется до похода в сеть`() {
        // Потолок 512 символов — защита от мусора в параметре: реальные ymapsbm1://-идентификаторы
        // умещаются в десятки символов.
        assertFailsWith<ValidationException> { service().resolveUri("ymapsbm1://" + "x".repeat(512)) }
    }

    @Test
    fun `ключ не настроен — resolve тоже отвечает недоступностью`() {
        assertFailsWith<GeocoderUnavailableException> {
            service(apiKey = "").resolveUri("ymapsbm1://org?oid=1")
        }
    }
}
