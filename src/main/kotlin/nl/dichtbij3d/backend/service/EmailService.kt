package nl.dichtbij3d.backend.service

import jakarta.mail.internet.InternetAddress
import nl.dichtbij3d.backend.config.MailProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
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
     * Checks whether a functional mail sender is configured.
     * In development or when SMTP is not configured (or host/password is missing when required),
     * this returns false.
     */
    fun isMailConfigured(): Boolean {
        if (mailSender == null) return false
        if (mailSender is JavaMailSenderImpl) {
            val host = mailSender.host
            if (host.isNullOrBlank()) return false

            val auth = mailSender.javaMailProperties.getProperty("mail.smtp.auth")?.toBoolean() ?: true
            if (auth && mailSender.password.isNullOrBlank()) {
                log.warn(
                    "[SMTP not fully configured] SMTP auth is enabled for host '{}' but no password was specified. " +
                        "Please configure SPRING_MAIL_PASSWORD (or MAIL_PASSWORD / SMTP_PASSWORD) in your environment.",
                    host,
                )
                return false
            }
        }
        return true
    }

    /**
     * Dispatches a password reset email to the user.
     * If [JavaMailSender] is not configured (e.g. in local development without SMTP,
     * or when credentials are missing), the reset link is printed to the server logs.
     */
    fun sendPasswordResetEmail(toEmail: String, displayName: String, resetUrl: String, locale: String = "nl") {
        if (!isMailConfigured()) {
            log.warn(
                "[DEV MODE / SMTP not configured] Password reset requested for '{}' ({}). Reset URL:\n{}",
                displayName,
                toEmail,
                resetUrl,
            )
            return
        }

        val sender = mailSender!!
        sanitizeSender(sender)

        try {
            val message = sender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")

            helper.setFrom(InternetAddress(props.from, props.fromName, "UTF-8"))
            helper.setTo(toEmail)
            helper.setSubject(resolveSubject(locale))

            val htmlBody = buildHtmlTemplate(displayName, resetUrl, locale)
            val textBody = buildTextTemplate(displayName, resetUrl, locale)

            helper.setText(textBody, htmlBody)

            sender.send(message)
            log.info("Password reset email successfully sent from {} to {}", props.from, toEmail)
        } catch (ex: Exception) {
            log.error(
                "Failed to send password reset email to {}: {}. Reset URL for manual use:\n{}",
                toEmail,
                ex.message,
                resetUrl,
                ex,
            )
        }
    }

    /**
     * Dispatches a two-factor authentication (MFA) verification code to the user's email.
     * If SMTP is not configured, the code is printed to the server logs.
     */
    fun sendMfaCodeEmail(toEmail: String, displayName: String, code: String, locale: String = "nl") {
        if (!isMailConfigured()) {
            log.warn(
                "[DEV MODE / SMTP not configured] 2FA/MFA verification code for '{}' ({}): {}",
                displayName,
                toEmail,
                code,
            )
            return
        }

        val sender = mailSender!!
        sanitizeSender(sender)

        try {
            val message = sender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")

            helper.setFrom(InternetAddress(props.from, props.fromName, "UTF-8"))
            helper.setTo(toEmail)
            helper.setSubject(resolveMfaSubject(locale, code))

            val htmlBody = buildMfaHtmlTemplate(displayName, code, locale)
            val textBody = buildMfaTextTemplate(displayName, code, locale)

            helper.setText(textBody, htmlBody)

            sender.send(message)
            log.info("MFA verification code email successfully sent from {} to {}", props.from, toEmail)
        } catch (ex: Exception) {
            log.error(
                "Failed to send MFA email to {}: {}. Verification code for manual use: {}",
                toEmail,
                ex.message,
                code,
                ex,
            )
        }
    }

    private fun sanitizeSender(sender: JavaMailSender) {
        if (sender is JavaMailSenderImpl) {
            val currentPass = sender.password
            if (!currentPass.isNullOrBlank()) {
                val trimmed = currentPass.trim()
                if (sender.host?.contains("gmail", ignoreCase = true) == true &&
                    trimmed.length == 19 && trimmed.count { it == ' ' } == 3
                ) {
                    sender.password = trimmed.replace(" ", "")
                } else if (trimmed != currentPass) {
                    sender.password = trimmed
                }
            }
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

    private fun resolveMfaSubject(locale: String, code: String): String = when (locale.lowercase().take(2)) {
        "en" -> "Your Dichtbij3D verification code: $code"
        "de" -> "Ihr Dichtbij3D Bestätigungscode: $code"
        "fr" -> "Votre code de vérification Dichtbij3D : $code"
        else -> "Je Dichtbij3D verificatiecode: $code"
    }

    private fun buildMfaHtmlTemplate(name: String, code: String, locale: String): String {
        val lang = locale.lowercase().take(2)
        val title = when (lang) {
            "en" -> "Two-factor authentication"
            "de" -> "Zwei-Faktor-Authentifizierung"
            "fr" -> "Authentification à deux facteurs"
            else -> "Tweestapsverificatie"
        }
        val greeting = when (lang) {
            "en" -> "Hello $name,"
            "de" -> "Hallo $name,"
            "fr" -> "Bonjour $name,"
            else -> "Hallo $name,"
        }
        val intro = when (lang) {
            "en" -> "Use the verification code below to sign in to your Dichtbij3D account:"
            "de" -> "Verwenden Sie den folgenden Bestätigungscode, um sich bei Ihrem Dichtbij3D-Konto anzumelden:"
            "fr" -> "Utilisez le code de vérification ci-dessous pour vous connecter à votre compte Dichtbij3D :"
            else -> "Gebruik de onderstaande verificatiecode om in te loggen bij je Dichtbij3D-account:"
        }
        val expiryNotice = when (lang) {
            "en" -> "This code is valid for 10 minutes."
            "de" -> "Dieser Code ist 10 Minuten lang gültig."
            "fr" -> "Ce code est valable pendant 10 minutes."
            else -> "Deze code is 10 minuten geldig."
        }
        val warningNotice = when (lang) {
            "en" -> "Never share this code with anyone. Dichtbij3D employees will never ask for your code."
            "de" -> "Teilen Sie diesen Code niemals mit anderen. Dichtbij3D-Mitarbeiter werden Sie niemals nach diesem Code fragen."
            "fr" -> "Ne partagez jamais ce code. Les employés de Dichtbij3D ne vous demanderont jamais ce code."
            else -> "Deel deze code nooit met anderen. Medewerkers van Dichtbij3D zullen hier nooit naar vragen."
        }
        val suspiciousNotice = when (lang) {
            "en" -> "If you did not attempt to sign in, someone may know your password. We recommend changing your password immediately."
            "de" -> "Wenn Sie sich nicht angemeldet haben, kennt möglicherweise jemand Ihr Passwort. Bitte ändern Sie Ihr Passwort umgehend."
            "fr" -> "Si vous n'avez pas tenté de vous connecter, quelqu'un connaît peut-être votre mot de passe. Veuillez le changer immédiatement."
            else -> "Heb je niet geprobeerd in te loggen? Dan kent iemand mogelijk je wachtwoord. Wij raden je aan je wachtwoord direct te wijzigen."
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
              <p style="margin: 0 0 20px 0; font-size: 15px; line-height: 1.5; color: #334155;">$intro</p>

              <div style="margin: 24px 0; padding: 18px 24px; background-color: #FFF7ED; border: 2px dashed #F26514; border-radius: 10px; text-align: center;">
                <span style="font-size: 32px; font-weight: 800; letter-spacing: 8px; color: #EA580C; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;">$code</span>
              </div>

              <p style="margin: 0 0 8px 0; font-size: 13px; font-weight: 600; color: #334155;">$expiryNotice</p>
              <p style="margin: 0 0 16px 0; font-size: 13px; color: #64748B;">$warningNotice</p>

              <hr style="border: none; border-top: 1px solid #E2E8F0; margin: 24px 0 16px 0;" />

              <p style="margin: 0; font-size: 12px; line-height: 1.4; color: #94A3B8;">$suspiciousNotice</p>
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

    private fun buildMfaTextTemplate(name: String, code: String, locale: String): String {
        val lang = locale.lowercase().take(2)
        return when (lang) {
            "en" -> """
                Hello $name,

                Your Dichtbij3D verification code is:
                $code

                This code is valid for 10 minutes.
                Never share this code with anyone.

                Dichtbij3D
            """.trimIndent()
            else -> """
                Hallo $name,

                Je Dichtbij3D verificatiecode is:
                $code

                Deze code is 10 minuten geldig.
                Deel deze code nooit met anderen.

                Dichtbij3D
            """.trimIndent()
        }
    }
}
