package nl.dichtbij3d.backend.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.dichtbij3d.backend.domain.AdvertStatus
import nl.dichtbij3d.backend.domain.AdvertType
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.AdvertFilter
import nl.dichtbij3d.backend.service.AdvertService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/adverts")
class AdvertController(private val advertService: AdvertService) {

    @GetMapping
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) type: List<AdvertType>?,
        @RequestParam(required = false) tag: List<String>?,
        @RequestParam(required = false) status: List<AdvertStatus>?,
        @RequestParam(required = false) minPrice: Int?,
        @RequestParam(required = false) maxPrice: Int?,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) authorId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) postedAfter: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) postedBefore: LocalDate?,
        @RequestParam(defaultValue = "false") biddable: Boolean,
        @RequestParam(defaultValue = "newest") sort: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: AppPrincipal?,
        request: HttpServletRequest,
    ): PageResponse<AdvertSummaryDto> = advertService.search(
        AdvertFilter(
            query = q,
            types = type.orEmpty(),
            tags = tag.orEmpty(),
            statuses = status.orEmpty(),
            minPriceCents = minPrice,
            maxPriceCents = maxPrice,
            city = city,
            authorId = authorId,
            postedAfter = postedAfter,
            postedBefore = postedBefore,
            onlyBiddable = biddable,
            sort = sort,
            page = page,
            size = size,
        ),
        principal,
        request.locale(),
    )

    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AppPrincipal?,
        request: HttpServletRequest,
    ): AdvertDetailDto = advertService.detail(id, principal, request.locale())

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody body: AdvertCreateRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
        request: HttpServletRequest,
    ): AdvertDetailDto = advertService.create(body, principal, request.locale())

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: AdvertUpdateRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
        request: HttpServletRequest,
    ): AdvertDetailDto = advertService.update(id, body, principal, request.locale())

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
        @RequestParam(required = false) reason: String?,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse {
        advertService.delete(id, principal, reason)
        return MessageResponse("Advert removed")
    }

    @PostMapping("/{id}/moderate/delete")
    fun moderateDelete(
        @PathVariable id: UUID,
        @Valid @RequestBody body: ModerationRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse {
        advertService.delete(id, principal, body.reason)
        return MessageResponse("Advert removed and author notified")
    }

    @PostMapping("/{id}/accept")
    fun accept(
        @PathVariable id: UUID,
        @RequestParam(required = false) reactionId: UUID?,
        @RequestParam(required = false) userId: UUID?,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = advertService.accept(id, reactionId, principal, userId)

    @PostMapping("/{id}/status")
    fun status(
        @PathVariable id: UUID,
        @RequestParam status: AdvertStatus,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = advertService.updateStatus(id, status, principal)

    @PostMapping("/{id}/view")
    fun view(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: ViewPingRequest?,
        @AuthenticationPrincipal principal: AppPrincipal?,
        request: HttpServletRequest,
    ): Map<String, Int> = mapOf(
        "viewCount" to advertService.registerView(id, principal, request, body?.dwellMillis ?: 0)
    )

    // ------------------------------------------------------------ reactions

    @PostMapping("/{id}/reactions")
    @ResponseStatus(HttpStatus.CREATED)
    fun addReaction(
        @PathVariable id: UUID,
        @Valid @RequestBody body: ReactionCreateRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): ReactionDto = advertService.addReaction(id, body, principal)

    @DeleteMapping("/{id}/reactions/{reactionId}")
    fun deleteReaction(
        @PathVariable id: UUID,
        @PathVariable reactionId: UUID,
        @RequestParam(required = false) reason: String?,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse {
        advertService.deleteReaction(id, reactionId, principal, reason)
        return MessageResponse("Reaction removed")
    }

    // ------------------------------------------------------------ bids

    @PostMapping("/{id}/bids")
    @ResponseStatus(HttpStatus.CREATED)
    fun placeBid(
        @PathVariable id: UUID,
        @Valid @RequestBody body: BidCreateRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): BidDto = advertService.placeBid(id, body, principal)

    @PostMapping("/{id}/bids/{bidId}/accept")
    fun acceptBid(
        @PathVariable id: UUID,
        @PathVariable bidId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = advertService.decideBid(id, bidId, true, principal)

    @PostMapping("/{id}/bids/{bidId}/reject")
    fun rejectBid(
        @PathVariable id: UUID,
        @PathVariable bidId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = advertService.decideBid(id, bidId, false, principal)
}

/** Resolves the UI locale from `X-Locale` or the standard `Accept-Language` header. */
fun HttpServletRequest.locale(): String {
    val explicit = getHeader("X-Locale")?.lowercase()?.take(2)
    val fromAccept = getHeader("Accept-Language")?.split(",")?.firstOrNull()?.trim()?.lowercase()?.take(2)
    return (explicit ?: fromAccept)?.takeIf { it in SUPPORTED_LOCALES } ?: "nl"
}

private val SUPPORTED_LOCALES = setOf("nl", "en", "de", "fr")
