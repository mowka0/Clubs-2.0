package com.clubs.event

// Текст кнопки-ссылки на точку события — единый для личных DM и живого закрепа чата.
const val OPEN_IN_YANDEX_MAPS_BUTTON = "🗺 Открыть в Яндекс.Картах"

/**
 * Отображаемое место события для текстов бота/закрепа (V58: и адрес, и уточнение опциональны):
 * «адрес (уточнение)» / адрес / уточнение / null — при null строка «📍 …» не рендерится вовсе.
 */
val Event.locationDisplay: String?
    get() = when {
        locationText != null && locationHint != null -> "$locationText ($locationHint)"
        locationText != null -> locationText
        else -> locationHint
    }

/**
 * Бесключевой deep-link «открыть точку в Яндекс.Картах»; null у события без гео-точки.
 * ⚠️ Порядок координат у Яндекса в pt — lon,lat. Зеркалит openMapUrl фронта (utils/yandexMaps.ts).
 */
val Event.yandexMapsUrl: String?
    get() {
        val lat = locationLat ?: return null
        val lon = locationLon ?: return null
        return "https://yandex.ru/maps/?pt=$lon,$lat&z=17"
    }
