package nl.dichtbij3d.backend.web

import jakarta.validation.Valid
import nl.dichtbij3d.backend.dto.AddParticipantRequest
import nl.dichtbij3d.backend.dto.ConversationDto
import nl.dichtbij3d.backend.dto.ConversationStartRequest
import nl.dichtbij3d.backend.dto.MessageCreateRequest
import nl.dichtbij3d.backend.dto.MessageDto
import nl.dichtbij3d.backend.dto.MessageResponse
import nl.dichtbij3d.backend.dto.PageResponse
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.ChatService
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/conversations")
class ChatController(private val service: ChatService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AppPrincipal,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "30") size: Int,
    ): PageResponse<ConversationDto> = service.list(principal, page, size)

    @GetMapping("/unread-count")
    fun unread(@AuthenticationPrincipal principal: AppPrincipal): Map<String, Long> =
        mapOf("count" to service.unreadCount(principal))

    @PostMapping
    fun start(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: ConversationStartRequest,
    ): ConversationDto = service.start(
        principal = principal,
        peerId = request.userId,
        peerIds = request.userIds,
        title = request.title,
        advertId = request.advertId,
        firstMessage = request.message,
    )

    @PostMapping("/{id}/participants")
    fun addParticipant(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: AddParticipantRequest,
    ): ConversationDto = service.addParticipant(id, request.userId, principal)

    @PostMapping(path = ["/{id}/accept", "/{id}/approve"])
    fun accept(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
    ): ConversationDto = service.acceptInvite(id, principal)

    @PostMapping("/{id}/decline")
    fun decline(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
    ): ConversationDto = service.declineInvite(id, principal)

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: UUID): ConversationDto =
        service.detail(id, principal)

    @GetMapping("/{id}/messages")
    fun messages(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "40") size: Int,
    ): PageResponse<MessageDto> = service.messages(id, principal, page, size)

    @PostMapping("/{id}/messages")
    fun send(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: MessageCreateRequest,
    ): MessageDto = service.send(id, principal, request)

    @GetMapping("/{id}/messages/{messageId}/download")
    fun download(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
        @PathVariable messageId: UUID,
    ): ResponseEntity<InputStreamResource> {
        val (stream, fileName, contentType) = service.downloadAttachment(id, messageId, principal)
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName).build().toString()
            )
            .contentType(MediaType.parseMediaType(contentType))
            .body(InputStreamResource(stream))
    }

    @PostMapping("/{id}/read")
    fun read(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: UUID): MessageResponse {
        service.markRead(id, principal)
        return MessageResponse("ok")
    }
}
