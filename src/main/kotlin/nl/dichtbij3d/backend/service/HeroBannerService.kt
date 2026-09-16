package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.BannerMediaType
import nl.dichtbij3d.backend.domain.HeroBanner
import nl.dichtbij3d.backend.domain.HeroBannerSettings
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.HeroBannerRepository
import nl.dichtbij3d.backend.repo.HeroBannerSettingsRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class HeroBannerService(
    private val bannerRepository: HeroBannerRepository,
    private val settingsRepository: HeroBannerSettingsRepository,
) {

    @Transactional(readOnly = true)
    fun getPublicBanners(): PublicBannersResponse {
        val banners = bannerRepository.findByEnabledTrueOrderBySortOrderAscCreatedAtAsc().map { toDto(it) }
        val settings = getSettings()
        return PublicBannersResponse(banners, settings)
    }

    @Transactional(readOnly = true)
    fun getAllBanners(): List<HeroBannerDto> =
        bannerRepository.findAllByOrderBySortOrderAscCreatedAtAsc().map { toDto(it) }

    @Transactional(readOnly = true)
    fun getSettings(): HeroBannerSettingsDto {
        val settings = settingsRepository.findById(1).orElseGet {
            settingsRepository.save(HeroBannerSettings(id = 1, slideDurationSeconds = 5, showForLoggedInUsers = false))
        }
        return HeroBannerSettingsDto(
            slideDurationSeconds = settings.slideDurationSeconds,
            showForLoggedInUsers = settings.showForLoggedInUsers,
        )
    }

    @Transactional
    fun updateSettings(request: HeroBannerSettingsUpdateRequest, principal: AppPrincipal): HeroBannerSettingsDto {
        val settings = settingsRepository.findById(1).orElseGet {
            HeroBannerSettings(id = 1, slideDurationSeconds = 5, showForLoggedInUsers = false)
        }
        request.slideDurationSeconds?.let { settings.slideDurationSeconds = it }
        request.showForLoggedInUsers?.let { settings.showForLoggedInUsers = it }
        settings.updatedAt = Instant.now()
        val saved = settingsRepository.save(settings)
        return HeroBannerSettingsDto(
            slideDurationSeconds = saved.slideDurationSeconds,
            showForLoggedInUsers = saved.showForLoggedInUsers,
        )
    }

    @Transactional
    fun createBanner(request: HeroBannerCreateRequest, principal: AppPrincipal): HeroBannerDto {
        val mediaType = request.mediaType ?: detectMediaType(request.mediaUrl)
        val sortOrder = request.sortOrder ?: run {
            val all = bannerRepository.findAll()
            if (all.isEmpty()) 0 else (all.maxOfOrNull { it.sortOrder } ?: 0) + 1
        }

        val banner = HeroBanner(
            title = request.title?.trim()?.ifBlank { null },
            subtitle = request.subtitle?.trim()?.ifBlank { null },
            mediaUrl = request.mediaUrl.trim(),
            mediaType = mediaType,
            durationSeconds = request.durationSeconds,
            linkUrl = request.linkUrl?.trim()?.ifBlank { null },
            linkText = request.linkText?.trim()?.ifBlank { null },
            sortOrder = sortOrder,
            enabled = request.enabled ?: true,
        )

        return toDto(bannerRepository.save(banner))
    }

    @Transactional
    fun updateBanner(id: UUID, request: HeroBannerUpdateRequest, principal: AppPrincipal): HeroBannerDto {
        val banner = bannerRepository.findById(id).orElseThrow { ApiException.notFound("Hero banner") }
        request.title?.let { banner.title = it.trim().ifBlank { null } }
        request.subtitle?.let { banner.subtitle = it.trim().ifBlank { null } }
        request.mediaUrl?.let {
            banner.mediaUrl = it.trim()
            if (request.mediaType == null) {
                banner.mediaType = detectMediaType(it.trim())
            }
        }
        request.mediaType?.let { banner.mediaType = it }
        if (request.durationSeconds != null) {
            banner.durationSeconds = request.durationSeconds
        }
        request.linkUrl?.let { banner.linkUrl = it.trim().ifBlank { null } }
        request.linkText?.let { banner.linkText = it.trim().ifBlank { null } }
        request.sortOrder?.let { banner.sortOrder = it }
        request.enabled?.let { banner.enabled = it }
        banner.updatedAt = Instant.now()

        return toDto(bannerRepository.save(banner))
    }

    @Transactional
    fun deleteBanner(id: UUID, principal: AppPrincipal) {
        if (!bannerRepository.existsById(id)) throw ApiException.notFound("Hero banner")
        bannerRepository.deleteById(id)
    }

    @Transactional
    fun reorderBanners(request: HeroBannerReorderRequest, principal: AppPrincipal) {
        val all = bannerRepository.findAllById(request.bannerIds).associateBy { it.id }
        request.bannerIds.forEachIndexed { index, id ->
            val banner = all[id]
            if (banner != null) {
                banner.sortOrder = index
                banner.updatedAt = Instant.now()
                bannerRepository.save(banner)
            }
        }
    }

    private fun detectMediaType(url: String): BannerMediaType {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return if (clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov") ||
            clean.endsWith(".m4v") || clean.endsWith(".ogg") || clean.endsWith(".ogv")
        ) {
            BannerMediaType.VIDEO
        } else {
            BannerMediaType.IMAGE
        }
    }

    private fun toDto(b: HeroBanner) = HeroBannerDto(
        id = b.id!!,
        title = b.title,
        subtitle = b.subtitle,
        mediaUrl = b.mediaUrl,
        mediaType = b.mediaType,
        durationSeconds = b.durationSeconds,
        linkUrl = b.linkUrl,
        linkText = b.linkText,
        sortOrder = b.sortOrder,
        enabled = b.enabled,
        createdAt = b.createdAt,
        updatedAt = b.updatedAt,
    )
}
