package nl.dichtbij3d.backend.security

import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.pow

/**
 * RFC 6238 TOTP (SHA-1, 6 digits, 30s step) with a +/- 1 step drift window.
 * Implemented in-house so no extra dependency is needed.
 */
@Service
class TotpService {

    private val random = SecureRandom()

    fun generateSecret(): String {
        val bytes = ByteArray(20)
        random.nextBytes(bytes)
        return base32Encode(bytes)
    }

    fun provisioningUri(secret: String, accountName: String, issuer: String = "Dichtbij3D"): String {
        val label = URLEncoder.encode("$issuer:$accountName", StandardCharsets.UTF_8)
        val params = listOf(
            "secret=$secret",
            "issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8),
            "algorithm=SHA1",
            "digits=$DIGITS",
            "period=$PERIOD",
        ).joinToString("&")
        return "otpauth://totp/$label?$params"
    }

    /**
     * Verifies the TOTP code against the secret within a +/- 1 step drift window.
     * To prevent replay attacks (RFC 6238 Section 5.2), [lastUsedStep] can be provided.
     * If valid and not replayed, returns the matched time step (to be stored by caller).
     * Returns null if the code is invalid or has already been used in this or a prior step.
     */
    fun verify(
        secret: String,
        code: String,
        lastUsedStep: Long? = null,
        now: Long = System.currentTimeMillis() / 1000,
    ): Long? {
        val normalized = code.trim().replace(" ", "")
        if (normalized.length != DIGITS || normalized.any { !it.isDigit() }) return null
        val key = base32Decode(secret)
        val counter = now / PERIOD
        for (drift in -1..1) {
            val step = counter + drift
            if (lastUsedStep != null && step <= lastUsedStep) {
                continue
            }
            if (constantTimeEquals(generate(key, step), normalized)) {
                return step
            }
        }
        return null
    }

    /**
     * Generates the code for a specific counter step (useful for testing and internal verification).
     */
    fun generateCode(secret: String, counter: Long): String {
        val key = base32Decode(secret)
        return generate(key, counter)
    }

    private fun generate(key: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val otp = binary % 10.0.pow(DIGITS).toInt()
        return otp.toString().padStart(DIGITS, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        return sb.toString()
    }

    private fun base32Decode(secret: String): ByteArray {
        val clean = secret.uppercase().replace("=", "").replace(" ", "")
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var index = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "Invalid base32 character: $c" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[index++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }
        return out.copyOf(abs(index))
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val DIGITS = 6
        private const val PERIOD = 30L
    }
}
