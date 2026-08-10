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
}
