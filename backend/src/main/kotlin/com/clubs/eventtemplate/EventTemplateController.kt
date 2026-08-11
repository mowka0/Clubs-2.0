package com.clubs.eventtemplate

import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.RequiresCapability
import com.clubs.common.security.AuthenticatedUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Шаблоны встреч клуба. Все club-scoped точки гейтятся [ClubCapability.MANAGE_EVENTS] — тем же
 * правом, что и создание самой встречи, поэтому со-организаторы работают с шаблонами наравне
 * с владельцем. Спека: docs/modules/event-templates.md.
 *
 * Пути club-scoped намеренно (в том числе для правки и удаления, где хватило бы одного
 * templateId): так работает аннотация @RequiresCapability, и принадлежность шаблона клубу
 * проверяется явно, а не выводится из тела запроса.
 */
@RestController
class EventTemplateController(
    private val templateService: EventTemplateService
) {

    /**
     * Шаблоны всех клубов, где у вызывающего есть MANAGE_EVENTS — питает список в пикере «+».
     * НАМЕРЕННО без @RequiresCapability: клуба в пути нет, авторизация тут — сама фильтрация
     * клубов внутри сервиса (тот же приём, что у /me/events).
     */
    @GetMapping("/api/me/event-templates")
    fun getMyTemplates(
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<List<EventTemplateDto>> =
        ResponseEntity.ok(templateService.getMyTemplates(user.userId))

    @RequiresCapability(ClubCapability.MANAGE_EVENTS, clubIdParam = "clubId")
    @GetMapping("/api/clubs/{clubId}/event-templates")
    fun getClubTemplates(@PathVariable clubId: UUID): ResponseEntity<List<EventTemplateDto>> =
        ResponseEntity.ok(templateService.getClubTemplates(clubId))

    @RequiresCapability(ClubCapability.MANAGE_EVENTS, clubIdParam = "clubId")
    @PostMapping("/api/clubs/{clubId}/event-templates")
    fun createTemplate(
        @PathVariable clubId: UUID,
        @RequestBody @Valid request: SaveEventTemplateRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<EventTemplateDto> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(templateService.createTemplate(clubId, request, user.userId))

    @RequiresCapability(ClubCapability.MANAGE_EVENTS, clubIdParam = "clubId")
    @PutMapping("/api/clubs/{clubId}/event-templates/{templateId}")
    fun updateTemplate(
        @PathVariable clubId: UUID,
        @PathVariable templateId: UUID,
        @RequestBody @Valid request: SaveEventTemplateRequest,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<EventTemplateDto> =
        ResponseEntity.ok(templateService.updateTemplate(clubId, templateId, request, user.userId))

    @RequiresCapability(ClubCapability.MANAGE_EVENTS, clubIdParam = "clubId")
    @DeleteMapping("/api/clubs/{clubId}/event-templates/{templateId}")
    fun deleteTemplate(
        @PathVariable clubId: UUID,
        @PathVariable templateId: UUID,
        @AuthenticationPrincipal user: AuthenticatedUser
    ): ResponseEntity<Void> {
        templateService.deleteTemplate(clubId, templateId, user.userId)
        return ResponseEntity.noContent().build()
    }
}
