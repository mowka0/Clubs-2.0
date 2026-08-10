package com.clubs.geo

import com.clubs.common.exception.ValidationException
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Разбор ответа Геосаджеста, сборка запроса и режим «ключ не настроен». Сеть здесь не гоняется —
 * живые сценарии проверяются на staging; тут важно, что мёртвые подсказки отсеиваются, рамка
 * города собирается корректно и недоступность не маскируется под «ничего не нашли».
 */
class SuggestServiceTest {

    private val objectMapper = ObjectMapper()

    private val REFERER = "https://77-42-23-177.sslip.io"

    /** Город не нужен большинству проверок: рамку в них передаём напрямую. */
    private val noCityCenters = object : CityCenterRepository {
        override fun findByClubId(clubId: UUID): CityCenter? = null
    }

    /** Клуб с городом: центр один и тот же для любого clubId — какой именно, тестам не важно. */
    private fun cityCenters(center: CityCenter) = object : CityCenterRepository {
        override fun findByClubId(clubId: UUID): CityCenter = center
    }

    private fun service(
        apiKey: String = "test-key",
        cityCenterRepository: CityCenterRepository = noCityCenters
    ) = SuggestService(apiKey, REFERER, cityCenterRepository, objectMapper)

    @Test
    fun `пустой ключ — недоступность, а не пустой список`() {
        // Пустой список значит «ничего не нашли»; ненастроенный ключ обязан отвечать 503,
        // иначе поломку конфигурации никто не заметит.
        assertFailsWith<SuggestUnavailableException> { service(apiKey = "").suggest("Иваново, бар", null) }
    }

    @Test
    fun `недоступность саджеста мапится в тот же 503, что и у геокодера`() {
        // GlobalExceptionHandler ловит GeocoderUnavailableException и отдаёт 503 — подтип обязан
        // попадать в тот же обработчик, отдельной ветки для саджеста нет.
        val e = assertFailsWith<GeocoderUnavailableException> {
            service(apiKey = "").suggest("Иваново, бар", null)
        }
        assertEquals(SuggestUnavailableException::class, e::class)
    }

    @Test
    fun `слишком короткий запрос отклоняется до похода в сеть`() {
        val e = assertFailsWith<ValidationException> { service().suggest("ба", null) }
        assertEquals("Запрос слишком короткий", e.message)
    }

    @Test
    fun `слишком длинный запрос отклоняется до похода в сеть`() {
        assertFailsWith<ValidationException> { service().suggest("а".repeat(201), null) }
    }

    // ── разбор ответа ──────────────────────────────────────────────────────────────────

    private fun parse(body: String): List<SuggestItemDto> {
        val m = SuggestService::class.java.getDeclaredMethod("parseSuggestions", String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(service(), body) as List<SuggestItemDto>
    }

    @Test
    fun `у заведения адрес приклеен к рубрике — иначе тёзок не различить`() {
        val body = """
            {"results":[{
              "title":{"text":"Old Friends"},
              "subtitle":{"text":"Кальян-бар"},
              "address":{"formatted_address":"Иваново, Пограничный переулок, 62"},
              "tags":["business"],
              "uri":"ymapsbm1://org?oid=1024394521"
            }]}
        """.trimIndent()

        val item = parse(body).single()
        assertEquals("Old Friends", item.title)
        assertEquals("Кальян-бар · Иваново, Пограничный переулок, 62", item.subtitle)
        // Адрес остаётся отдельным полем: из него собирается текст места события.
        assertEquals("Иваново, Пограничный переулок, 62", item.address)
        assertEquals("business", item.kind)
    }

    @Test
    fun `адрес не дублируется, если Яндекс уже склеил его с рубрикой`() {
        // Фактический ответ боевого API при print_address=1: subtitle приходит уже в виде
        // «рубрика · адрес». Слепая склейка давала «Кальян-бар · <адрес> · <адрес>» — баг
        // пойман живым запросом 2026-08-10, синтетическая фикстура его не показывала.
        val body = """
            {"results":[{
              "title":{"text":"Old Friends"},
              "subtitle":{"text":"Кальян-бар · Иваново, Пограничный переулок, 62"},
              "address":{"formatted_address":"Иваново, Пограничный переулок, 62"},
              "tags":["business"],
              "uri":"ymapsbm1://org?oid=1024394521"
            }]}
        """.trimIndent()

        val item = parse(body).single()
        assertEquals("Кальян-бар · Иваново, Пограничный переулок, 62", item.subtitle)
        // Адрес отдельным полем не страдает — из него собирается текст места события.
        assertEquals("Иваново, Пограничный переулок, 62", item.address)
    }

    @Test
    fun `у дома адрес к subtitle не клеится — он повторил бы заголовок`() {
        val body = """
            {"results":[{
              "title":{"text":"Пограничный переулок, 62"},
              "subtitle":{"text":"Иваново, Ивановская область"},
              "address":{"formatted_address":"Россия, Ивановская область, Иваново, Пограничный переулок, 62"},
              "tags":["house"],
              "uri":"ymapsbm1://geo?ll=40.9,57.0"
            }]}
        """.trimIndent()

        val item = parse(body).single()
        assertEquals("Иваново, Ивановская область", item.subtitle)
        assertEquals("house", item.kind)
    }

    @Test
    fun `подсказка без uri отбрасывается`() {
        // Без uri координату не достать — строка в списке была бы мёртвой.
        val body = """
            {"results":[
              {"title":{"text":"Без ссылки"},"tags":["business"]},
              {"title":{"text":"С ссылкой"},"tags":["street"],"uri":"ymapsbm1://geo?x=1"}
            ]}
        """.trimIndent()

        val items = parse(body)
        assertEquals(1, items.size)
        assertEquals("С ссылкой", items.single().title)
        assertEquals("street", items.single().kind)
    }

    @Test
    fun `незнакомая метка превращается в other, а не роняет разбор`() {
        val body = """
            {"results":[{"title":{"text":"Станция"},"tags":["metro"],"uri":"ymapsbm1://geo?x=2"}]}
        """.trimIndent()
        assertEquals("other", parse(body).single().kind)
    }

    @Test
    fun `пустая выдача — пустой список, а не ошибка`() {
        assertEquals(emptyList(), parse("""{"results":[]}"""))
    }

    @Test
    fun `нечитаемое тело — недоступность, а не NPE`() {
        val e = assertFailsWith<java.lang.reflect.InvocationTargetException> { parse("не json") }
        assertTrue(
            e.cause is SuggestUnavailableException,
            "Ожидали SuggestUnavailableException, получили ${e.cause}"
        )
    }

    // ── сборка запроса ─────────────────────────────────────────────────────────────────

    private fun buildUrl(center: CityCenter?): String {
        val m = SuggestService::class.java
            .getDeclaredMethod("buildUrl", String::class.java, CityCenter::class.java)
        m.isAccessible = true
        return (m.invoke(service(), "бар", center) as URI).toString()
    }

    @Test
    fun `рамка города — это ll+spn+strict_bounds, координаты в порядке lon,lat`() {
        val url = buildUrl(CityCenter(lat = 57.012830, lon = 40.972935))
        // У Яндекса первой идёт долгота — перепутать значит искать в другой точке планеты.
        assertTrue(url.contains("&ll=40.972935,57.012830"), "Нет корректного ll: $url")
        assertTrue(url.contains("&spn=0.6,0.6"), "Нет spn: $url")
        // Без strict_bounds ll+spn — лишь мягкое смещение: «антикафе» тянуло Астану и Волгоград.
        assertTrue(url.contains("&strict_bounds=1"), "Нет строгой рамки: $url")
    }

    @Test
    fun `русская локаль не ломает разделитель координат`() {
        // Под ru-RU формат по умолчанию дал бы «40,972935» — и Яндекс ответил бы 400,
        // потому что запятая у него разделяет саму пару координат.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            assertTrue(buildUrl(CityCenter(lat = 57.0, lon = 40.5)).contains("&ll=40.500000,57.000000"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `без города рамки нет — иначе загородная база отдыха не найдётся`() {
        val url = buildUrl(null)
        assertTrue(!url.contains("&ll="), "Рамка не должна появляться без города: $url")
        assertTrue(!url.contains("strict_bounds"), "Рамка не должна появляться без города: $url")
        // Заведения и адреса — одним списком, и uri обязателен: без него строка бесполезна.
        assertTrue(url.contains("&types=biz,geo"), "Потерян types: $url")
        assertTrue(url.contains("&attrs=uri"), "Потерян attrs=uri: $url")
    }

    // ── поход в сеть ───────────────────────────────────────────────────────────────────
    // HTTP-клиент подменяется фейком (FakeHttpClient.kt): проверяется не разбор тела, а то,
    // СКОЛЬКО запросов уходит и что происходит с ответом Яндекса, кроме счастливого случая.

    /** Иваново — оно же в примерах спеки. Конкретные цифры важны только для проверки ll. */
    private val IVANOVO = CityCenter(lat = 57.012830, lon = 40.972935)

    /** Одна подсказка-заведение в формате Геосаджеста. */
    private fun businessBody(title: String) = """
        {"results":[{
          "title":{"text":"$title"},
          "subtitle":{"text":"Кальян-бар"},
          "address":{"formatted_address":"Иваново, Пограничный переулок, 62"},
          "tags":["business"],
          "uri":"ymapsbm1://org?oid=1024394521"
        }]}
    """.trimIndent()

    private val EMPTY_BODY = """{"results":[]}"""

    @Test
    fun `нормальный ответ Геосаджеста превращается в список подсказок за один запрос`() {
        val svc = service()
        val http = installFakeHttp(
            svc,
            listOf(
                ok(
                    """
                    {"results":[
                      {"title":{"text":"Old Friends"},"subtitle":{"text":"Кальян-бар"},
                       "address":{"formatted_address":"Иваново, Пограничный переулок, 62"},
                       "tags":["business"],"uri":"ymapsbm1://org?oid=1"},
                      {"title":{"text":"Пограничный переулок"},"subtitle":{"text":"Иваново"},
                       "address":{"formatted_address":"Россия, Иваново, Пограничный переулок"},
                       "tags":["street"],"uri":"ymapsbm1://geo?ll=40.9,57.0"}
                    ]}
                    """.trimIndent()
                )
            )
        )

        val items = svc.suggest("Иваново, Old Friends", null)

        assertEquals(2, items.size)
        // Заведения и адреса приходят одним списком — фронтенд их не разделяет, только рисует.
        assertEquals(listOf("business", "street"), items.map { it.kind })
        assertEquals(1, http.requestCount, "Один поиск = один запрос: квота 1000/сутки")
        // Referer обязателен: ключ Яндекса ограничен белым списком доменов, без заголовка — 403.
        assertEquals(REFERER, http.headerOf(0, "Referer"))
        assertTrue(http.urlOf(0).contains("apikey=test-key"), "Ключ не ушёл: ${http.urlOf(0)}")
        assertTrue(http.urlOf(0).contains("&text="), "Запрос не ушёл: ${http.urlOf(0)}")
    }

    @Test
    fun `пусто со строгой рамкой — ровно один повтор без рамки`() {
        val svc = service(cityCenterRepository = cityCenters(IVANOVO))
        val http = installFakeHttp(svc, listOf(ok(EMPTY_BODY), ok(businessBody("База отдыха"))))

        val items = svc.suggest("база отдыха", UUID.randomUUID())

        // Иначе загородное место или клуб на окраине превращались бы в «ничего не нашли».
        assertEquals("База отдыха", items.single().title)
        assertEquals(2, http.requestCount, "Ожидали запрос с рамкой и ровно один повтор без неё")
        assertTrue(http.urlOf(0).contains("strict_bounds=1"), "Первый запрос обязан быть строгим")
        assertTrue(!http.urlOf(1).contains("strict_bounds"), "Повтор обязан идти БЕЗ рамки")
        assertTrue(!http.urlOf(1).contains("&ll="), "Повтор обязан идти БЕЗ центра города")
    }

    @Test
    fun `нашлось внутри города — повтора нет`() {
        // Заготовка ровно одна: второй запрос уронил бы тест как «лишний запрос в Яндекс».
        val svc = service(cityCenterRepository = cityCenters(IVANOVO))
        val http = installFakeHttp(svc, listOf(ok(businessBody("Old Friends"))))

        assertEquals("Old Friends", svc.suggest("Old Friends", UUID.randomUUID()).single().title)
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `пустой повтор — пустой список, а не третий заход`() {
        val svc = service(cityCenterRepository = cityCenters(IVANOVO))
        val http = installFakeHttp(svc, listOf(ok(EMPTY_BODY), ok(EMPTY_BODY)))

        assertEquals(emptyList(), svc.suggest("Блаблабла Пыщь", UUID.randomUUID()))
        assertEquals(2, http.requestCount, "Повтор ровно один: дальше честно «ничего не нашли»")
    }

    @Test
    fun `без клуба повтора нет — рамки и так не было`() {
        val svc = service()
        val http = installFakeHttp(svc, listOf(ok(EMPTY_BODY)))

        assertEquals(emptyList(), svc.suggest("Блаблабла Пыщь", null))
        assertEquals(1, http.requestCount, "Повторять нечего: первый запрос уже был без рамки")
    }

    @Test
    fun `не-2xx от Яндекса — недоступность, и повтора не будет`() {
        // 403 приходит и на «домен не разрешён», и на исчерпанный лимит — оба случая не лечатся
        // повтором, а пустым списком их маскировать нельзя: пользователю нужен другой текст.
        val svc = service(cityCenterRepository = cityCenters(IVANOVO))
        val http = installFakeHttp(svc, listOf(failed(403, """{"message":"Invalid referer"}""")))

        assertFailsWith<SuggestUnavailableException> { svc.suggest("бар", UUID.randomUUID()) }
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `обрыв сети — недоступность, а не пустой список`() {
        val svc = service()
        installFakeHttp(svc, listOf(networkFailure()))

        assertFailsWith<SuggestUnavailableException> { svc.suggest("бар", null) }
    }

    @Test
    fun `кривое тело по сети — недоступность, а не пустой список`() {
        val svc = service()
        installFakeHttp(svc, listOf(ok("<html>502 Bad Gateway</html>")))

        assertFailsWith<SuggestUnavailableException> { svc.suggest("бар", null) }
    }
}
