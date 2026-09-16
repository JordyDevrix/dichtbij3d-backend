package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.BannerMediaType
import nl.dichtbij3d.backend.domain.HeroBanner
import nl.dichtbij3d.backend.domain.HeroBannerSettings
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.HeroBannerRepository
import nl.dichtbij3d.backend.repo.HeroBannerSettingsRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.Optional
import java.util.UUID

class HeroBannerTest {

    private lateinit var bannerRepo: HeroBannerRepository
    private lateinit var settingsRepo: HeroBannerSettingsRepository
    private lateinit var service: HeroBannerService

    private val adminPrincipal = AppPrincipal(
        id = UUID.randomUUID(),
        email = "admin@dichtbij3d.nl",
        displayName = "Admin",
        roles = setOf(Role.ADMIN),
    )

    @BeforeEach
    fun setup() {
        bannerRepo = mock(HeroBannerRepository::class.java)
        settingsRepo = mock(HeroBannerSettingsRepository::class.java)
        service = HeroBannerService(bannerRepo, settingsRepo)
    }

    @Test
    fun `auto detects video media type for mp4 and webm and image for others`() {
        `when`(settingsRepo.findById(1)).thenReturn(
            Optional.of(HeroBannerSettings(id = 1, slideDurationSeconds = 5, showForLoggedInUsers = false))
        )
        `when`(bannerRepo.findAll()).thenReturn(emptyList())
        `when`(bannerRepo.save(any(HeroBanner::class.java))).thenAnswer { invocation ->
            val b = invocation.getArgument(0) as HeroBanner
            b.id = UUID.randomUUID()
            b
        }

        val videoBanner = service.createBanner(
            HeroBannerCreateRequest(mediaUrl = "https://example.com/video.mp4"),
            adminPrincipal
        )
        assertEquals(BannerMediaType.VIDEO, videoBanner.mediaType)

        val imageBanner = service.createBanner(
            HeroBannerCreateRequest(mediaUrl = "https://example.com/banner.gif"),
            adminPrincipal
        )
        assertEquals(BannerMediaType.IMAGE, imageBanner.mediaType)
    }

    @Test
    fun `returns public banners and settings`() {
        val banner1 = HeroBanner(
            id = UUID.randomUUID(),
            title = "Banner 1",
            mediaUrl = "/api/files/banner1.jpg",
            mediaType = BannerMediaType.IMAGE,
            sortOrder = 0,
            enabled = true,
        )
        `when`(bannerRepo.findByEnabledTrueOrderBySortOrderAscCreatedAtAsc()).thenReturn(listOf(banner1))
        `when`(settingsRepo.findById(1)).thenReturn(
            Optional.of(HeroBannerSettings(id = 1, slideDurationSeconds = 7, showForLoggedInUsers = true))
        )

        val response = service.getPublicBanners()
        assertEquals(1, response.banners.size)
        assertEquals("Banner 1", response.banners[0].title)
        assertEquals(7, response.settings.slideDurationSeconds)
        assertTrue(response.settings.showForLoggedInUsers)
    }

    @Test
    fun `updates settings correctly`() {
        val existing = HeroBannerSettings(id = 1, slideDurationSeconds = 5, showForLoggedInUsers = false)
        `when`(settingsRepo.findById(1)).thenReturn(Optional.of(existing))
        `when`(settingsRepo.save(any(HeroBannerSettings::class.java))).thenAnswer { it.getArgument(0) }

        val updated = service.updateSettings(
            HeroBannerSettingsUpdateRequest(slideDurationSeconds = 10, showForLoggedInUsers = true),
            adminPrincipal
        )

        assertEquals(10, updated.slideDurationSeconds)
        assertTrue(updated.showForLoggedInUsers)
    }
}
