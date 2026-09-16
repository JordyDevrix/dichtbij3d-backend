package nl.dichtbij3d.backend.service

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import nl.dichtbij3d.backend.web.ApiException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Collections

data class GoogleUserInfo(
    val googleId: String,
    val email: String,
    val emailVerified: Boolean,
    val name: String?,
    val pictureUrl: String?,
)

@Service
class GoogleAuthService(
    @Value("\${dichtbij3d.google.client-id:\${GOOGLE_OAUTH_CLIENT_ID:\${GOOGLE_CLIENT_ID:}}}")
    private val rawClientId: String,
) {
    private val log = LoggerFactory.getLogger(GoogleAuthService::class.java)
    val clientId: String? = rawClientId.trim().ifBlank { null }
    val isEnabled: Boolean get() = clientId != null

    private val verifier: GoogleIdTokenVerifier? by lazy {
        if (clientId == null) null
        else {
            GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build()
        }
    }

    fun verifyToken(idTokenString: String): GoogleUserInfo {
        if (!isEnabled || verifier == null) {
            throw ApiException.badRequest("Google Sign-In is not configured on this server")
        }
        val idToken: GoogleIdToken? = try {
            verifier!!.verify(idTokenString)
        } catch (e: Exception) {
            log.warn("Failed to verify Google ID Token: {}", e.message)
            null
        }

        if (idToken == null) {
            throw ApiException.unauthorized("Invalid or expired Google token")
        }

        val payload = idToken.payload
        val email = payload.email?.trim()?.lowercase()
            ?: throw ApiException.unauthorized("Google token is missing an email address")
        val emailVerified = payload.emailVerified ?: false
        if (!emailVerified) {
            throw ApiException.unauthorized("Google account email is not verified")
        }

        val googleId = payload.subject
            ?: throw ApiException.unauthorized("Google token is missing a subject identifier")
        val name = (payload["name"] as? String) ?: (payload["given_name"] as? String)
        val picture = payload["picture"] as? String

        return GoogleUserInfo(
            googleId = googleId,
            email = email,
            emailVerified = emailVerified,
            name = name,
            pictureUrl = picture,
        )
    }
}
