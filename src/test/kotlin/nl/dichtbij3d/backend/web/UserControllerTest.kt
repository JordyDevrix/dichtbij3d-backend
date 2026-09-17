package nl.dichtbij3d.backend.web

import com.fasterxml.jackson.databind.ObjectMapper
import nl.dichtbij3d.backend.domain.Advert
import nl.dichtbij3d.backend.domain.AdvertStatus
import nl.dichtbij3d.backend.domain.AdvertType
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.domain.User
import nl.dichtbij3d.backend.dto.DeleteAccountRequest
import nl.dichtbij3d.backend.repo.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.BlockService
import nl.dichtbij3d.backend.service.DtoMapper
import nl.dichtbij3d.backend.service.StorageService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.Instant
import java.util.*

class UserControllerTest {

    private lateinit var userRepository: UserRepository
    private lateinit var storageService: StorageService
    private lateinit var blockService: BlockService
    private lateinit var mapper: DtoMapper
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var refreshTokenRepository: RefreshTokenRepository
    private lateinit var passkeyRepository: PasskeyCredentialRepository
    private lateinit var advertRepository: AdvertRepository
    private lateinit var auditLogRepository: AuditLogRepository
    private lateinit var controller: UserController
    private lateinit var mockMvc: MockMvc
    private val objectMapper = ObjectMapper()

    private val userId = UUID.randomUUID()
    private val testPrincipal = AppPrincipal(
        id = userId,
        email = "test@example.com",
        displayName = "Test User",
        roles = setOf(Role.CUSTOMER),
    )

    @BeforeEach
    fun setup() {
        userRepository = mock(UserRepository::class.java)
        storageService = mock(StorageService::class.java)
        blockService = mock(BlockService::class.java)
        mapper = mock(DtoMapper::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        refreshTokenRepository = mock(RefreshTokenRepository::class.java)
        passkeyRepository = mock(PasskeyCredentialRepository::class.java)
        advertRepository = mock(AdvertRepository::class.java)
        auditLogRepository = mock(AuditLogRepository::class.java)

        controller = UserController(
            userRepository,
            storageService,
            blockService,
            mapper,
            passwordEncoder,
            refreshTokenRepository,
            passkeyRepository,
            advertRepository,
            auditLogRepository
        )

        val principalResolver = object : HandlerMethodArgumentResolver {
            override fun supportsParameter(parameter: MethodParameter): Boolean =
                parameter.parameterType == AppPrincipal::class.java

            override fun resolveArgument(
                parameter: MethodParameter,
                mavContainer: ModelAndViewContainer?,
                webRequest: NativeWebRequest,
                binderFactory: WebDataBinderFactory?
            ): Any = testPrincipal
        }

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(principalResolver)
            .build()
    }

    @Test
    fun `deleteMe successfully anonymizes user and revokes tokens when correct password is provided`() {
        val user = User(
            id = userId,
            email = "test@example.com",
            passwordHash = "\$2a\$10\$hash",
            displayName = "Test User",
            bio = "Some bio",
            avatarKey = "avatars/sample.png",
            roles = mutableSetOf(Role.CUSTOMER)
        )
        val advert = Advert(
            id = UUID.randomUUID(),
            author = user,
            type = AdvertType.PRINT_REQUEST,
            title = "Test Advert",
            description = "Test Description",
            status = AdvertStatus.OPEN
        )

        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("correctPassword", "\$2a\$10\$hash")).thenReturn(true)
        `when`(advertRepository.findAllByAuthorIdAndDeletedAtIsNull(userId)).thenReturn(listOf(advert))

        val request = DeleteAccountRequest(password = "correctPassword", reason = "Leaving platform")

        mockMvc.perform(
            delete("/api/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("Account successfully deleted"))

        verify(storageService).delete("avatars/sample.png")
        verify(refreshTokenRepository).revokeAllForUser(any(UUID::class.java) ?: userId, any(Instant::class.java) ?: Instant.now())
        verify(passkeyRepository).deleteAllByUserId(userId)
        verify(advertRepository).saveAll(anyList())
        verify(auditLogRepository).save(any())
        verify(userRepository).save(user)

        assertEquals("deleted-$userId@dichtbij3d.invalid", user.email)
        assertEquals("Verwijderde gebruiker", user.displayName)
        assertEquals("", user.passwordHash)
        assertNull(user.bio)
        assertNull(user.avatarKey)
        assertFalse(user.enabled)
        assertNotNull(user.deletedAt)
        assertEquals("Leaving platform", user.disabledReason)
        assertEquals(AdvertStatus.CANCELLED, advert.status)
        assertNotNull(advert.deletedAt)
    }

    @Test
    fun `deleteMe succeeds without password for OAuth users`() {
        val oauthUser = User(
            id = userId,
            email = "oauth@example.com",
            googleId = "google-sub-12345",
            displayName = "Google User",
            roles = mutableSetOf(Role.CUSTOMER)
        )

        `when`(userRepository.findById(userId)).thenReturn(Optional.of(oauthUser))
        `when`(advertRepository.findAllByAuthorIdAndDeletedAtIsNull(userId)).thenReturn(emptyList())

        val request = DeleteAccountRequest(reason = "No longer needed")

        mockMvc.perform(
            delete("/api/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)

        verify(userRepository).save(oauthUser)
        assertEquals("deleted-$userId@dichtbij3d.invalid", oauthUser.email)
        assertNull(oauthUser.googleId)
    }

    @Test
    fun `deleteMe fails when wrong password is provided`() {
        val user = User(
            id = userId,
            email = "test@example.com",
            passwordHash = "\$2a\$10\$hash",
            displayName = "Test User"
        )

        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("wrongPassword", "\$2a\$10\$hash")).thenReturn(false)

        val request = DeleteAccountRequest(password = "wrongPassword")

        mockMvc.perform(
            delete("/api/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Incorrect password"))

        verify(userRepository, never()).save(user)
    }
}
