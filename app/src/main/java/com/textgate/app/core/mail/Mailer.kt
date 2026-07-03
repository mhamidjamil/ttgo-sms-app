package com.textgate.app.core.mail

import com.textgate.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Minimal SMTP mailer mirroring the baileys-service pattern: same env/config
 * names (SMTP_HOST/PORT/SECURE/USER/PASS/FROM_EMAIL/SENDER_NAME), fromEmail →
 * user fallback, and a graceful "not configured" failure instead of a crash
 * when the SMTP keys are absent from local.properties.
 *
 * SECURITY: the SMTP credentials are baked into the APK via BuildConfig. Use a
 * dedicated low-privilege mail account for this app.
 */
class Mailer {

    private val isConfigured: Boolean
        get() = BuildConfig.SMTP_HOST.isNotBlank() && BuildConfig.SMTP_USER.isNotBlank()

    suspend fun send(to: String, subject: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(isConfigured) { "Email is not configured in this build" }

                val props = Properties().apply {
                    put("mail.smtp.host", BuildConfig.SMTP_HOST)
                    put("mail.smtp.port", BuildConfig.SMTP_PORT.toString())
                    put("mail.smtp.auth", "true")
                    if (BuildConfig.SMTP_SECURE) {
                        put("mail.smtp.ssl.enable", "true")
                    } else {
                        put("mail.smtp.starttls.enable", "true")
                    }
                }
                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(BuildConfig.SMTP_USER, BuildConfig.SMTP_PASS)
                })
                val fromEmail = BuildConfig.SMTP_FROM_EMAIL.ifBlank { BuildConfig.SMTP_USER }
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(fromEmail, BuildConfig.SMTP_SENDER_NAME))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    setSubject(subject)
                    setText(body)
                }
                Transport.send(message)
            }
        }
}
