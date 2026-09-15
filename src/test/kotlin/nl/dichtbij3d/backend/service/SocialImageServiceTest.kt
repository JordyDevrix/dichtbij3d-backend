package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.Advert
import nl.dichtbij3d.backend.domain.AdvertImage
import nl.dichtbij3d.backend.domain.AdvertStatus
import nl.dichtbij3d.backend.domain.AdvertType
import nl.dichtbij3d.backend.domain.Category
import nl.dichtbij3d.backend.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

class SocialImageServiceTest {

    private lateinit var storage: StorageService
    private lateinit var service: SocialImageService

    @BeforeEach
    fun setup() {
        storage = mock(StorageService::class.java)
        service = SocialImageService(storage)
        service.init()
    }

    @Test
    fun `generateAdvertThumbnail creates 1200x630 JPEG under 280KB for landscape image`() {
        val user = User(id = UUID.randomUUID(), email = "test@example.com", displayName = "Test User")
        val advert = Advert(
            id = UUID.randomUUID(),
            author = user,
            type = AdvertType.PRINT_REQUEST,
            category = Category.TOOLS_WORKSHOP,
            title = "Test 3D Print Request",
            description = "A detailed description of the 3D model to print.",
            status = AdvertStatus.OPEN,
            priceCents = 2500,
            city = "Utrecht"
        )
        val imageKey = "listings/2026/09/landscape.jpg"
        advert.images.add(AdvertImage(advert = advert, objectKey = imageKey, sortOrder = 0))

        // Create sample landscape image (1600 x 900)
        val landscape = BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB)
        val g = landscape.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, 1600, 900)
        g.color = Color.YELLOW
        g.fillOval(400, 200, 800, 500)
        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(landscape, "jpg", baos)
        val rawBytes = baos.toByteArray()

        `when`(storage.readBytesWithAlias(imageKey)).thenReturn(rawBytes)

        val resultBytes = service.generateAdvertThumbnail(advert)
        assertNotNull(resultBytes)
        // Must be <= 280 KB (safe for WhatsApp's 300 KB limit)
        assertTrue(resultBytes!!.size <= 280_000, "Output size ${resultBytes.size} must be <= 280,000 bytes")

        // Must decode to exactly 1200 x 630 px
        val resultImg = ImageIO.read(ByteArrayInputStream(resultBytes))
        assertNotNull(resultImg)
        assertEquals(1200, resultImg.width)
        assertEquals(630, resultImg.height)
    }

    @Test
    fun `generateAdvertThumbnail creates 1200x630 JPEG for portrait image with blur background`() {
        val user = User(id = UUID.randomUUID(), email = "test@example.com", displayName = "Test User")
        val advert = Advert(
            id = UUID.randomUUID(),
            author = user,
            type = AdvertType.MODEL_FOR_SALE,
            category = Category.TABLETOP_MINIATURES,
            title = "Portrait Miniature Figurine",
            description = "High resolution miniature model for printing.",
            status = AdvertStatus.OPEN,
            priceCents = 1500,
            city = "Amsterdam"
        )
        val imageKey = "listings/2026/09/portrait.jpg"
        advert.images.add(AdvertImage(advert = advert, objectKey = imageKey, sortOrder = 0))

        // Create sample portrait image (600 x 1200)
        val portrait = BufferedImage(600, 1200, BufferedImage.TYPE_INT_RGB)
        val g = portrait.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 600, 1200)
        g.color = Color.WHITE
        g.fillRect(100, 200, 400, 800)
        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(portrait, "jpg", baos)
        val rawBytes = baos.toByteArray()

        `when`(storage.readBytesWithAlias(imageKey)).thenReturn(rawBytes)

        val resultBytes = service.generateAdvertThumbnail(advert)
        assertNotNull(resultBytes)
        assertTrue(resultBytes!!.size <= 280_000, "Portrait output size ${resultBytes.size} must be <= 280,000 bytes")

        val resultImg = ImageIO.read(ByteArrayInputStream(resultBytes))
        assertNotNull(resultImg)
        assertEquals(1200, resultImg.width)
        assertEquals(630, resultImg.height)
    }

    @Test
    fun `generateAdvertThumbnail generates branded card when no image is uploaded`() {
        val user = User(id = UUID.randomUUID(), email = "test@example.com", displayName = "Test User")
        val advert = Advert(
            id = UUID.randomUUID(),
            author = user,
            type = AdvertType.PRINT_REQUEST,
            category = Category.SPARE_PARTS_REPAIR,
            title = "Gezocht: tandwiel voor espressomachine",
            description = "Wie kan een klein tandwiel printen in PETG of nylon?",
            status = AdvertStatus.OPEN,
            allowBidding = true,
            city = "Rotterdam"
        )

        val resultBytes = service.generateAdvertThumbnail(advert)
        assertNotNull(resultBytes)
        assertTrue(resultBytes!!.size <= 280_000, "Branded card size ${resultBytes.size} must be <= 280,000 bytes")

        val resultImg = ImageIO.read(ByteArrayInputStream(resultBytes))
        assertNotNull(resultImg)
        assertEquals(1200, resultImg.width)
        assertEquals(630, resultImg.height)
    }

    @Test
    fun `generateAdvertThumbnail caches result for repeated requests`() {
        val user = User(id = UUID.randomUUID(), email = "test@example.com", displayName = "Test User")
        val advert = Advert(
            id = UUID.randomUUID(),
            author = user,
            type = AdvertType.PRINT_REQUEST,
            category = Category.OTHER,
            title = "Cached Advert",
            description = "Description",
            status = AdvertStatus.OPEN
        )

        val first = service.generateAdvertThumbnail(advert)
        val second = service.generateAdvertThumbnail(advert)
        assertNotNull(first)
        assertNotNull(second)
        // Should return identical cached bytes instance
        assertTrue(first === second)
    }
}
