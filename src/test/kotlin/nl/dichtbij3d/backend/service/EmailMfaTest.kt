package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.config.MailProperties
import nl.dichtbij3d.backend.domain.EmailMfaToken
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.domain.User
import nl.dichtbij3d.backend.dto.LoginRequest
import nl.dichtbij3d.backend.dto.MfaVerifyRequest
import nl.dichtbij3d.backend.dto.UserProfileDto
import nl.dichtbij3d.backend.repo.EmailMfaTokenRepository
import nl.dichtbij3d.backend.repo.PasswordResetTokenRepository
import nl.dichtbij3d.backend.repo.RefreshTokenRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.security.ParsedToken
import nl.dichtbij3d.backend.security.TokenService
import nl.dichtbij3d.backend.security.TokenType
import nl.dichtbij3d.backend.security.TotpService
import nl.dichtbij3d.backend.web.ApiException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class EmailMfaTest {

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Mock
    private lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    @Mock
    private lateinit var emailMfaTokenRepository: EmailMfaTokenRepository

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
        from = "noreply@dichtbij3d.nl",
        frontendUrl = "http://localhost:8081",
        mfaTokenTtl = Duration.ofMinutes(10),
    )

    private lateinit var authService: AuthService

    private fun <T : Any> anyNonNull(fallback: T): T {
        any(fallback::class.java)
        return fallback
    }

    private fun <T : Any> eqNonNull(value: T): T {
        eq(value)
        return value
    }

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
            emailMfaTokenRepository = emailMfaTokenRepository,
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
    fun `login with emailMfaEnabled requires MFA and does not dispatch email code`() {
        val user = User(
            id = UUID.randomUUID(),
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        `when`(userRepository.findByEmail("user@example.com")).thenReturn(user)
        `when`(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true)
        `when`(tokenService.createMfaChallengeToken(user)).thenReturn("mfa-challenge-jwt")

        val response = authService.login(
            LoginRequest(email = "user@example.com", password = "Password123!"),
            null
        )

        assertTrue(response.mfaRequired)
        assertEquals("mfa-challenge-jwt", response.mfaToken)
        assertTrue(response.mfaMethods.contains("email"))
        assertFalse(response.mfaMethods.contains("totp"))
        assertEquals("u***r@example.com", response.maskedEmail)

        verify(emailService, never()).sendMfaCodeEmail(anyNonNull(""), anyNonNull(""), anyNonNull(""), anyNonNull(""))
    }

    @Test
    fun `sendLoginEmailMfaCode dispatches email code when requested`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        `when`(tokenService.parse("mfa-challenge-jwt")).thenReturn(
            ParsedToken(
                userId = userId,
                email = "user@example.com",
                displayName = "User",
                roles = setOf(Role.CUSTOMER),
                type = TokenType.MFA,
            )
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(tokenService.hash(anyNonNull(""))).thenAnswer { "hash-" + it.getArgument<String>(0) }
        `when`(emailMfaTokenRepository.findAllByUserIdAndPurposeAndUsedFalse(user.id!!, "LOGIN")).thenReturn(emptyList())

        val res = authService.sendLoginEmailMfaCode("mfa-challenge-jwt")
        assertTrue(res.message.contains("sent to your email"))

        val codeCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(emailService).sendMfaCodeEmail(
            eqNonNull("user@example.com"),
            eqNonNull("User"),
            captureNonNull(codeCaptor, ""),
            eqNonNull("nl")
        )
        assertEquals(6, codeCaptor.value.length)
        assertTrue(codeCaptor.value.all { it.isDigit() })

        val dummyToken = EmailMfaToken(userId = UUID.randomUUID(), codeHash = "", expiresAt = Instant.now())
        verify(emailMfaTokenRepository).save(anyNonNull(dummyToken))
    }

    @Test
    fun `login with emailMfaEnabled and valid mfaCode logs in directly`() {
        val user = User(
            id = UUID.randomUUID(),
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        val token = EmailMfaToken(
            userId = user.id!!,
            codeHash = "hash-123456",
            purpose = "LOGIN",
            expiresAt = Instant.now().plusSeconds(300),
            used = false,
        )

        `when`(userRepository.findByEmail("user@example.com")).thenReturn(user)
        `when`(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true)
        `when`(tokenService.hash(anyNonNull(""))).thenAnswer { "hash-" + it.getArgument<String>(0) }
        `when`(tokenService.refreshTokenTtl).thenReturn(Duration.ofDays(7))
        `when`(tokenService.generateRefreshToken()).thenReturn("refresh-token-xyz")
        `when`(tokenService.createAccessToken(user)).thenReturn("access-token-abc")
        `when`(tokenService.accessTokenTtlSeconds).thenReturn(900L)
        `when`(mapper.profile(user)).thenReturn(mock(UserProfileDto::class.java))
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(user.id!!), eqNonNull("LOGIN"), anyNonNull(Instant.now())
        )).thenReturn(token)

        val response = authService.login(
            LoginRequest(email = "user@example.com", password = "Password123!", mfaCode = "123456"),
            null
        )

        assertFalse(response.mfaRequired)
        assertEquals("access-token-abc", response.accessToken)
        assertTrue(token.used)
        verify(emailMfaTokenRepository).save(token)
    }

    @Test
    fun `verifyMfa succeeds with valid email code`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        val token = EmailMfaToken(
            userId = userId,
            codeHash = "hash-654321",
            purpose = "LOGIN",
            expiresAt = Instant.now().plusSeconds(300),
            used = false,
        )

        `when`(tokenService.parse("valid-mfa-token")).thenReturn(
            ParsedToken(
                userId = userId,
                email = "user@example.com",
                displayName = "User",
                roles = setOf(Role.CUSTOMER),
                type = TokenType.MFA,
            )
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(tokenService.hash(anyNonNull(""))).thenAnswer { "hash-" + it.getArgument<String>(0) }
        `when`(tokenService.refreshTokenTtl).thenReturn(Duration.ofDays(7))
        `when`(tokenService.generateRefreshToken()).thenReturn("refresh-token-xyz")
        `when`(tokenService.createAccessToken(user)).thenReturn("access-token-abc")
        `when`(tokenService.accessTokenTtlSeconds).thenReturn(900L)
        `when`(mapper.profile(user)).thenReturn(mock(UserProfileDto::class.java))
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("LOGIN"), anyNonNull(Instant.now())
        )).thenReturn(token)

        val response = authService.verifyMfa(MfaVerifyRequest(mfaToken = "valid-mfa-token", code = "654321"), null)

        assertEquals("access-token-abc", response.accessToken)
        assertTrue(token.used)
        verify(emailMfaTokenRepository).save(token)
    }

    @Test
    fun `verifyMfa fails with incorrect email code and increments attempts`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        val token = EmailMfaToken(
            userId = userId,
            codeHash = "hash-654321",
            purpose = "LOGIN",
            expiresAt = Instant.now().plusSeconds(300),
            used = false,
            attempts = 0,
        )

        `when`(tokenService.parse("valid-mfa-token")).thenReturn(
            ParsedToken(
                userId = userId,
                email = "user@example.com",
                displayName = "User",
                roles = setOf(Role.CUSTOMER),
                type = TokenType.MFA,
            )
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(tokenService.hash("999999")).thenReturn("hash-999999")
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("LOGIN"), anyNonNull(Instant.now())
        )).thenReturn(token)

        val ex = assertThrows(ApiException::class.java) {
            authService.verifyMfa(MfaVerifyRequest(mfaToken = "valid-mfa-token", code = "999999"), null)
        }
        assertEquals("Invalid verification code", ex.message)
        assertEquals(1, token.attempts)
        assertFalse(token.used)
        verify(emailMfaTokenRepository).save(token)
    }

    @Test
    fun `verifyMfa invalidates token when max attempts reached`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        val token = EmailMfaToken(
            userId = userId,
            codeHash = "hash-654321",
            purpose = "LOGIN",
            expiresAt = Instant.now().plusSeconds(300),
            used = false,
            attempts = 4, // 5th attempt
        )

        `when`(tokenService.parse("valid-mfa-token")).thenReturn(
            ParsedToken(
                userId = userId,
                email = "user@example.com",
                displayName = "User",
                roles = setOf(Role.CUSTOMER),
                type = TokenType.MFA,
            )
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(tokenService.hash("999999")).thenReturn("hash-999999")
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("LOGIN"), anyNonNull(Instant.now())
        )).thenReturn(token)

        val ex = assertThrows(ApiException::class.java) {
            authService.verifyMfa(MfaVerifyRequest(mfaToken = "valid-mfa-token", code = "999999"), null)
        }
        assertEquals("Invalid verification code", ex.message)
        assertEquals(5, token.attempts)
        assertTrue(token.used)
        verify(emailMfaTokenRepository).save(token)
    }

    @Test
    fun `enableEmailMfa counts attempts and invalidates after max attempts`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = false,
        )
        val token = EmailMfaToken(
            userId = userId,
            codeHash = "hash-123456",
            purpose = "ENABLE",
            expiresAt = Instant.now().plusSeconds(300),
            used = false,
            attempts = 4, // 5th attempt
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("ENABLE"), anyNonNull(Instant.now())
        )).thenReturn(token)
        `when`(tokenService.hash("000000")).thenReturn("hash-000000")

        val ex = assertThrows(ApiException::class.java) {
            authService.enableEmailMfa(userId, "000000")
        }
        assertTrue(ex.message.contains("Too many failed attempts"))
        assertEquals(5, token.attempts)
        assertTrue(token.used)
        assertFalse(user.emailMfaEnabled)
    }

    @Test
    fun `verifyMfa supports either TOTP or email code when both are enabled`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            totpEnabled = true,
            totpSecret = "MYSECRET",
            emailMfaEnabled = true,
        )

        `when`(tokenService.parse("valid-mfa-token")).thenReturn(
            ParsedToken(userId = userId, email = "user@example.com", displayName = "User", roles = setOf(Role.CUSTOMER), type = TokenType.MFA)
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(totpService.verify(eqNonNull("MYSECRET"), eqNonNull("123456"), isNull(), anyLong())).thenReturn(100L)
        `when`(tokenService.hash(anyNonNull(""))).thenAnswer { "hash-" + it.getArgument<String>(0) }
        `when`(tokenService.refreshTokenTtl).thenReturn(Duration.ofDays(7))
        `when`(tokenService.generateRefreshToken()).thenReturn("refresh-token-xyz")
        `when`(tokenService.createAccessToken(user)).thenReturn("access-token-abc")
        `when`(tokenService.accessTokenTtlSeconds).thenReturn(900L)
        `when`(mapper.profile(user)).thenReturn(mock(UserProfileDto::class.java))

        // Verifying with TOTP code succeeds even though email MFA is also enabled
        val response = authService.verifyMfa(MfaVerifyRequest(mfaToken = "valid-mfa-token", code = "123456"), null)
        assertEquals("access-token-abc", response.accessToken)
        assertEquals(100L, user.lastTotpStep)
        verify(userRepository).save(user)
    }

    @Test
    fun `verifyMfa rejects replayed TOTP code`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            totpEnabled = true,
            totpSecret = "MYSECRET",
            lastTotpStep = 100L,
            emailMfaEnabled = false,
        )

        `when`(tokenService.parse("valid-mfa-token")).thenReturn(
            ParsedToken(userId = userId, email = "user@example.com", displayName = "User", roles = setOf(Role.CUSTOMER), type = TokenType.MFA)
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        // totpService rejects the replayed code by returning null when step <= lastTotpStep
        `when`(totpService.verify(eqNonNull("MYSECRET"), eqNonNull("123456"), eqNonNull(100L), anyLong())).thenReturn(null)

        val ex = assertThrows(ApiException::class.java) {
            authService.verifyMfa(MfaVerifyRequest(mfaToken = "valid-mfa-token", code = "123456"), null)
        }
        assertEquals("Invalid verification code", ex.message)
    }

    @Test
    fun `startEmailMfaSetup dispatches code and enableEmailMfa enables it`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = false,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("ENABLE"), anyNonNull(Instant.now())
        )).thenReturn(null)
        `when`(emailMfaTokenRepository.findAllByUserIdAndPurposeAndUsedFalse(userId, "ENABLE")).thenReturn(emptyList())
        `when`(tokenService.hash(anyNonNull(""))).thenAnswer { "hash-" + it.getArgument<String>(0) }

        val setupMsg = authService.startEmailMfaSetup(userId)
        assertTrue(setupMsg.message.contains("verification code has been sent"))

        val tokenCaptor = ArgumentCaptor.forClass(EmailMfaToken::class.java)
        verify(emailMfaTokenRepository).save(captureNonNull(tokenCaptor, EmailMfaToken(userId = userId, codeHash = "", expiresAt = Instant.now())))
        val savedToken = tokenCaptor.value

        // Now test enableEmailMfa with the correct code
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("ENABLE"), anyNonNull(Instant.now())
        )).thenReturn(savedToken)

        val codeCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(emailService).sendMfaCodeEmail(
            eqNonNull("user@example.com"),
            eqNonNull("User"),
            captureNonNull(codeCaptor, ""),
            eqNonNull("nl")
        )
        val generatedCode = codeCaptor.value

        authService.enableEmailMfa(userId, generatedCode)
        assertTrue(user.emailMfaEnabled)
        assertTrue(savedToken.used)
        verify(userRepository).save(user)
    }

    @Test
    fun `disableEmailMfa throws when neither password nor code is provided`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))

        val ex = assertThrows(ApiException::class.java) {
            authService.disableEmailMfa(userId)
        }
        assertTrue(ex.message.contains("Verification code or current password is required"))
        assertTrue(user.emailMfaEnabled)
        verify(userRepository, never()).save(user)
    }

    @Test
    fun `disableEmailMfa succeeds with valid password`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("Password123!", "hashed")).thenReturn(true)

        authService.disableEmailMfa(userId, password = "Password123!")
        assertFalse(user.emailMfaEnabled)
        verify(userRepository).save(user)
    }

    @Test
    fun `disableEmailMfa fails with incorrect password`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("WrongPassword!", "hashed")).thenReturn(false)

        val ex = assertThrows(ApiException::class.java) {
            authService.disableEmailMfa(userId, password = "WrongPassword!")
        }
        assertEquals("Your current password is incorrect", ex.message)
        assertTrue(user.emailMfaEnabled)
        verify(userRepository, never()).save(user)
    }

    @Test
    fun `disableEmailMfa succeeds with valid email code and marks token used`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        val token = EmailMfaToken(
            userId = userId,
            codeHash = "hash-123456",
            purpose = "DISABLE",
            expiresAt = Instant.now().plusSeconds(300),
            used = false,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("DISABLE"), anyNonNull(Instant.now())
        )).thenReturn(token)
        `when`(tokenService.hash("123456")).thenReturn("hash-123456")

        authService.disableEmailMfa(userId, code = "123456")
        assertFalse(user.emailMfaEnabled)
        assertTrue(token.used)
        verify(emailMfaTokenRepository).save(token)
        verify(userRepository).save(user)
    }

    @Test
    fun `disableEmailMfa fails with invalid code`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("DISABLE"), anyNonNull(Instant.now())
        )).thenReturn(null)
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("LOGIN"), anyNonNull(Instant.now())
        )).thenReturn(null)
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("ENABLE"), anyNonNull(Instant.now())
        )).thenReturn(null)

        val ex = assertThrows(ApiException::class.java) {
            authService.disableEmailMfa(userId, code = "000000")
        }
        assertEquals("That code is not correct", ex.message)
        assertTrue(user.emailMfaEnabled)
        verify(userRepository, never()).save(user)
    }

    @Test
    fun `sendDisableEmailMfaCode dispatches code when email MFA is enabled`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = true,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            eqNonNull(userId), eqNonNull("DISABLE"), anyNonNull(Instant.now())
        )).thenReturn(null)
        `when`(emailMfaTokenRepository.findAllByUserIdAndPurposeAndUsedFalse(userId, "DISABLE")).thenReturn(emptyList())
        `when`(tokenService.hash(anyNonNull(""))).thenAnswer { "hash-" + it.getArgument<String>(0) }

        val res = authService.sendDisableEmailMfaCode(userId)
        assertTrue(res.message.contains("verification code has been sent"))

        val codeCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(emailService).sendMfaCodeEmail(
            eqNonNull("user@example.com"),
            eqNonNull("User"),
            captureNonNull(codeCaptor, ""),
            eqNonNull("nl")
        )
        assertEquals(6, codeCaptor.value.length)
    }

    @Test
    fun `sendDisableEmailMfaCode throws when email MFA is not enabled`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            passwordHash = "hashed",
            displayName = "User",
            emailMfaEnabled = false,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))

        val ex = assertThrows(ApiException::class.java) {
            authService.sendDisableEmailMfaCode(userId)
        }
        assertEquals("Email two-factor authentication is not enabled", ex.message)
    }
}
