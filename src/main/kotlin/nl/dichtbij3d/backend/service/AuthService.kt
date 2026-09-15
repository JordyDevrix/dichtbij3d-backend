package nl.dichtbij3d.backend.service

import jakarta.servlet.http.HttpServletRequest
import nl.dichtbij3d.backend.config.MailProperties
import nl.dichtbij3d.backend.domain.EmailMfaToken
import nl.dichtbij3d.backend.domain.PasswordResetToken
import nl.dichtbij3d.backend.domain.RefreshToken
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.domain.User
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.EmailMfaTokenRepository
import nl.dichtbij3d.backend.repo.PasswordResetTokenRepository
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
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val emailMfaTokenRepository: EmailMfaTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService,
    private val totpService: TotpService,
    private val emailService: EmailService,
    private val mailProperties: MailProperties,
    private val mapper: DtoMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

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

        val mfaRequired = user.totpEnabled || user.emailMfaEnabled
        if (mfaRequired) {
            val code = (request.mfaCode ?: request.totpCode)?.trim()?.ifBlank { null }
            if (!code.isNullOrBlank()) {
                var verified = false
                if (user.totpEnabled && user.totpSecret != null) {
                    if (totpService.verify(user.totpSecret!!, code)) {
                        verified = true
                    }
                }
                if (!verified && user.emailMfaEnabled) {
                    val token = emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        user.id!!, "LOGIN", Instant.now()
                    )
                    if (token != null && token.codeHash == tokenService.hash(code)) {
                        token.used = true
                        emailMfaTokenRepository.save(token)
                        verified = true
                    }
                }
                if (!verified) {
                    throw ApiException.unauthorized("Invalid verification code")
                }
            } else {
                val mfaToken = tokenService.createMfaChallengeToken(user)
                val methods = buildSet {
                    if (user.totpEnabled) add("totp")
                    if (user.emailMfaEnabled) add("email")
                }
                return AuthResponse(
                    mfaRequired = true,
                    mfaToken = mfaToken,
                    mfaMethods = methods,
                    maskedEmail = if (user.emailMfaEnabled) maskEmail(user.email) else null,
                )
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
        if (!user.totpEnabled && !user.emailMfaEnabled) {
            throw ApiException.badRequest("Two-factor authentication is not enabled")
        }

        val code = request.code.trim()
        var verified = false

        if (user.totpEnabled && user.totpSecret != null) {
            if (totpService.verify(user.totpSecret!!, code)) {
                verified = true
            }
        }

        if (!verified && user.emailMfaEnabled) {
            val token = emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                user.id!!, "LOGIN", Instant.now()
            )
            if (token != null && token.codeHash == tokenService.hash(code)) {
                token.used = true
                emailMfaTokenRepository.save(token)
                verified = true
            }
        }

        if (!verified) throw ApiException.unauthorized("Invalid verification code")
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

    // ------------------------------------------------------------ Email MFA

    @Transactional
    fun sendLoginEmailMfaCode(mfaToken: String): MessageResponse {
        val parsed = tokenService.parse(mfaToken)
            ?: throw ApiException.unauthorized("The verification session expired, please sign in again")
        if (parsed.type != TokenType.MFA) throw ApiException.unauthorized("Invalid verification session")
        val user = userRepository.findById(parsed.userId).orElseThrow { ApiException.unauthorized() }
        if (!user.emailMfaEnabled) {
            throw ApiException.badRequest("Email two-factor authentication is not enabled for this account")
        }

        val recent = emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            user.id!!, "LOGIN", Instant.now()
        )
        if (recent != null && recent.createdAt.isAfter(Instant.now().minusSeconds(30))) {
            throw ApiException.badRequest("Please wait a moment before requesting another code")
        }

        issueAndSendEmailMfaCode(user, "LOGIN")
        return MessageResponse("A new verification code has been sent to your email address")
    }

    @Transactional
    fun startEmailMfaSetup(userId: UUID): MessageResponse {
        val user = userRepository.findById(userId).orElseThrow { ApiException.notFound("User") }
        if (user.emailMfaEnabled) throw ApiException.conflict("Email two-factor authentication is already enabled")

        val recent = emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            user.id!!, "ENABLE", Instant.now()
        )
        if (recent != null && recent.createdAt.isAfter(Instant.now().minusSeconds(30))) {
            throw ApiException.badRequest("Please wait a moment before requesting another code")
        }

        issueAndSendEmailMfaCode(user, "ENABLE")
        return MessageResponse("A verification code has been sent to ${user.email}")
    }

    @Transactional
    fun enableEmailMfa(userId: UUID, code: String) {
        val user = userRepository.findById(userId).orElseThrow { ApiException.notFound("User") }
        val cleanCode = code.trim()
        val token = emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            user.id!!, "ENABLE", Instant.now()
        ) ?: throw ApiException.badRequest("No pending verification code found. Please request a new one.")

        if (token.codeHash != tokenService.hash(cleanCode)) {
            throw ApiException.badRequest("That code is not correct")
        }

        token.used = true
        emailMfaTokenRepository.save(token)

        user.emailMfaEnabled = true
        userRepository.save(user)
        log.info("Email MFA enabled for user: {}", user.email)
    }

    @Transactional
    fun disableEmailMfa(userId: UUID, code: String? = null, password: String? = null) {
        val user = userRepository.findById(userId).orElseThrow { ApiException.notFound("User") }
        if (!user.emailMfaEnabled) return

        if (!password.isNullOrBlank()) {
            if (!passwordEncoder.matches(password, user.passwordHash)) {
                throw ApiException.badRequest("Your current password is incorrect")
            }
        } else if (!code.isNullOrBlank()) {
            val cleanCode = code.trim()
            val token = emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                user.id!!, "LOGIN", Instant.now()
            ) ?: emailMfaTokenRepository.findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                user.id!!, "ENABLE", Instant.now()
            )
            val matchesEmail = token != null && token.codeHash == tokenService.hash(cleanCode)
            val matchesTotp = user.totpEnabled && user.totpSecret != null && totpService.verify(user.totpSecret!!, cleanCode)
            if (!matchesEmail && !matchesTotp) {
                throw ApiException.badRequest("That code is not correct")
            }
        }

        user.emailMfaEnabled = false
        userRepository.save(user)
        log.info("Email MFA disabled for user: {}", user.email)
    }

    private fun issueAndSendEmailMfaCode(user: User, purpose: String): String {
        val existing = emailMfaTokenRepository.findAllByUserIdAndPurposeAndUsedFalse(user.id!!, purpose)
        existing.forEach { it.used = true }
        if (existing.isNotEmpty()) {
            emailMfaTokenRepository.saveAll(existing)
        }

        val code = "%06d".format(secureRandom.nextInt(1_000_000))
        val tokenHash = tokenService.hash(code)
        val expiresAt = Instant.now().plus(mailProperties.mfaTokenTtl)

        emailMfaTokenRepository.save(
            EmailMfaToken(
                userId = user.id!!,
                codeHash = tokenHash,
                purpose = purpose,
                expiresAt = expiresAt,
            )
        )

        emailService.sendMfaCodeEmail(user.email, user.displayName, code, user.locale)
        return code
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

    @Transactional
    fun requestPasswordReset(emailInput: String) {
        val email = emailInput.trim().lowercase()
        val user = userRepository.findByEmail(email)
        if (user == null || !user.enabled) {
            log.info("Password reset requested for unknown or disabled email: {}", email)
            return
        }

        val rawToken = tokenService.generateRefreshToken()
        val tokenHash = tokenService.hash(rawToken)
        val expiresAt = Instant.now().plus(mailProperties.resetTokenTtl)

        passwordResetTokenRepository.save(
            PasswordResetToken(
                userId = user.id!!,
                tokenHash = tokenHash,
                expiresAt = expiresAt,
            )
        )

        val resetUrl = "${mailProperties.frontendUrl.trimEnd('/')}/auth/reset-password?token=$rawToken"
        emailService.sendPasswordResetEmail(user.email, user.displayName, resetUrl, user.locale)
    }

    @Transactional
    fun resetPassword(rawToken: String, newPassword: String) {
        val tokenHash = tokenService.hash(rawToken.trim())
        val resetToken = passwordResetTokenRepository.findByTokenHash(tokenHash)
            ?: throw ApiException.badRequest("Invalid or expired password reset link")

        if (resetToken.used) {
            throw ApiException.badRequest("This password reset link has already been used")
        }

        if (resetToken.expiresAt.isBefore(Instant.now())) {
            throw ApiException.badRequest("This password reset link has expired. Please request a new one.")
        }

        validatePasswordStrength(newPassword)

        val user = userRepository.findById(resetToken.userId).orElseThrow { ApiException.notFound("User") }
        user.passwordHash = passwordEncoder.encode(newPassword)
        userRepository.save(user)

        resetToken.used = true
        passwordResetTokenRepository.save(resetToken)

        // Revoke active sessions across all devices for security
        refreshTokenRepository.revokeAllForUser(user.id!!, Instant.now())
        log.info("Password successfully reset for user: {}", user.id)
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    fun purgeExpiredTokens() {
        val now = Instant.now()
        refreshTokenRepository.deleteExpired(now)
        passwordResetTokenRepository.deleteExpiredOrUsed(now)
        emailMfaTokenRepository.deleteExpiredOrUsed(now)
    }

    private fun maskEmail(email: String): String {
        val atIndex = email.indexOf('@')
        if (atIndex <= 0 || atIndex == email.length - 1) return email
        val name = email.substring(0, atIndex)
        val domain = email.substring(atIndex + 1)
        val maskedName = when {
            name.length <= 1 -> "*"
            name.length == 2 -> "${name[0]}*"
            else -> "${name.first()}***${name.last()}"
        }
        return "$maskedName@$domain"
    }

    companion object {
        /** Pre-computed Argon2id hash used to equalise login timing for unknown accounts. */
        private const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHRzb21lc2FsdA\$Nx0pKrEqvJZmVL0h9V0bW/aXK0i9JhqCz5lK1x3f4kE"
        private val WEAK_PASSWORDS = setOf(
            "password123", "welkom12345", "wachtwoord1", "qwertyuiop1", "1234567890",
        )
    }
}
