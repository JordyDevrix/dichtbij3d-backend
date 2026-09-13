package nl.dichtbij3d.backend.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import jakarta.servlet.http.HttpServletRequest
import nl.dichtbij3d.backend.config.WebAuthnProperties
import nl.dichtbij3d.backend.domain.PasskeyCredential
import nl.dichtbij3d.backend.domain.WebAuthnChallenge
import nl.dichtbij3d.backend.domain.WebAuthnPurpose
import nl.dichtbij3d.backend.dto.AuthResponse
import nl.dichtbij3d.backend.dto.PasskeyDto
import nl.dichtbij3d.backend.repo.PasskeyCredentialRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.repo.WebAuthnChallengeRepository
import nl.dichtbij3d.backend.web.ApiException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Passkey (WebAuthn / FIDO2) registration and passwordless sign-in.
 *
 * Discoverable credentials are requested so users can sign in without typing an email address.
 */
@Service
class PasskeyService(
    private val props: WebAuthnProperties,
    private val challengeRepository: WebAuthnChallengeRepository,
    private val passkeyRepository: PasskeyCredentialRepository,
    private val userRepository: UserRepository,
    private val authService: AuthService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val webAuthnManager: WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val converter = ObjectConverter()
    private val attestedCredentialDataConverter = AttestedCredentialDataConverter(converter)
    private val b64url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    // ------------------------------------------------------------- registration

    @Transactional
    fun registrationOptions(userId: UUID): Map<String, Any?> {
        val user = userRepository.findById(userId).orElseThrow { ApiException.notFound("User") }
        val challenge = newChallenge(userId, WebAuthnPurpose.REGISTRATION)
        val existing = passkeyRepository.findAllByUserId(userId).map {
            mapOf("type" to "public-key", "id" to it.credentialId)
        }
        return mapOf(
            "challenge" to challenge,
            "rp" to mapOf("id" to props.rpId, "name" to props.rpName),
            "user" to mapOf(
                "id" to b64url.encodeToString(uuidToBytes(userId)),
                "name" to user.email,
                "displayName" to user.displayName,
            ),
            "pubKeyCredParams" to listOf(
                mapOf("type" to "public-key", "alg" to -7),   // ES256
                mapOf("type" to "public-key", "alg" to -257), // RS256
            ),
            "timeout" to 120_000,
            "attestation" to "none",
            "excludeCredentials" to existing,
            "authenticatorSelection" to mapOf(
                "residentKey" to "preferred",
                "requireResidentKey" to false,
                "userVerification" to "preferred",
            ),
        )
    }

    @Transactional
    fun finishRegistration(userId: UUID, credential: Map<String, Any?>, label: String?): PasskeyDto {
        val response = credential.nested("response") ?: throw ApiException.badRequest("Malformed passkey response")
        val clientDataJSON = decode(response["clientDataJSON"])
        val attestationObject = decode(response["attestationObject"])
        val stored = consumeChallenge(clientDataJSON, WebAuthnPurpose.REGISTRATION)
        if (stored.userId != userId) throw ApiException.forbidden("Challenge does not belong to this account")

        val serverProperty = ServerProperty(origins(), props.rpId, DefaultChallenge(b64urlDecoder.decode(stored.challenge)), null)
        val registrationData = try {
            webAuthnManager.verify(
                RegistrationRequest(attestationObject, clientDataJSON),
                RegistrationParameters(serverProperty, null, false, true),
            )
        } catch (ex: Exception) {
            log.warn("Passkey registration verification failed: {}", ex.message)
            throw ApiException.badRequest("Passkey could not be verified")
        }

        val attestedCredentialData = registrationData.attestationObject?.authenticatorData?.attestedCredentialData
            ?: throw ApiException.badRequest("Passkey is missing credential data")
        val credentialId = b64url.encodeToString(attestedCredentialData.credentialId)
        if (passkeyRepository.findByCredentialId(credentialId) != null) {
            throw ApiException.conflict("This passkey is already registered")
        }

        val saved = passkeyRepository.save(
            PasskeyCredential(
                userId = userId,
                credentialId = credentialId,
                attestedData = Base64.getEncoder()
                    .encodeToString(attestedCredentialDataConverter.convert(attestedCredentialData)),
                signCount = registrationData.attestationObject?.authenticatorData?.signCount ?: 0,
                label = label?.takeIf { it.isNotBlank() }?.take(100) ?: "Passkey",
                transports = (response["transports"] as? List<*>)?.joinToString(",")?.take(255),
            )
        )
        return PasskeyDto(saved.id!!, saved.label, saved.createdAt, saved.lastUsedAt)
    }

    // ------------------------------------------------------------- authentication

    @Transactional
    fun authenticationOptions(): Map<String, Any?> = mapOf(
        "challenge" to newChallenge(null, WebAuthnPurpose.AUTHENTICATION),
        "rpId" to props.rpId,
        "timeout" to 120_000,
        "userVerification" to "preferred",
        "allowCredentials" to emptyList<Any>(),
    )

    @Transactional
    fun finishAuthentication(credential: Map<String, Any?>, request: HttpServletRequest?): AuthResponse {
        val response = credential.nested("response") ?: throw ApiException.badRequest("Malformed passkey response")
        val clientDataJSON = decode(response["clientDataJSON"])
        val authenticatorData = decode(response["authenticatorData"])
        val signature = decode(response["signature"])
        val userHandle = (response["userHandle"] as? String)?.takeIf { it.isNotBlank() }?.let { b64urlDecoder.decode(it) }
        val rawId = (credential["rawId"] as? String) ?: (credential["id"] as? String)
        ?: throw ApiException.badRequest("Malformed passkey response")

        val stored = consumeChallenge(clientDataJSON, WebAuthnPurpose.AUTHENTICATION)
        val record = passkeyRepository.findByCredentialId(normalizeB64(rawId))
            ?: throw ApiException.unauthorized("Unknown passkey")
        val user = userRepository.findById(record.userId)
            .orElseThrow { ApiException.unauthorized("Unknown passkey") }
        if (!user.enabled || user.deletedAt != null) throw ApiException.forbidden("This account has been disabled")

        val attestedCredentialData = attestedCredentialDataConverter.convert(
            Base64.getDecoder().decode(record.attestedData)
        )
        val authenticator = AuthenticatorImpl(attestedCredentialData, NoneAttestationStatement(), record.signCount)
        val serverProperty = ServerProperty(origins(), props.rpId, DefaultChallenge(b64urlDecoder.decode(stored.challenge)), null)

        val authenticationData = try {
            webAuthnManager.verify(
                AuthenticationRequest(
                    b64urlDecoder.decode(normalizeB64(rawId)),
                    userHandle,
                    authenticatorData,
                    clientDataJSON,
                    null,
                    signature,
                ),
                AuthenticationParameters(
                    serverProperty,
                    authenticator,
                    listOf(b64urlDecoder.decode(normalizeB64(rawId))),
                    false,
                    true,
                ),
            )
        } catch (ex: Exception) {
            log.warn("Passkey authentication failed: {}", ex.message)
            throw ApiException.unauthorized("Passkey could not be verified")
        }

        record.signCount = authenticationData.authenticatorData?.signCount ?: record.signCount
        record.lastUsedAt = Instant.now()
        passkeyRepository.save(record)
        return authService.issueTokens(user, request)
    }

    // ------------------------------------------------------------- management

    fun list(userId: UUID): List<PasskeyDto> = passkeyRepository.findAllByUserId(userId)
        .map { PasskeyDto(it.id!!, it.label, it.createdAt, it.lastUsedAt) }

    @Transactional
    fun delete(userId: UUID, passkeyId: UUID) {
        val record = passkeyRepository.findById(passkeyId).orElseThrow { ApiException.notFound("Passkey") }
        if (record.userId != userId) throw ApiException.forbidden()
        passkeyRepository.delete(record)
    }

    // ------------------------------------------------------------- helpers

    private fun newChallenge(userId: UUID?, purpose: WebAuthnPurpose): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val challenge = b64url.encodeToString(bytes)
        challengeRepository.save(
            WebAuthnChallenge(
                challenge = challenge,
                userId = userId,
                purpose = purpose,
                expiresAt = Instant.now().plusSeconds(300),
            )
        )
        return challenge
    }

    private fun consumeChallenge(clientDataJSON: ByteArray, purpose: WebAuthnPurpose): WebAuthnChallenge {
        val clientData = objectMapper.readTree(String(clientDataJSON, StandardCharsets.UTF_8))
        val challenge = clientData.path("challenge").asText(null)
            ?: throw ApiException.badRequest("Missing challenge")
        val stored = challengeRepository.findByChallenge(normalizeB64(challenge))
            ?: throw ApiException.badRequest("Unknown or already used challenge")
        challengeRepository.delete(stored)
        if (stored.purpose != purpose) throw ApiException.badRequest("Challenge purpose mismatch")
        if (stored.expiresAt.isBefore(Instant.now())) throw ApiException.badRequest("Challenge expired")
        return stored
    }

    private fun origins(): Set<Origin> = props.origins.map { Origin(it) }.toSet()

    private fun decode(value: Any?): ByteArray {
        val text = value as? String ?: throw ApiException.badRequest("Malformed passkey response")
        return b64urlDecoder.decode(normalizeB64(text))
    }

    private fun normalizeB64(value: String) = value.replace('+', '-').replace('/', '_').replace("=", "")

    private fun uuidToBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(16)
        .putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)
        .array()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nested(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    @Scheduled(fixedDelay = 600_000)
    @Transactional
    fun purgeExpiredChallenges() = challengeRepository.deleteExpired(Instant.now())
}
