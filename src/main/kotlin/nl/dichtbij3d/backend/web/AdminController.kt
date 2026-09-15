package nl.dichtbij3d.backend.web

import jakarta.validation.Valid
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.AdminAdvertDto
import nl.dichtbij3d.backend.service.AdminService
import nl.dichtbij3d.backend.service.PlatformService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val service: AdminService,
    private val platformService: PlatformService,
) {

    @GetMapping("/metrics")
    fun metrics(): AdminMetricsDto = service.metrics()

    @GetMapping("/users")
    fun users(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): PageResponse<AdminUserDto> = service.users(q, page, size)

    @PatchMapping("/users/{id}")
    fun updateUser(
        @PathVariable id: UUID,
        @RequestBody request: AdminUserUpdateRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = service.updateUser(id, request, principal)

    @DeleteMapping("/users/{id}")
    fun deleteUser(
        @PathVariable id: UUID,
        @RequestParam(required = false) reason: String?,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = service.deleteUser(id, principal, reason)

    @GetMapping("/adverts")
    fun adverts(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): PageResponse<AdminAdvertDto> = service.adverts(page, size)

    @PostMapping("/adverts/{id}/restore")
    fun restore(@PathVariable id: UUID, @AuthenticationPrincipal principal: AppPrincipal): MessageResponse =
        service.restoreAdvert(id, principal)

    @GetMapping("/reports")
    fun reports(): List<ReportDto> = service.reports()

    @PostMapping("/reports/{id}/resolve")
    fun resolve(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") dismiss: Boolean,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = service.resolveReport(id, dismiss, principal)

    @GetMapping("/audit-log")
    fun auditLog(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): PageResponse<AuditLogDto> = service.auditLog(page, size)

    // Platform banner & announcements management
    @GetMapping("/banner")
    fun getBanner(): PlatformBannerDto = platformService.getBanner()

    @PutMapping("/banner")
    fun updateBanner(
        @RequestBody request: PlatformBannerUpdateRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): PlatformBannerDto = platformService.updateBanner(request, principal)

    @GetMapping("/announcements")
    fun getAnnouncements(): List<PlatformAnnouncementDto> = platformService.getAllAnnouncements()

    @PostMapping("/announcements")
    fun createAnnouncement(
        @Valid @RequestBody request: PlatformAnnouncementRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): PlatformAnnouncementDto = platformService.createAnnouncement(request, principal)

    @PutMapping("/announcements/{id}")
    fun updateAnnouncement(
        @PathVariable id: UUID,
        @Valid @RequestBody request: PlatformAnnouncementRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): PlatformAnnouncementDto = platformService.updateAnnouncement(id, request, principal)

    @DeleteMapping("/announcements/{id}")
    fun deleteAnnouncement(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = platformService.deleteAnnouncement(id, principal)
}

@RestController
@RequestMapping("/api/public")
class PublicController(
    private val service: AdminService,
    private val platformService: PlatformService,
) {

    /** Lightweight counters used on the public landing page. */
    @GetMapping("/stats")
    fun stats(): Map<String, Long> {
        val metrics = service.metrics()
        return mapOf(
            "adverts" to metrics.totalAdverts,
            "users" to metrics.totalUsers,
            "models" to metrics.totalModels,
            "views" to metrics.totalViews,
        )
    }

    @GetMapping("/banner")
    fun getBanner(): PlatformBannerDto = platformService.getBanner()

    @GetMapping("/announcements")
    fun getAnnouncements(): List<PlatformAnnouncementDto> = platformService.getActiveAnnouncements()
}

