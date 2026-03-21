package com.clubs.storage

import com.clubs.common.exception.ValidationException
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

    companion object {
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5 MB
        private val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png")
        private val CONTENT_TYPE_TO_EXT = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png"
        )
    }

    @PostMapping("/api/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): ResponseEntity<UploadResponseDto> {
        val contentType = file.contentType
            ?: throw ValidationException("Content type is required")

        if (contentType !in ALLOWED_CONTENT_TYPES) {
            throw ValidationException("Only JPEG and PNG images are allowed, got: $contentType")
        }

        if (file.size > MAX_FILE_SIZE) {
            throw ValidationException("File size must not exceed 5 MB")
        }

        val ext = CONTENT_TYPE_TO_EXT[contentType]
            ?: throw ValidationException("Unsupported file type")

        val path = "uploads/${UUID.randomUUID()}.$ext"
        val url = storageService.uploadFile(file.bytes, path, contentType)

        return ResponseEntity.ok(UploadResponseDto(url))
    }
}
