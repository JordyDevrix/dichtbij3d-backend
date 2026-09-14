package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.config.MailProperties
import nl.dichtbij3d.backend.domain.PasswordResetToken
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.domain.User
import nl.dichtbij3d.backend.repo.PasswordResetTokenRepository
import nl.dichtbij3d.backend.repo.RefreshTokenRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.security.TokenService
import nl.dichtbij3d.backend.security.TotpService
import nl.dichtbij3d.backend.web.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class PasswordResetTest {

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Mock
    private lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    @Mock
    private lateinit var tokenService: TokenService

    @Mock
    private lateinit var totpService: TotpService

    @Mock
    private lateinit var emailService: EmailService

    @Mock
    private lateinit var mapper: DtoMapper

    @Mock
    private lateinit var transactionManager: PlatformTransactionManager

    private val mailProperties = MailProperties(
        from = "noreply@jordydevrix.com",
        frontendUrl = "http://localhost:8081",
        resetTokenTtl = Duration.ofMinutes(30),
    )

    private lateinit var authService: AuthService

    private fun <T> captureNonNull(captor: ArgumentCaptor<T>, fallback: T): T {
        captor.capture()
        return fallback
    }

    @BeforeEach
    fun setUp() {
        authService = AuthService(
            userRepository = userRepository,
            refreshTokenRepository = refreshTokenRepository,
            passwordResetTokenRepository = passwordResetTokenRepository,
            passwordEncoder = passwordEncoder,
            tokenService = tokenService,
            totpService = totpService,
            emailService = emailService,
            mailProperties = mailProperties,
            mapper = mapper,
            transactionManager = transactionManager,
        )
    }

    @Test
    fun `requestPasswordReset sends email when user exists and enabled`() {
        val user = User(
            id = UUID.randomUUID(),
            email = "user@example.com",
            passwordHash = "hash",
            displayName = "Test User",
            roles = mutableSetOf(Role.CUSTOMER),
            enabled = true,
            locale = "nl",
        )
        `when`(userRepository.findByEmail("user@example.com")).thenReturn(user)
        `when`(tokenService.generateRefreshToken()).thenReturn("plain-token-123")
        `when`(tokenService.hash("plain-token-123")).thenReturn("hashed-token-xyz")

        authService.requestPasswordReset("user@example.com")

        val tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken::class.java)
        verify(passwordResetTokenRepository).save(tokenCaptor.capture())
        assertEquals("hashed-token-xyz", tokenCaptor.value.tokenHash)
        assertEquals(user.id, tokenCaptor.value.userId)

        val emailCaptor = ArgumentCaptor.forClass(String::class.java)
        val nameCaptor = ArgumentCaptor.forClass(String::class.java)
        val urlCaptor = ArgumentCaptor.forClass(String::class.java)
        val localeCaptor = ArgumentCaptor.forClass(String::class.java)

        verify(emailService).sendPasswordResetEmail(
            captureNonNull(emailCaptor, ""),
            captureNonNull(nameCaptor, ""),
            captureNonNull(urlCaptor, ""),
            captureNonNull(localeCaptor, ""),
        )
        assertEquals("user@example.com", emailCaptor.value)
        assertEquals("Test User", nameCaptor.value)
        assertEquals("http://localhost:8081/auth/reset-password?token=plain-token-123", urlCaptor.value)
        assertEquals("nl", localeCaptor.value)
    }

    @Test
    fun `requestPasswordReset does not send email or save token when user not found`() {
        `when`(userRepository.findByEmail("unknown@example.com")).thenReturn(null)

        authService.requestPasswordReset("unknown@example.com")

        verifyNoInteractions(passwordResetTokenRepository)
        verifyNoInteractions(emailService)
    }

    @Test
    fun `resetPassword successfully updates password and marks token used`() {
        val userId = UUID.randomUUID()
        val rawToken = "my-secret-token"
        val tokenHash = "hashed-secret-token"

        val resetToken = PasswordResetToken(
            id = UUID.randomUUID(),
            userId = userId,
            tokenHash = tokenHash,
            expiresAt = Instant.now().plusSeconds(1800),
            used = false,
        )
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "old-hash",
            displayName = "Test User",
            roles = mutableSetOf(Role.CUSTOMER),
        )

        `when`(tokenService.hash(rawToken)).thenReturn(tokenHash)
        `when`(passwordResetTokenRepository.findByTokenHash(tokenHash)).thenReturn(resetToken)
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.encode("ValidNewPass123!")).thenReturn("new-hash-456")

        authService.resetPassword(rawToken, "ValidNewPass123!")

        assertTrue(resetToken.used)
        assertEquals("new-hash-456", user.passwordHash)
        verify(userRepository).save(user)
        verify(passwordResetTokenRepository).save(resetToken)

        val userCaptor = ArgumentCaptor.forClass(UUID::class.java)
        val instantCaptor = ArgumentCaptor.forClass(Instant::class.java)
        verify(refreshTokenRepository).revokeAllForUser(
            captureNonNull(userCaptor, userId),
            captureNonNull(instantCaptor, Instant.now()),
        )
        assertEquals(userId, userCaptor.value)
    }

    @Test
    fun `resetPassword fails when token is expired`() {
        val userId = UUID.randomUUID()
        val rawToken = "expired-token"
        val tokenHash = "hashed-expired"

        val resetToken = PasswordResetToken(
            id = UUID.randomUUID(),
            userId = userId,
            tokenHash = tokenHash,
            expiresAt = Instant.now().minusSeconds(10),
            used = false,
        )

        `when`(tokenService.hash(rawToken)).thenReturn(tokenHash)
        `when`(passwordResetTokenRepository.findByTokenHash(tokenHash)).thenReturn(resetToken)

        val ex = assertThrows(ApiException::class.java) {
            authService.resetPassword(rawToken, "ValidNewPass123!")
        }
        assertTrue(ex.message?.contains("expired") == true)
    }

    @Test
    fun `resetPassword fails when token already used`() {
        val userId = UUID.randomUUID()
        val rawToken = "used-token"
        val tokenHash = "hashed-used"

        val resetToken = PasswordResetToken(
            id = UUID.randomUUID(),
            userId = userId,
            tokenHash = tokenHash,
            expiresAt = Instant.now().plusSeconds(1800),
            used = true,
        )

        `when`(tokenService.hash(rawToken)).thenReturn(tokenHash)
        `when`(passwordResetTokenRepository.findByTokenHash(tokenHash)).thenReturn(resetToken)

        val ex = assertThrows(ApiException::class.java) {
            authService.resetPassword(rawToken, "ValidNewPass123!")
        }
        assertTrue(ex.message?.contains("already been used") == true)
    }
}
