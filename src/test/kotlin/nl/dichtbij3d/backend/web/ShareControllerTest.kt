package nl.dichtbij3d.backend.web

import nl.dichtbij3d.backend.config.ShareProperties
import nl.dichtbij3d.backend.domain.Advert
import nl.dichtbij3d.backend.domain.AdvertStatus
import nl.dichtbij3d.backend.domain.AdvertType
import nl.dichtbij3d.backend.domain.Category
import nl.dichtbij3d.backend.domain.User
import nl.dichtbij3d.backend.repo.AdvertRepository
import nl.dichtbij3d.backend.service.SocialImageService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional
import java.util.UUID

class ShareControllerTest {

    private lateinit var advertRepository: AdvertRepository
    private lateinit var socialImageService: SocialImageService
    private lateinit var shareProperties: ShareProperties
    private lateinit var controller: ShareController
    private lateinit var mockMvc: MockMvc

    private val advertId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private lateinit var sampleAdvert: Advert

    @BeforeEach
    fun setup() {
        advertRepository = mock(AdvertRepository::class.java)
        socialImageService = mock(SocialImageService::class.java)
        shareProperties = ShareProperties()
        controller = ShareController(advertRepository, socialImageService, shareProperties)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        val user = User(id = UUID.randomUUID(), email = "author@example.com", displayName = "Author")
        sampleAdvert = Advert(
            id = advertId,
            author = user,
            type = AdvertType.PRINT_REQUEST,
            category = Category.OTHER,
            title = "Mooie 3D geprinte lampenkap",
            description = "Ik zoek iemand die deze lampenkap kan printen in wit PLA.",
            status = AdvertStatus.OPEN,
            priceCents = 3500,
            city = "Utrecht"
        )
        `when`(advertRepository.findById(advertId)).thenReturn(Optional.of(sampleAdvert))
    }

    @Test
    fun `shareAdvert returns HTML with WhatsApp OpenGraph tags and dedicated thumbnail endpoint`() {
        mockMvc.perform(
            get("/api/share/advert/$advertId")
                .header(HttpHeaders.USER_AGENT, "WhatsApp/2.23.20.76 i")
                .header(HttpHeaders.HOST, "dichtbij3d.nl")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("og:title")))
            .andExpect(content().string(containsString("Mooie 3D geprinte lampenkap")))
            .andExpect(content().string(containsString("og:image")))
            .andExpect(content().string(containsString("https://dichtbij3d.nl/api/share/advert/$advertId/image.jpg")))
            .andExpect(content().string(containsString("og:image:width")))
            .andExpect(content().string(containsString("1200")))
            .andExpect(content().string(containsString("og:image:height")))
            .andExpect(content().string(containsString("630")))
            .andExpect(content().string(containsString("og:image:type")))
            .andExpect(content().string(containsString("image/jpeg")))
            // WhatsApp crawler should NOT receive meta http-equiv="refresh"
            .andExpect(content().string(not(containsString("http-equiv=\"refresh\""))))
    }

    @Test
    fun `shareAdvert includes client-side redirect for human browser visitors`() {
        mockMvc.perform(
            get("/api/share/advert/$advertId")
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .header(HttpHeaders.HOST, "dichtbij3d.nl")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("http-equiv=\"refresh\"")))
            .andExpect(content().string(containsString("window.location.replace")))
    }

    @Test
    fun `shareAdvertImage serves optimized JPEG with Content-Length and Cache-Control headers`() {
        val sampleJpeg = ByteArray(50_000) { 1 }
        `when`(socialImageService.generateAdvertThumbnail(sampleAdvert)).thenReturn(sampleJpeg)

        mockMvc.perform(
            get("/api/share/advert/$advertId/image.jpg")
                .header(HttpHeaders.HOST, "dichtbij3d.nl")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "50000"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
            .andExpect(content().bytes(sampleJpeg))
    }

    @Test
    fun `shareAdvertImage supports HEAD requests with Content-Length`() {
        val sampleJpeg = ByteArray(42_000) { 2 }
        `when`(socialImageService.generateAdvertThumbnail(sampleAdvert)).thenReturn(sampleJpeg)

        mockMvc.perform(
            head("/api/share/advert/$advertId/image.jpg")
                .header(HttpHeaders.HOST, "dichtbij3d.nl")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "42000"))
    }
}
