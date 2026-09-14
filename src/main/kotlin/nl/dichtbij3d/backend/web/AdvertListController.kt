package nl.dichtbij3d.backend.web

import jakarta.validation.Valid
import nl.dichtbij3d.backend.dto.AdvertListCreateRequest
import nl.dichtbij3d.backend.dto.AdvertListDto
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.AdvertListService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/users/me/lists")
class AdvertListController(
    private val service: AdvertListService,
) {

    @GetMapping
    fun getLists(@AuthenticationPrincipal principal: AppPrincipal): List<AdvertListDto> =
        service.getOrCreateUserLists(principal.id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createList(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: AdvertListCreateRequest,
    ): AdvertListDto = service.createList(principal.id, request.name)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteList(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
    ) = service.deleteList(principal.id, id)

    @PostMapping("/{listId}/items/{advertId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun addToList(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable listId: UUID,
        @PathVariable advertId: UUID,
    ) = service.addToList(principal.id, listId, advertId)

    @DeleteMapping("/{listId}/items/{advertId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeFromList(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable listId: UUID,
        @PathVariable advertId: UUID,
    ) = service.removeFromList(principal.id, listId, advertId)
}
