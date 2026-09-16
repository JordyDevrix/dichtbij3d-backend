package nl.dichtbij3d.backend.web

import jakarta.validation.Valid
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.HeroBannerService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/banners")
class HeroBannerPublicController(
    private val service: HeroBannerService,
) {
    @GetMapping
    fun getPublicBanners(): PublicBannersResponse = service.getPublicBanners()
}

@RestController
@RequestMapping("/api/admin/banners")
@PreAuthorize("hasRole('ADMIN')")
class AdminHeroBannerController(
    private val service: HeroBannerService,
) {
    @GetMapping
    fun list(): List<HeroBannerDto> = service.getAllBanners()

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: HeroBannerCreateRequest,
    ): HeroBannerDto = service.createBanner(request, principal)

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: HeroBannerUpdateRequest,
    ): HeroBannerDto = service.updateBanner(id, request, principal)

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
    ): MessageResponse {
        service.deleteBanner(id, principal)
        return MessageResponse("Hero banner deleted")
    }

    @PostMapping("/reorder")
    fun reorder(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: HeroBannerReorderRequest,
    ): MessageResponse {
        service.reorderBanners(request, principal)
        return MessageResponse("Hero banners reordered")
    }

    @GetMapping("/settings")
    fun getSettings(): HeroBannerSettingsDto = service.getSettings()

    @PutMapping("/settings")
    fun updateSettings(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: HeroBannerSettingsUpdateRequest,
    ): HeroBannerSettingsDto = service.updateSettings(request, principal)
}
