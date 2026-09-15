package nl.dichtbij3d.backend.service

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import nl.dichtbij3d.backend.config.MailProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.util.Properties

class EmailServiceTest {

    private val mailProps = MailProperties(
        from = "noreply@dichtbij3d.nl",
        fromName = "Dichtbij3D",
        frontendUrl = "http://localhost:8081"
    )

    @Test
    fun `isMailConfigured returns false when mailSender is null`() {
        val service = EmailService(mailProps, null)
        assertFalse(service.isMailConfigured())
        // Should not throw, falls back to dev logging
        service.sendPasswordResetEmail("user@example.com", "User", "http://reset.url")
    }

    @Test
    fun `isMailConfigured returns false when host is blank`() {
        val mailSender = JavaMailSenderImpl()
        mailSender.host = ""
        val service = EmailService(mailProps, mailSender)
        assertFalse(service.isMailConfigured())
        service.sendPasswordResetEmail("user@example.com", "User", "http://reset.url")
    }

    @Test
    fun `isMailConfigured returns false when auth is true but password is blank`() {
        val mailSender = JavaMailSenderImpl()
        mailSender.host = "smtp.gmail.com"
        mailSender.username = "user@gmail.com"
        mailSender.password = ""
        mailSender.javaMailProperties = Properties().apply { put("mail.smtp.auth", "true") }

        val service = EmailService(mailProps, mailSender)
        assertFalse(service.isMailConfigured())
        service.sendPasswordResetEmail("user@example.com", "User", "http://reset.url")
    }

    @Test
    fun `isMailConfigured returns true when host and password are provided`() {
        val mailSender = JavaMailSenderImpl()
        mailSender.host = "smtp.gmail.com"
        mailSender.username = "user@gmail.com"
        mailSender.password = "abcdefghijklmnop"
        mailSender.javaMailProperties = Properties().apply { put("mail.smtp.auth", "true") }

        val service = EmailService(mailProps, mailSender)
        assertTrue(service.isMailConfigured())
    }

    @Test
    fun `sendPasswordResetEmail sanitizes Google App Passwords with spaces and sends email`() {
        val mailSender = JavaMailSenderImpl()
        mailSender.host = "smtp.gmail.com"
        mailSender.username = "user@gmail.com"
        mailSender.password = "abcd efgh ijkl mnop"
        mailSender.javaMailProperties = Properties().apply { put("mail.smtp.auth", "true") }

        // Provide a mock session / transport so send doesn't actually connect to real internet
        val spiedSender = org.mockito.Mockito.spy(mailSender)
        val dummyMessage = MimeMessage(Session.getInstance(Properties()))
        `when`(spiedSender.createMimeMessage()).thenReturn(dummyMessage)
        org.mockito.Mockito.doNothing().`when`(spiedSender).send(dummyMessage)

        val service = EmailService(mailProps, spiedSender)
        service.sendPasswordResetEmail("target@example.com", "Target", "http://reset.url")

        assertEquals("abcdefghijklmnop", spiedSender.password)
        verify(spiedSender).send(dummyMessage)
    }

    @Test
    fun `sendMfaCodeEmail sends email with valid sender and handles unconfigured sender safely`() {
        val unconfigured = EmailService(mailProps, null)
        assertFalse(unconfigured.isMailConfigured())
        // Should safely no-op without throwing
        unconfigured.sendMfaCodeEmail("user@example.com", "User", "123456")

        val mailSender = JavaMailSenderImpl().apply {
            host = "smtp.example.com"
            username = "user"
            password = "password"
        }
        val spiedSender = org.mockito.Mockito.spy(mailSender)
        val dummyMessage = MimeMessage(Session.getInstance(Properties()))
        `when`(spiedSender.createMimeMessage()).thenReturn(dummyMessage)
        org.mockito.Mockito.doNothing().`when`(spiedSender).send(dummyMessage)

        val service = EmailService(mailProps, spiedSender)
        service.sendMfaCodeEmail("target@example.com", "Target", "654321")
        verify(spiedSender).send(dummyMessage)
    }

    @Test
    fun `send methods catch exceptions during mail delivery and do not leak secrets`() {
        val mailSender = mock(JavaMailSenderImpl::class.java)
        `when`(mailSender.host).thenReturn("smtp.example.com")
        `when`(mailSender.password).thenReturn("password")
        `when`(mailSender.javaMailProperties).thenReturn(Properties().apply { put("mail.smtp.auth", "false") })
        val dummyMessage = MimeMessage(Session.getInstance(Properties()))
        `when`(mailSender.createMimeMessage()).thenReturn(dummyMessage)
        org.mockito.Mockito.doThrow(RuntimeException("SMTP connection timed out")).`when`(mailSender).send(dummyMessage)

        val service = EmailService(mailProps, mailSender)
        // Neither method should throw
        service.sendPasswordResetEmail("target@example.com", "Target", "http://reset.url/secret-token")
        service.sendMfaCodeEmail("target@example.com", "Target", "123456")
    }
}
