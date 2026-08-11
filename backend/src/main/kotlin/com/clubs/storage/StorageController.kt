package com.clubs.storage

import com.clubs.common.exception.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
class StorageController(
    private val storageService: StorageService
) {

    private val log = LoggerFactory.getLogger(StorageController::class.java)

    companion object {
        // Потолок размера загружаемой картинки: 5 МБ.
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5 MB

        /**
         * Белый список форматов. WebP добавлен 2026-08-11: телефоны и мессенджеры отдают его
         * всё чаще (скриншоты Android, пересланные из веба картинки), и пользователь не понимал,
         * почему обычная с виду картинка «не та». Список обязан совпадать с IMAGE_ALLOWED_MIMES
         * во `frontend/src/utils/imageUpload.ts` — иначе файл проходит выбор и падает на 400.
         */
        private val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        // Расширение файла в хранилище по MIME: имя объекта генерируем сами, полагаться на
        // originalFilename нельзя (он от клиента и может быть каким угодно).
        private val CONTENT_TYPE_TO_EXT = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp"
        )
    }

    @PostMapping("/api/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): ResponseEntity<UploadResponseDto> {
        val contentType = file.contentType
            ?: throw ValidationException("Content type is required")

        if (contentType !in ALLOWED_CONTENT_TYPES) {
            throw ValidationException("Only JPEG, PNG and WebP images are allowed, got: $contentType")
        }

        if (file.size > MAX_FILE_SIZE) {
            throw ValidationException("File size must not exceed 5 MB")
        }

        val ext = CONTENT_TYPE_TO_EXT[contentType]
            ?: throw ValidationException("Unsupported file type")

        val path = "uploads/${UUID.randomUUID()}.$ext"
        log.info("Upload file: contentType={} size={} path={}", contentType, file.size, path)
        val url = storageService.uploadFile(file.bytes, path, contentType)

        return ResponseEntity.ok(UploadResponseDto(url))
    }
}
