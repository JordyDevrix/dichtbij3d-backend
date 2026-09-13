package nl.dichtbij3d.backend.service

import jakarta.servlet.http.HttpServletRequest
import nl.dichtbij3d.backend.domain.RefreshToken
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.domain.User
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.RefreshTokenRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.security.TokenService
import nl.dichtbij3d.backend.security.TokenType
import nl.dichtbij3d.backend.security.TotpService
import nl.dichtbij3d.backend.web.ApiException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService,
    private val totpService: TotpService,
    private val mapper: DtoMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Used to persist token-family revocation even when the request ends in an error. */
    private val requiresNew = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional
    fun register(request: RegisterRequest, httpRequest: HttpServletRequest?): AuthResponse {
        val email = request.email.trim().lowercase()
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("An account with this email address already exists")
        }
        validatePasswordStrength(request.password)

        val roles = (request.roles ?: setOf(Role.CUSTOMER))
            .filter { it != Role.ADMIN }
            .toMutableSet()
            .ifEmpty { mutableSetOf(Role.CUSTOMER) }

        val user = User(
            email = email,
            passwordHash = passwordEncoder.encode(request.password),
            displayName = request.displayName.trim(),
            locale = normalizeLocale(request.locale),
            roles = roles.toMutableSet(),
        )
        userRepository.save(user)
        log.info("New account registered: {}", email)
        return issueTokens(user, httpRequest)
    }

    @Transactional
    fun login(request: LoginRequest, httpRequest: HttpServletRequest?): AuthResponse {
        val user = userRepository.findByEmail(request.email.trim().lowercase())
        // Always run a hash comparison to keep timing consistent for unknown accounts.
        val matches = if (user == null) {
            passwordEncoder.matches(request.password, DUMMY_HASH)
            false
        } else {
            passwordEncoder.matches(request.password, user.passwordHash)
        }
        if (user == null || !matches) throw ApiException.unauthorized("Invalid email address or password")
        if (!user.enabled) {
            throw ApiException.forbidden(user.disabledReason ?: "This account has been disabled by an administrator")
        }

        if (user.totpEnabled) {
            val code = request.totpCode
            if (code.isNullOrBlank()) {
                return AuthResponse(mfaRequired = true, mfaToken = tokenService.createMfaChallengeToken(user))
            }
            if (!totpService.verify(user.totpSecret!!, code)) {
                throw ApiException.unauthorized("Invalid verification code")
            }
        }
        return issueTokens(user, httpRequest)
    }

    @Transactional
    fun verifyMfa(request: MfaVerifyRequest, httpRequest: HttpServletRequest?): AuthResponse {
        val parsed = tokenService.parse(request.mfaToken)
            ?: throw ApiException.unauthorized("The verification session expired, please sign in again")
        if (parsed.type != TokenType.MFA) throw ApiException.unauthorized("Invalid verification session")
        val user = userRepository.findById(parsed.userId).orElseThrow { ApiException.unauthorized() }
        val secret = user.totpSecret ?: throw ApiException.badRequest("Two-factor authentication is not enabled")
        if (!totpService.verify(secret, request.code)) throw ApiException.unauthorized("Invalid verification code")
        return issueTokens(user, httpRequest)
    }

    @Transactional
    fun refresh(rawToken: String, httpRequest: HttpServletRequest?): AuthResponse {
        if (rawToken.isBlank()) throw ApiException.unauthorized("Missing refresh token")
        val hash = tokenService.hash(rawToken)
        val stored = refreshTokenRepository.findByTokenHash(hash)
            ?: throw ApiException.unauthorized("Invalid refresh token")

        if (stored.revokedAt != null) {
            // Reuse of a rotated token => likely theft, kill the whole family.
            // Runs in its own transaction so it survives the exception below.
            requiresNew.executeWithoutResult {
                refreshTokenRepository.revokeFamily(stored.familyId, Instant.now())
            }
            log.warn("Refresh token reuse detected for user {}", stored.userId)
            throw ApiException.unauthorized("Session expired, please sign in again")
        }
        if (stored.expiresAt.isBefore(Instant.now())) throw ApiException.unauthorized("Session expired")

        val user = userRepository.findById(stored.userId).orElseThrow { ApiException.unauthorized() }
        if (!user.enabled || user.deletedAt != null) throw ApiException.forbidden("This account is no longer active")

        val newRaw = tokenService.generateRefreshToken()
        val newHash = tokenService.hash(newRaw)
        stored.revokedAt = Instant.now()
        stored.replacedBy = newHash
        refreshTokenRepository.save(stored)
        refreshTokenRepository.save(
            RefreshToken(
                userId = user.id!!,
                tokenHash = newHash,
                familyId = stored.familyId,
                expiresAt = Instant.now().plus(tokenService.refreshTokenTtl),
                userAgent = httpRequest?.getHeader("User-Agent")?.take(255),
                ipAddress = httpRequest?.let { clientIp(it) },
            )
        )
        return AuthResponse(
            accessToken = tokenService.createAccessToken(user),
            refreshToken = newRaw,
            expiresIn = tokenService.accessTokenTtlSeconds,
            user = mapper.profile(user),
        )
    }

    @Transactional
    fun logout(rawToken: String?) {
        if (rawToken.isNullOrBlank()) return
        val stored = refreshTokenRepository.findByTokenHash(tokenService.hash(rawToken)) ?: return
        refreshTokenRepository.revokeFamily(stored.familyId, Instant.now())
    }

    @Transactional
    fun logoutEverywhere(userId: UUID) = refreshTokenRepository.revokeAllForUser(userId, Instant.now())

    @Transactional
    fun changePassword(userId: UUID, request: PasswordChangeRequest) {
        val user = userRepository.findById(userId).orElseThrow { ApiException.notFound("User") }
        if (!passwordEncoder.matches(request.currentPassword, user.passwordHash)) {
            throw ApiException.badRequest("Your current password is incorrect")
        }
        validatePasswordStrength(request.newPassword)
        user.passwordHash = passwordEncoder.encode(request.newPassword)
        userRepository.save(user)
        refreshTokenRepository.revokeAllForUser(userId, Instant.now())
    }

    @Transactional
    fun issueTokens(user: User, httpRequest: HttpServletRequest?): AuthResponse {
        user.lastLoginAt = Instant.now()
        userRepository.save(user)
        val raw = tokenService.generateRefreshToken()
        refreshTokenRepository.save(
            RefreshToken(
                userId = user.id!!,
                tokenHash = tokenService.hash(raw),
                familyId = UUID.randomUUID(),
                expiresAt = Instant.now().plus(tokenService.refreshTokenTtl),
                userAgent = httpRequest?.getHeader("User-Agent")?.take(255),
                ipAddress = httpRequest?.let { clientIp(it) },
            )
        )
        return AuthResponse(
            accessToken = tokenService.createAccessToken(user),
            refreshToken = raw,
            expiresIn = tokenService.accessTokenTtlSeconds,
            user = mapper.profile(user),
        )
    }

    // ------------------------------------------------------------ TOTP

    @Transactional
    fun startTotpSetup(userId: UUID): TotpSetupResponse {
        val user = userRepository.findById(userId).orElseThrow { ApiException.notFound("User") }
        if (user.totpEnabled) throw ApiException.conflict("Two-factor authentication is already enabled")
        val secret = totpService.generateSecret()
        user.totpSecret = secret
        userRepository.save(user)
        return TotpSetupResponse(secret, totpService.provisioningUri(secret, user.email))
    }

    @Transactional
    fun enableTotp(userId: UUID, code: String) {
        val user = userRepository.findById(userId).orElseThrow { ApiException.notFound("User") }
        val secret = user.totpSecret ?: throw ApiException.badRequest("Start the setup first")
        if (!totpService.verify(secret, code)) throw ApiException.badRequest("That code is not correct")
        user.totpEnabled = true
        userRepository.save(user)
    }

    @Transactional
    fun disableTotp(userId: UUID, code: String) {
        val user = userRepository.findById(userId).orElseThrow { ApiException.notFound("User") }
        val secret = user.totpSecret ?: return
        if (!totpService.verify(secret, code)) throw ApiException.badRequest("That code is not correct")
        user.totpEnabled = false
        user.totpSecret = null
        userRepository.save(user)
    }

    private fun validatePasswordStrength(password: String) {
        if (password.length < 10) throw ApiException.badRequest("Password must be at least 10 characters long")
        val hasLetter = password.any { it.isLetter() }
        val hasDigitOrSymbol = password.any { it.isDigit() || !it.isLetterOrDigit() }
        if (!hasLetter || !hasDigitOrSymbol) {
            throw ApiException.badRequest("Password must contain letters and at least one digit or symbol")
        }
        if (password.lowercase() in WEAK_PASSWORDS) throw ApiException.badRequest("This password is too common")
    }

    private fun normalizeLocale(locale: String?): String =
        locale?.lowercase()?.take(2)?.takeIf { it in setOf("nl", "en", "de", "fr") } ?: "nl"

    private fun clientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        return (forwarded?.split(",")?.firstOrNull()?.trim() ?: request.remoteAddr ?: "unknown").take(64)
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    fun purgeExpiredTokens() = refreshTokenRepository.deleteExpired(Instant.now())

    companion object {
        /** Pre-computed Argon2id hash used to equalise login timing for unknown accounts. */
        private const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHRzb21lc2FsdA\$Nx0pKrEqvJZmVL0h9V0bW/aXK0i9JhqCz5lK1x3f4kE"
        private val WEAK_PASSWORDS = setOf(
            "password123", "welkom12345", "wachtwoord1", "qwertyuiop1", "1234567890",
        )
    }
}
