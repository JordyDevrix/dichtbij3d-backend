package nl.dichtbij3d.backend.service

import jakarta.mail.internet.InternetAddress
import nl.dichtbij3d.backend.config.MailProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class EmailService(
    private val props: MailProperties,
    @Autowired(required = false) private val mailSender: JavaMailSender? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Dispatches a password reset email to the user.
     * If [JavaMailSender] is not configured (e.g. in local development without SMTP),
     * the reset link is printed to the server logs.
     */
    fun sendPasswordResetEmail(toEmail: String, displayName: String, resetUrl: String, locale: String = "nl") {
        if (mailSender == null) {
            log.warn(
                "[DEV MODE - SMTP not configured] Password reset requested for '{}' ({}). Reset URL:\n{}",
                displayName,
                toEmail,
                resetUrl,
            )
            return
        }

        try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")

            helper.setFrom(InternetAddress(props.from, props.fromName, "UTF-8"))
            helper.setTo(toEmail)
            helper.setSubject(resolveSubject(locale))

            val htmlBody = buildHtmlTemplate(displayName, resetUrl, locale)
            val textBody = buildTextTemplate(displayName, resetUrl, locale)

            helper.setText(textBody, htmlBody)

            mailSender.send(message)
            log.info("Password reset email successfully sent from {} to {}", props.from, toEmail)
        } catch (ex: Exception) {
            log.error("Failed to send password reset email to {}: {}", toEmail, ex.message, ex)
        }
    }

    private fun resolveSubject(locale: String): String = when (locale.lowercase().take(2)) {
        "en" -> "Reset your Dichtbij3D password"
        "de" -> "Passwort für Dichtbij3D zurücksetzen"
        "fr" -> "Réinitialisation de votre mot de passe Dichtbij3D"
        else -> "Wachtwoord opnieuw instellen - Dichtbij3D"
    }

    private fun buildHtmlTemplate(name: String, resetUrl: String, locale: String): String {
        val lang = locale.lowercase().take(2)
        val title = when (lang) {
            "en" -> "Reset your password"
            "de" -> "Passwort zurücksetzen"
            "fr" -> "Réinitialisez votre mot de passe"
            else -> "Wachtwoord opnieuw instellen"
        }
        val greeting = when (lang) {
            "en" -> "Hello $name,"
            "de" -> "Hallo $name,"
            "fr" -> "Bonjour $name,"
            else -> "Hallo $name,"
        }
        val intro = when (lang) {
            "en" -> "We received a request to reset the password for your Dichtbij3D account. Click the button below to choose a new password:"
            "de" -> "Wir haben eine Anfrage zum Zurücksetzen Ihres Passworts für Dichtbij3D erhalten. Klicken Sie auf die Schaltfläche unten:"
            "fr" -> "Nous avons reçu une demande de réinitialisation du mot de passe de votre compte Dichtbij3D. Cliquez sur le bouton ci-dessous :"
            else -> "We hebben een verzoek ontvangen om het wachtwoord voor je Dichtbij3D-account opnieuw in te stellen. Klik op de knop hieronder om een nieuw wachtwoord te kiezen:"
        }
        val buttonText = when (lang) {
            "en" -> "Reset password"
            "de" -> "Neues Passwort festlegen"
            "fr" -> "Réinitialiser le mot de passe"
            else -> "Wachtwoord instellen"
        }
        val expiryNotice = when (lang) {
            "en" -> "This link is valid for 30 minutes."
            "de" -> "Dieser Link ist 30 Minuten lang gültig."
            "fr" -> "Ce lien est valable pendant 30 minutes."
            else -> "Deze link is 30 minuten geldig."
        }
        val ignoreNotice = when (lang) {
            "en" -> "If you did not request a password reset, you can safely ignore this email. Your password will not change."
            "de" -> "Wenn Sie dies nicht angefordert haben, können Sie diese E-Mail ignorieren. Ihr Passwort bleibt unverändert."
            "fr" -> "Si vous n'êtes pas à l'origine de cette demande, vous pouvez ignorer cet e-mail en toute sécurité."
            else -> "Heb je dit niet aangevraagd? Dan kun je deze e-mail gerust negeren. Je huidige wachtwoord blijft gewoon werken."
        }
        val fallbackHint = when (lang) {
            "en" -> "If the button above does not work, copy and paste this link into your browser:"
            "de" -> "Falls die Schaltfläche nicht funktioniert, kopieren Sie diesen Link in Ihren Browser:"
            "fr" -> "Si le bouton ne fonctionne pas, copiez et collez ce lien dans votre navigateur :"
            else -> "Werkt de knop niet? Kopieer en plak dan de onderstaande link in je browser:"
        }

        return """
<!DOCTYPE html>
<html lang="$lang">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>$title</title>
</head>
<body style="margin: 0; padding: 24px 12px; background-color: #F8FAFC; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1E293B;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
    <tr>
      <td align="center">
        <table role="presentation" style="max-width: 520px; width: 100%; background-color: #FFFFFF; border-radius: 12px; border: 1px solid #E2E8F0; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.03);" cellpadding="0" cellspacing="0" border="0">
          <tr>
            <td style="padding: 32px 32px 20px 32px; border-bottom: 2px solid #FFF0E6;">
              <span style="color: #F26514; font-size: 22px; font-weight: 800; letter-spacing: -0.5px;">Dichtbij3D</span>
            </td>
          </tr>
          <tr>
            <td style="padding: 28px 32px;">
              <h1 style="margin: 0 0 16px 0; font-size: 20px; font-weight: 700; color: #0F172A;">$title</h1>
              <p style="margin: 0 0 16px 0; font-size: 15px; line-height: 1.5; color: #334155;">$greeting</p>
              <p style="margin: 0 0 24px 0; font-size: 15px; line-height: 1.5; color: #334155;">$intro</p>

              <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin: 0 0 24px 0;">
                <tr>
                  <td align="center" style="border-radius: 8px; background-color: #F26514;">
                    <a href="$resetUrl" target="_blank" style="display: inline-block; padding: 12px 24px; font-size: 15px; font-weight: 600; color: #FFFFFF; text-decoration: none; border-radius: 8px;">$buttonText &rarr;</a>
                  </td>
                </tr>
              </table>

              <p style="margin: 0 0 8px 0; font-size: 13px; color: #64748B;">$expiryNotice</p>
              <p style="margin: 0 0 24px 0; font-size: 13px; color: #64748B;">$ignoreNotice</p>

              <hr style="border: none; border-top: 1px solid #E2E8F0; margin: 24px 0 16px 0;" />

              <p style="margin: 0 0 8px 0; font-size: 12px; color: #94A3B8;">$fallbackHint</p>
              <p style="margin: 0; font-size: 12px; line-height: 1.4; word-break: break-all;">
                <a href="$resetUrl" target="_blank" style="color: #F26514; text-decoration: underline;">$resetUrl</a>
              </p>
            </td>
          </tr>
          <tr>
            <td style="padding: 20px 32px; background-color: #F8FAFC; border-top: 1px solid #E2E8F0; font-size: 12px; color: #94A3B8; text-align: center;">
              &copy; ${java.time.Year.now().value} Dichtbij3D &middot; 3D-printen dichtbij huis
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
""".trimIndent()
    }

    private fun buildTextTemplate(name: String, resetUrl: String, locale: String): String {
        val lang = locale.lowercase().take(2)
        return when (lang) {
            "en" -> """
                Hello $name,

                We received a request to reset the password for your Dichtbij3D account.
                Visit the link below to set a new password:
                $resetUrl

                This link is valid for 30 minutes.
                If you did not request this, you can ignore this email.

                Dichtbij3D
            """.trimIndent()
            else -> """
                Hallo $name,

                We hebben een verzoek ontvangen om het wachtwoord van je Dichtbij3D-account opnieuw in te stellen.
                Ga naar de onderstaande link om een nieuw wachtwoord te kiezen:
                $resetUrl

                Deze link is 30 minuten geldig.
                Heb je dit niet aangevraagd? Dan kun je deze e-mail gerust negeren.

                Dichtbij3D
            """.trimIndent()
        }
    }
}
