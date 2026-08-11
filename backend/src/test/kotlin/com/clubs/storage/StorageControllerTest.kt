package com.clubs.storage

import com.clubs.common.exception.ValidationException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockMultipartFile

/**
 * Белый список форматов `/api/upload`. Тесты стерегут не только «webp принимается», но и
 * зеркальность фронту: список здесь обязан совпадать с IMAGE_ALLOWED_MIMES в
 * `frontend/src/utils/imageUpload.ts`, иначе файл проходит выбор и падает на 400.
 */
class StorageControllerTest {

    private lateinit var storageService: StorageService
    private lateinit var controller: StorageController

    @BeforeEach
    fun setUp() {
        storageService = mockk()
        every { storageService.uploadFile(any(), any(), any()) } returns "https://cdn.example/uploads/x"
        controller = StorageController(storageService)
    }

    private fun upload(contentType: String?, size: Int = 10) =
        controller.uploadFile(MockMultipartFile("file", "pic", contentType, ByteArray(size)))

    @Test
    fun `webp принимается и ложится в хранилище с расширением webp`() {
        val path = slot<String>()
        every { storageService.uploadFile(any(), capture(path), any()) } returns "https://cdn.example/x"

        val response = upload("image/webp")

        assertEquals(200, response.statusCode.value())
        assertTrue(path.captured.endsWith(".webp"), "ожидали .webp, получили ${path.captured}")
        verify { storageService.uploadFile(any(), any(), "image/webp") }
    }

    @Test
    fun `jpeg и png продолжают работать — webp их не вытеснил`() {
        val path = slot<String>()
        every { storageService.uploadFile(any(), capture(path), any()) } returns "https://cdn.example/x"

        upload("image/jpeg")
        assertTrue(path.captured.endsWith(".jpg"))

        upload("image/png")
        assertTrue(path.captured.endsWith(".png"))
    }

    @Test
    fun `формат вне списка отвергается до похода в хранилище`() {
        val e = assertThrows<ValidationException> { upload("image/gif") }
        assertTrue(e.message!!.contains("image/gif"), "в тексте ошибки нужен полученный тип")
        verify(exactly = 0) { storageService.uploadFile(any(), any(), any()) }
    }

    @Test
    fun `без content-type загрузка не проходит`() {
        assertThrows<ValidationException> { upload(null) }
        verify(exactly = 0) { storageService.uploadFile(any(), any(), any()) }
    }

    @Test
    fun `файл тяжелее 5 МБ отвергается`() {
        assertThrows<ValidationException> { upload("image/webp", size = 5 * 1024 * 1024 + 1) }
        verify(exactly = 0) { storageService.uploadFile(any(), any(), any()) }
    }
}
