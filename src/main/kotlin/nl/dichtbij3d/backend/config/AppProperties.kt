package nl.dichtbij3d.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "dichtbij3d.jwt")
data class JwtProperties(
    val secret: String,
    val issuer: String = "dichtbij3d",
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshTokenTtl: Duration = Duration.ofDays(30),
    val mfaChallengeTtl: Duration = Duration.ofMinutes(5),
)

@ConfigurationProperties(prefix = "dichtbij3d.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = emptyList(),
)

@ConfigurationProperties(prefix = "dichtbij3d.webauthn")
data class WebAuthnProperties(
    val rpId: String = "localhost",
    val rpName: String = "Dichtbij3D",
    val origins: List<String> = listOf("http://localhost:8081"),
)

@ConfigurationProperties(prefix = "dichtbij3d.storage")
data class StorageProperties(
    val endpoint: String = "http://localhost:9000",
    val accessKey: String = "dichtbij3d",
    val secretKey: String = "dichtbij3d-secret",
    val bucket: String = "dichtbij3d",
    val fallbackDirectory: String = "./.storage",
)

@ConfigurationProperties(prefix = "dichtbij3d.views")
data class ViewProperties(
    val dedupWindow: Duration = Duration.ofHours(12),
    val minDwellMillis: Long = 1500,
)

@ConfigurationProperties(prefix = "dichtbij3d.admin")
data class AdminProperties(
    val email: String = "admin@dichtbij3d.nl",
    val password: String = "Admin123!",
)

@ConfigurationProperties(prefix = "dichtbij3d.mail")
data class MailProperties(
    val from: String = "noreply@jordydevrix.com",
    val fromName: String = "Dichtbij3D",
    val frontendUrl: String = "http://localhost:8081",
    val resetTokenTtl: Duration = Duration.ofMinutes(30),
)

