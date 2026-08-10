package com.clubs.geo

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Результат прямого геокодинга: человеческий адрес + точка (порядок полей привычный, lat/lon). */
data class GeocodeResultDto(
    val address: String,
    val lat: Double,
    val lon: Double
)

/**
 * Одна подсказка Геосаджеста. Координат тут нет — саджест их не отдаёт, за точкой клиент идёт
 * в `/api/geo/resolve` с этим же [uri].
 */
data class SuggestItemDto(
    /** Название заведения или адресной точки: «Old Friends», «Пограничный переулок, 62». */
    val title: String,
    /**
     * Вторая строка списка: «Кальян-бар · Иваново, Пограничный переулок, 62». Именно её целиком
     * показывает пикер, поэтому у заведения к рубрике приклеен адрес — иначе два бара с одним
     * названием в списке неразличимы. Пусто = Яндекс не дал ни рубрики, ни адреса.
     */
    val subtitle: String,
    /** Почтовый адрес объекта — из него собирается текст места. Пусто = Яндекс адреса не дал. */
    val address: String,
    /** business | house | street | other — по нему клиент собирает текст места и иконку. */
    val kind: String,
    /** Непрозрачный идентификатор объекта у Яндекса. Клиент возвращает его как есть. */
    val uri: String
)

/** Обёртка списка: у списочного эндпоинта тело всегда объект — чтобы поля можно было добавить. */
data class SuggestResponseDto(
    val results: List<SuggestItemDto>
)

@RestController
@RequestMapping("/api/geo")
class GeoController(
    private val geocoderService: GeocoderService,
    private val suggestService: SuggestService
) {

    /**
     * Поиск адреса для пикера места события. Под JWT и общим rate-limit, как весь остальной /api.
     *
     * `204 No Content` = адрес не найден (пользователю «уточните запрос»), `503` = геокодер
     * недоступен («попробуйте позже»). Раньше это различие делал фронтенд по HTTP-коду Яндекса,
     * ходя к нему напрямую с публичным ключом; теперь ключ живёт только на сервере.
     */
    @GetMapping("/geocode")
    fun geocode(@RequestParam q: String): ResponseEntity<GeocodeResultDto> {
        val result = geocoderService.geocode(q) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(result)
    }

    /**
     * Подсказки заведений и адресов по строке. `clubId` не обязателен: с ним поиск сужается
     * до города клуба, без него ищем по всей карте.
     *
     * Пустой список — это `200`, а не `204`: для списочного эндпоинта «ничего не нашли» —
     * валидный результат, и клиенту не приходится разбирать пустое тело. `204` осталось
     * только у `/geocode`, где ответ — единственный объект.
     */
    @GetMapping("/suggest")
    fun suggest(
        @RequestParam q: String,
        @RequestParam(required = false) clubId: UUID?
    ): SuggestResponseDto = SuggestResponseDto(results = suggestService.suggest(q, clubId))

    /**
     * Координаты выбранной подсказки. Отдельный эндпоинт, а не параметр у `/geocode`: там `q` —
     * пользовательская строка, здесь `uri` — непрозрачный идентификатор Яндекса, и смешивать
     * их в одной валидации значит плодить взаимоисключающие ветки.
     *
     * `204` = uri не разрешился (клиент оставляет пин там, где он был), `503` = геокодер лежит.
     */
    @GetMapping("/resolve")
    fun resolve(@RequestParam uri: String): ResponseEntity<GeocodeResultDto> {
        val result = geocoderService.resolveUri(uri) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(result)
    }
}
