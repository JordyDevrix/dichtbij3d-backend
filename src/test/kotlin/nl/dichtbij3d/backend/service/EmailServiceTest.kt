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
}
