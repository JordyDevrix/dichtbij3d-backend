package nl.dichtbij3d.backend.web

import nl.dichtbij3d.backend.config.StorageProperties
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.StorageService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.ByteArrayInputStream
import java.util.UUID

class FileSecurityTest {

    private lateinit var storage: StorageService
    private lateinit var controller: FileController
    private lateinit var mockMvc: MockMvc

    private val principal = AppPrincipal(
        id = UUID.randomUUID(),
        email = "user@example.com",
        displayName = "Test User",
        roles = setOf(nl.dichtbij3d.backend.domain.Role.CUSTOMER),
    )

    @BeforeEach
    fun setup() {
        storage = mock(StorageService::class.java)
        controller = FileController(storage)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `serve blocks access to models and chat directory prefixes`() {
        mockMvc.perform(get("/api/files/models/2026/09/secret-model.stl"))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/api/files/chat/2026/09/private-doc.pdf"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `serve allows public listings and avatars and sets nosniff header`() {
        val bytes = "fake image content".toByteArray()
        `when`(storage.readWithAlias("listings/2026/09/image.jpg"))
            .thenReturn(Pair(ByteArrayInputStream(bytes), bytes.size.toLong()))

        mockMvc.perform(get("/api/files/listings/2026/09/image.jpg"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    @Test
    fun `serve adds strict CSP header to SVG files`() {
        val svg = "<svg><circle cx='50' cy='50' r='40'/></svg>".toByteArray()
        `when`(storage.readWithAlias("listings/2026/09/icon.svg"))
            .thenReturn(Pair(ByteArrayInputStream(svg), svg.size.toLong()))

        mockMvc.perform(get("/api/files/listings/2026/09/icon.svg"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    @Test
    fun `upload rejects SVG files with malicious script tags`() {
        val maliciousSvg = "<svg><script>alert('XSS')</script></svg>".toByteArray()
        val file = MockMultipartFile("file", "xss.svg", "image/svg+xml", maliciousSvg)

        val ex = assertThrows(ApiException::class.java) {
            controller.upload(principal, file, "listings")
        }
        assertTrue(ex.message!!.contains("SVG file contains prohibited scripts"))
    }

    @Test
    fun `upload rejects SVG files with onload handlers`() {
        val maliciousSvg = "<svg onload='alert(1)'></svg>".toByteArray()
        val file = MockMultipartFile("file", "bad.svg", "image/svg+xml", maliciousSvg)

        val ex = assertThrows(ApiException::class.java) {
            controller.upload(principal, file, "listings")
        }
        assertTrue(ex.message!!.contains("SVG file contains prohibited scripts"))
    }

    @Test
    fun `storage publicUrl filters out private prefixes`() {
        val realStorage = StorageService(StorageProperties())
        assertEquals("/api/files/listings/2026/09/photo.jpg", realStorage.publicUrl("listings/2026/09/photo.jpg"))
        assertEquals("/api/files/avatars/2026/09/avatar.png", realStorage.publicUrl("avatars/2026/09/avatar.png"))
        assertNull(realStorage.publicUrl("models/2026/09/model.stl"))
        assertNull(realStorage.publicUrl("chat/2026/09/file.pdf"))
    }
}
