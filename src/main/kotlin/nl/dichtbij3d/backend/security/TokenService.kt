package nl.dichtbij3d.backend.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import nl.dichtbij3d.backend.config.JwtProperties
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.domain.User
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

enum class TokenType { ACCESS, MFA }

data class ParsedToken(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val roles: Set<Role>,
    val type: TokenType,
)

@Service
class TokenService(private val props: JwtProperties) {

    private val key: SecretKey = Keys.hmacShaKeyFor(props.secret.toByteArray(StandardCharsets.UTF_8))
    private val random = SecureRandom()

    fun createAccessToken(user: User): String = build(user, TokenType.ACCESS, props.accessTokenTtl.seconds)

    /** Short lived token handed out after password check when MFA is still pending. */
    fun createMfaChallengeToken(user: User): String = build(user, TokenType.MFA, props.mfaChallengeTtl.seconds)

    private fun build(user: User, type: TokenType, ttlSeconds: Long): String {
        val now = Instant.now()
        return Jwts.builder()
            .issuer(props.issuer)
            .subject(user.id.toString())
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)))
            .claim("typ", type.name)
            .claim("email", user.email)
            .claim("name", user.displayName)
            .claim("roles", user.roles.map { it.name })
            .signWith(key, Jwts.SIG.HS512)
            .compact()
    }

    fun parse(token: String): ParsedToken? = try {
        val claims: Claims = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(props.issuer)
            .build()
            .parseSignedClaims(token)
            .payload
        @Suppress("UNCHECKED_CAST")
        val roles = (claims["roles"] as? List<String> ?: emptyList())
            .mapNotNull { runCatching { Role.valueOf(it) }.getOrNull() }
            .toSet()
        ParsedToken(
            userId = UUID.fromString(claims.subject),
            email = claims["email"] as? String ?: "",
            displayName = claims["name"] as? String ?: "",
            roles = roles,
            type = runCatching { TokenType.valueOf(claims["typ"] as String) }.getOrDefault(TokenType.ACCESS),
        )
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Opaque, high entropy refresh token. Only its SHA-256 digest is persisted. */
    fun generateRefreshToken(): String {
        val bytes = ByteArray(48)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    val accessTokenTtlSeconds: Long get() = props.accessTokenTtl.seconds
    val refreshTokenTtl get() = props.refreshTokenTtl
}
