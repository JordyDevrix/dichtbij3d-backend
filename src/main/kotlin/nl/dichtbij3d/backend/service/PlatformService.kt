package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.AuditLogEntry
import nl.dichtbij3d.backend.domain.PlatformAnnouncement
import nl.dichtbij3d.backend.domain.PlatformBanner
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.AuditLogRepository
import nl.dichtbij3d.backend.repo.PlatformAnnouncementRepository
import nl.dichtbij3d.backend.repo.PlatformBannerRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class PlatformService(
    private val bannerRepository: PlatformBannerRepository,
    private val announcementRepository: PlatformAnnouncementRepository,
    private val auditLogRepository: AuditLogRepository,
    private val mapper: DtoMapper,
) {

    private val bannerId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Transactional
    fun getOrCreateBanner(): PlatformBanner {
        return bannerRepository.findById(bannerId).orElseGet {
            bannerRepository.save(
                PlatformBanner(
                    id = bannerId,
                    enabled = false,
                    title = "Welkom bij Dichtbij3D",
                    subtitle = "Vind 3D-printers en ontwerpers bij jou in de buurt of verkoop je eigen creaties.",
                    badgeText = "Nieuw",
                    buttonText = "Ontdek marktplaats",
                    linkUrl = "/marketplace",
                )
            )
        }
    }

    @Transactional(readOnly = true)
    fun getBanner(): PlatformBannerDto = mapper.banner(getOrCreateBanner())

    @Transactional
    fun updateBanner(request: PlatformBannerUpdateRequest, actor: AppPrincipal): PlatformBannerDto {
        val banner = getOrCreateBanner()
        banner.enabled = request.enabled
        banner.title = request.title.trim()
        banner.subtitle = request.subtitle?.trim()?.ifBlank { null }
        banner.badgeText = request.badgeText?.trim()?.ifBlank { null }
        banner.buttonText = request.buttonText?.trim()?.ifBlank { null }
        banner.linkUrl = request.linkUrl?.trim()?.ifBlank { null }
        banner.imageKey = request.imageKey?.trim()?.ifBlank { null }
        banner.imageUrl = request.imageUrl?.trim()?.ifBlank { null }
        banner.updatedAt = Instant.now()
        val saved = bannerRepository.save(banner)

        auditLogRepository.save(
            AuditLogEntry(
                actorId = actor.id,
                action = "BANNER_UPDATED",
                targetType = "PLATFORM_BANNER",
                targetId = bannerId,
                detail = "enabled=${saved.enabled}, title=${saved.title}",
            )
        )
        return mapper.banner(saved)
    }

    @Transactional(readOnly = true)
    fun getActiveAnnouncements(): List<PlatformAnnouncementDto> =
        announcementRepository.findAllByActiveTrueOrderByCreatedAtDesc().map(mapper::announcement)

    @Transactional(readOnly = true)
    fun getAllAnnouncements(): List<PlatformAnnouncementDto> =
        announcementRepository.findAllByOrderByCreatedAtDesc().map(mapper::announcement)

    @Transactional
    fun createAnnouncement(request: PlatformAnnouncementRequest, actor: AppPrincipal): PlatformAnnouncementDto {
        val announcement = PlatformAnnouncement(
            title = request.title.trim(),
            content = request.content.trim(),
            type = request.type,
            eventDate = request.eventDate,
            linkUrl = request.linkUrl?.trim()?.ifBlank { null },
            linkText = request.linkText?.trim()?.ifBlank { null },
            active = request.active,
        )
        val saved = announcementRepository.save(announcement)
        auditLogRepository.save(
            AuditLogEntry(
                actorId = actor.id,
                action = "ANNOUNCEMENT_CREATED",
                targetType = "PLATFORM_ANNOUNCEMENT",
                targetId = saved.id,
                detail = "title=${saved.title}, type=${saved.type}",
            )
        )
        return mapper.announcement(saved)
    }

    @Transactional
    fun updateAnnouncement(id: UUID, request: PlatformAnnouncementRequest, actor: AppPrincipal): PlatformAnnouncementDto {
        val announcement = announcementRepository.findById(id).orElseThrow { ApiException.notFound("Announcement") }
        announcement.title = request.title.trim()
        announcement.content = request.content.trim()
        announcement.type = request.type
        announcement.eventDate = request.eventDate
        announcement.linkUrl = request.linkUrl?.trim()?.ifBlank { null }
        announcement.linkText = request.linkText?.trim()?.ifBlank { null }
        announcement.active = request.active
        announcement.updatedAt = Instant.now()
        val saved = announcementRepository.save(announcement)
        auditLogRepository.save(
            AuditLogEntry(
                actorId = actor.id,
                action = "ANNOUNCEMENT_UPDATED",
                targetType = "PLATFORM_ANNOUNCEMENT",
                targetId = id,
                detail = "title=${saved.title}, active=${saved.active}",
            )
        )
        return mapper.announcement(saved)
    }

    @Transactional
    fun deleteAnnouncement(id: UUID, actor: AppPrincipal): MessageResponse {
        val announcement = announcementRepository.findById(id).orElseThrow { ApiException.notFound("Announcement") }
        announcementRepository.delete(announcement)
        auditLogRepository.save(
            AuditLogEntry(
                actorId = actor.id,
                action = "ANNOUNCEMENT_DELETED",
                targetType = "PLATFORM_ANNOUNCEMENT",
                targetId = id,
                detail = "title=${announcement.title}",
            )
        )
        return MessageResponse("Announcement deleted")
    }
}
