package com.clubs.common.util

/**
 * Проверка, что URL картинки произведён НАШИМ загрузчиком: `{s3.base-url}/uploads/{имя}.{jpg|jpeg|png}`.
 * base-url в проде пуст, поэтому URL root-relative «/uploads/…». Отрезает ровно настроенный origin и
 * проверяет остаток — это блокирует javascript:/data: URL и посторонние хосты (evil.com/uploads/x.png)
 * от попадания в кликабельную ссылку получателя: чек и скриншот оплаты должны приходить из нашего
 * хранилища. Общее для чека к долгу и скриншота взноса (AccessGateService).
 */
object UploadedImageUrls {

    private val UPLOADS_PATH = Regex("^uploads/[\\w.-]+\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE)

    fun isUploadedImageUrl(url: String, storageBaseUrl: String): Boolean {
        val prefix = storageBaseUrl.trimEnd('/')
        val relative = when {
            prefix.isNotEmpty() && url.startsWith("$prefix/") -> url.removePrefix("$prefix/")
            prefix.isEmpty() && url.startsWith("/") -> url.removePrefix("/")
            else -> return false
        }
        return UPLOADS_PATH.matches(relative)
    }
}
