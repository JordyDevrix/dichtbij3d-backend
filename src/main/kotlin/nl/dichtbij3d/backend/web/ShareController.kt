package nl.dichtbij3d.backend.web

import jakarta.servlet.http.HttpServletRequest
import nl.dichtbij3d.backend.domain.Advert
import nl.dichtbij3d.backend.domain.AdvertStatus
import nl.dichtbij3d.backend.repo.AdvertRepository
import nl.dichtbij3d.backend.service.StorageService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils
import java.util.UUID

@RestController
@RequestMapping("/api/share")
class ShareController(
    private val advertRepository: AdvertRepository,
    private val storage: StorageService,
) {

    @GetMapping("/advert/{id}", produces = [MediaType.TEXT_HTML_VALUE])
    @Transactional(readOnly = true)
    fun shareAdvert(@PathVariable id: UUID, request: HttpServletRequest): ResponseEntity<String> {
        val advert = advertRepository.findById(id).orElse(null)
        val canonicalUrl = toAbsoluteUrl(request, "/advert/$id")

        if (advert == null || advert.deletedAt != null || (advert.hiddenAfterAccept && advert.status != AdvertStatus.OPEN)) {
            val notFoundHtml = renderNotFound(request, canonicalUrl)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFoundHtml)
        }

        val html = renderAdvert(advert, request, canonicalUrl)
        return ResponseEntity.ok()
            .header("Cache-Control", "public, max-age=300")
            .body(html)
    }

    private fun renderAdvert(advert: Advert, request: HttpServletRequest, canonicalUrl: String): String {
        val rawTitle = advert.title.trim()
        val escapedTitle = HtmlUtils.htmlEscape(rawTitle)

        val priceStr = formatPrice(advert)
        val cityStr = advert.city?.takeIf { it.isNotBlank() }
        val prefixParts = listOfNotNull(priceStr, cityStr)
        val prefix = if (prefixParts.isNotEmpty()) prefixParts.joinToString(" · ") + " — " else ""

        val cleanDesc = advert.description.replace(Regex("\\s+"), " ").trim()
        val fullDesc = (prefix + cleanDesc).take(300)
        val escapedDescription = HtmlUtils.htmlEscape(fullDesc)

        val coverKey = advert.images.minByOrNull { it.sortOrder }?.objectKey
            ?: advert.model?.thumbnailKey

        val relativeImageUrl = coverKey?.let { storage.publicUrl(it) ?: "/api/files/$it" } ?: "/og-image.png"
        val imageUrl = toAbsoluteUrl(request, relativeImageUrl)

        val imageMimeType = when (coverKey?.substringAfterLast('.', "")?.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

        val escapedCanonical = HtmlUtils.htmlEscape(canonicalUrl)
        val jsCanonical = canonicalUrl.replace("'", "\\'")

        return """
            <!DOCTYPE html>
            <html lang="nl">
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>$escapedTitle | Dichtbij3D</title>
                <meta name="description" content="$escapedDescription" />
                <meta name="theme-color" content="#FF6A00" />
                <link rel="canonical" href="$escapedCanonical" />

                <!-- Open Graph / Facebook / WhatsApp / Discord -->
                <meta property="og:site_name" content="Dichtbij3D" />
                <meta property="og:type" content="article" />
                <meta property="og:url" content="$escapedCanonical" />
                <meta property="og:title" content="$escapedTitle" />
                <meta property="og:description" content="$escapedDescription" />
                <meta property="og:image" content="$imageUrl" />
                <meta property="og:image:secure_url" content="$imageUrl" />
                <meta property="og:image:type" content="$imageMimeType" />
                <meta property="og:image:alt" content="$escapedTitle" />

                <!-- Twitter / X -->
                <meta name="twitter:card" content="summary_large_image" />
                <meta name="twitter:site" content="@Dichtbij3D" />
                <meta name="twitter:url" content="$escapedCanonical" />
                <meta name="twitter:title" content="$escapedTitle" />
                <meta name="twitter:description" content="$escapedDescription" />
                <meta name="twitter:image" content="$imageUrl" />
                <meta name="twitter:image:alt" content="$escapedTitle" />

                <!-- Client-side fallback redirect for human visitors -->
                <meta http-equiv="refresh" content="0;url=$escapedCanonical" />
                <script>window.location.replace('$jsCanonical');</script>
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 2rem; text-align: center; background: #FFF8F2; color: #1E293B;">
                <p>Doorsturen naar <a href="$escapedCanonical" style="color: #FF6A00; font-weight: bold; text-decoration: none;">$escapedTitle</a>...</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderNotFound(request: HttpServletRequest, canonicalUrl: String): String {
        val defaultImage = toAbsoluteUrl(request, "/og-image.png")
        val escapedCanonical = HtmlUtils.htmlEscape(canonicalUrl)

        return """
            <!DOCTYPE html>
            <html lang="nl">
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Advertentie niet gevonden | Dichtbij3D</title>
                <meta name="description" content="Deze advertentie is verwijderd of niet meer beschikbaar op Dichtbij3D." />
                <meta name="theme-color" content="#FF6A00" />

                <meta property="og:site_name" content="Dichtbij3D" />
                <meta property="og:title" content="Advertentie niet gevonden | Dichtbij3D" />
                <meta property="og:description" content="Deze advertentie is verwijderd of niet meer beschikbaar op Dichtbij3D." />
                <meta property="og:image" content="$defaultImage" />
                <meta name="twitter:card" content="summary" />

                <meta http-equiv="refresh" content="0;url=/" />
                <script>window.location.replace('/');</script>
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 2rem; text-align: center; background: #FFF8F2; color: #1E293B;">
                <p>Advertentie niet gevonden. Doorsturen naar <a href="/" style="color: #FF6A00; font-weight: bold; text-decoration: none;">Dichtbij3D</a>...</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun formatPrice(advert: Advert): String? {
        if (advert.allowBidding) return "Bieden"
        if (advert.priceCents != null) {
            val euros = advert.priceCents!! / 100
            val cents = advert.priceCents!! % 100
            return "€ %d,%02d".format(euros, cents)
        }
        if (advert.budgetMinCents != null || advert.budgetMaxCents != null) {
            val min = advert.budgetMinCents?.let { "€ %d,%02d".format(it / 100, it % 100) }
            val max = advert.budgetMaxCents?.let { "€ %d,%02d".format(it / 100, it % 100) }
            return when {
                min != null && max != null -> "$min – $max"
                min != null -> "Vanaf $min"
                max != null -> "Tot $max"
                else -> null
            }
        }
        return null
    }

    private fun toAbsoluteUrl(request: HttpServletRequest, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val cleanPath = "/" + path.trimStart('/')

        val forwardedProto = request.getHeader("X-Forwarded-Proto")
        val forwardedHost = request.getHeader("X-Forwarded-Host") ?: request.getHeader("Host")

        val host = (forwardedHost ?: request.serverName.let {
            if (request.serverPort != 80 && request.serverPort != 443) "$it:${request.serverPort}" else it
        }).trim()

        val proto = if (host.startsWith("localhost") || host.startsWith("127.0.0.1")) {
            forwardedProto?.takeIf { it.isNotBlank() } ?: request.scheme ?: "http"
        } else {
            // Any non-localhost host in production must use HTTPS for social scrapers
            "https"
        }

        return "$proto://$host$cleanPath"
    }
}
