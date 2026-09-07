package com.clubs.common.util

/**
 * True только для картинки, произведённой НАШИМ загрузчиком (`POST /api/upload`):
 * «{s3.base-url}/uploads/{uuid}.{ext}» — base-url в проде пуст, поэтому URL root-relative
 * «/uploads/…». Функция отрезает ровно настроенный origin и проверяет, что остаток — путь вида
 * `uploads/<имя>.<расширение картинки>`.
 *
 * Так блокируются `javascript:` / `data:`-URL и произвольные внешние хосты (например
 * `evil.com/uploads/x.png`): пользовательское «доказательство» (скриншот оплаты взноса, чек по
 * складчине) открывается организатором как кликабельная ссылка, поэтому оно обязано приходить
 * из нашего хранилища, а не с чужого домена.
 */
fun isUploadedImageUrl(url: String, storageBaseUrl: String): Boolean {
    val prefix = storageBaseUrl.trimEnd('/')
    val relative = when {
        prefix.isNotEmpty() && url.startsWith("$prefix/") -> url.removePrefix("$prefix/")
        prefix.isEmpty() && url.startsWith("/") -> url.removePrefix("/")
        else -> return false
    }
    return UPLOADS_PATH.matches(relative)
}

// Расширения обязаны совпадать с CONTENT_TYPE_TO_EXT в StorageController: формат, который
// загрузчик принял и сохранил, не должен отвергаться при попытке сослаться на него.
private val UPLOADS_PATH = Regex("^uploads/[\\w.-]+\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
