package nl.dichtbij3d.backend.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TotpServiceTest {

    private lateinit var totpService: TotpService
    private val secret = "JBSWY3DPEHPK3PXP" // Valid Base32 secret

    @BeforeEach
    fun setUp() {
        totpService = TotpService()
    }

    @Test
    fun `generateSecret generates valid base32 string`() {
        val secret = totpService.generateSecret()
        assertNotNull(secret)
        assertTrue(secret.length >= 16)
        assertTrue(secret.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
    }

    @Test
    fun `provisioningUri generates standard otpauth URL`() {
        val uri = totpService.provisioningUri(secret, "user@example.com", "Dichtbij3D")
        assertTrue(uri.startsWith("otpauth://totp/"))
        assertTrue(uri.contains("secret=$secret"))
        assertTrue(uri.contains("issuer=Dichtbij3D"))
        assertTrue(uri.contains("algorithm=SHA1"))
        assertTrue(uri.contains("digits=6"))
        assertTrue(uri.contains("period=30"))
    }

    @Test
    fun `verify accepts valid code at current time step`() {
        val now = 1700000000L
        val counter = now / 30L
        val code = totpService.generateCode(secret, counter)

        val matchedStep = totpService.verify(secret, code, lastUsedStep = null, now = now)
        assertEquals(counter, matchedStep)
    }

    @Test
    fun `verify accepts code within plus minus 1 step drift window`() {
        val now = 1700000000L
        val currentCounter = now / 30L

        // Previous step (drift = -1)
        val prevCode = totpService.generateCode(secret, currentCounter - 1)
        val prevMatched = totpService.verify(secret, prevCode, lastUsedStep = null, now = now)
        assertEquals(currentCounter - 1, prevMatched)

        // Next step (drift = +1)
        val nextCode = totpService.generateCode(secret, currentCounter + 1)
        val nextMatched = totpService.verify(secret, nextCode, lastUsedStep = null, now = now)
        assertEquals(currentCounter + 1, nextMatched)
    }

    @Test
    fun `verify rejects codes outside the drift window`() {
        val now = 1700000000L
        val currentCounter = now / 30L

        val tooOldCode = totpService.generateCode(secret, currentCounter - 2)
        assertNull(totpService.verify(secret, tooOldCode, lastUsedStep = null, now = now))

        val tooFutureCode = totpService.generateCode(secret, currentCounter + 2)
        assertNull(totpService.verify(secret, tooFutureCode, lastUsedStep = null, now = now))
    }

    @Test
    fun `verify rejects replayed code when lastUsedStep matches current step`() {
        val now = 1700000000L
        val counter = now / 30L
        val code = totpService.generateCode(secret, counter)

        // First verification succeeds
        val step1 = totpService.verify(secret, code, lastUsedStep = null, now = now)
        assertEquals(counter, step1)

        // Immediate replay within same validity window is rejected
        val step2 = totpService.verify(secret, code, lastUsedStep = step1, now = now)
        assertNull(step2, "Replay of the same TOTP code must be rejected")
    }

    @Test
    fun `verify rejects replayed drift code from earlier step`() {
        val now = 1700000000L
        val currentCounter = now / 30L

        // Suppose last verified step was currentCounter
        val lastUsed = currentCounter

        // Attacker tries to use the code from drift -1
        val prevCode = totpService.generateCode(secret, currentCounter - 1)
        val result = totpService.verify(secret, prevCode, lastUsedStep = lastUsed, now = now)
        assertNull(result, "Code for a step <= lastUsedStep must be rejected")
    }

    @Test
    fun `verify accepts fresh code in subsequent time step`() {
        val now1 = 1700000000L
        val counter1 = now1 / 30L
        val code1 = totpService.generateCode(secret, counter1)

        val step1 = totpService.verify(secret, code1, lastUsedStep = null, now = now1)
        assertEquals(counter1, step1)

        // Advance time by 30 seconds (next time step)
        val now2 = now1 + 30L
        val counter2 = now2 / 30L
        val code2 = totpService.generateCode(secret, counter2)

        val step2 = totpService.verify(secret, code2, lastUsedStep = step1, now = now2)
        assertEquals(counter2, step2)
        assertTrue(step2!! > step1!!)
    }

    @Test
    fun `verify rejects malformed codes`() {
        val now = 1700000000L
        assertNull(totpService.verify(secret, "", lastUsedStep = null, now = now))
        assertNull(totpService.verify(secret, "12345", lastUsedStep = null, now = now))
        assertNull(totpService.verify(secret, "1234567", lastUsedStep = null, now = now))
        assertNull(totpService.verify(secret, "abcdef", lastUsedStep = null, now = now))
    }
}
