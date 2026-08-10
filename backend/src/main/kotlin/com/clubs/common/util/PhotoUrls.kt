package com.clubs.common.util

/**
 * Абсолютный URL картинки для Telegram: photo_url хранится относительным («/uploads/…», когда
 * S3_BASE_URL не задан и фронтовый nginx проксирует на MinIO), а картинку скачивают серверы
 * Telegram — относительный путь они не поймут. Общий для личных DM и постов в чат клуба.
 */
fun absolutePhotoUrl(photoUrl: String, webAppBaseUrl: String): String =
    if (photoUrl.startsWith("http")) photoUrl else "$webAppBaseUrl$photoUrl"
