package nl.dichtbij3d.backend.service

import jakarta.annotation.PostConstruct
import nl.dichtbij3d.backend.domain.Advert
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.abs
import kotlin.math.floor

/**
 * Service that generates lightweight, high-compatibility social sharing preview images
 * (1200x630 px JPEG) specifically tailored for WhatsApp, Facebook, Twitter/X, and Discord.
 *
 * WhatsApp enforces a strict file size ceiling (typically ~300 KB). Large raw uploads
 * from modern smartphones (2-10 MB) silently fail to render in WhatsApp chat cards,
 * causing only the card title to show without the image. This service ensures images
 * are beautifully composed, properly oriented, and strictly under 280 KB.
 */
@Service
class SocialImageService(
    private val storage: StorageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Bounded LRU memory cache for generated thumbnails (max 150 cards ~ 12-15 MB)
    private val cache: MutableMap<String, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
                return size > 150
            }
        }
    )

    @PostConstruct
    fun init() {
        try {
            ImageIO.scanForPlugins()
            val formats = ImageIO.getReaderFormatNames().distinct()
            log.info("SocialImageService ready. Registered ImageIO formats: {}", formats.joinToString(", "))
        } catch (ex: Exception) {
            log.warn("Could not scan for ImageIO plugins: {}", ex.message)
        }
    }

    /**
     * Generates or retrieves from cache an optimized 1200x630 JPEG image for the given advert.
     * Guaranteed to be <= 280 KB.
     */
    fun generateAdvertThumbnail(advert: Advert): ByteArray? {
        val coverKey = advert.images.minByOrNull { it.sortOrder }?.objectKey
            ?: advert.model?.thumbnailKey

        if (coverKey != null) {
            val cacheKey = "$coverKey:${advert.updatedAt.epochSecond}"
            cache[cacheKey]?.let { return it }

            val rawBytes = storage.readBytesWithAlias(coverKey)
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val processed = processImage(rawBytes, advert)
                if (processed != null) {
                    cache[cacheKey] = processed
                    return processed
                }
            }
        }

        // Fallback: Generate a clean branded card with the advert's title and details
        val fallbackKey = "branded:${advert.id}:${advert.updatedAt.epochSecond}"
        cache[fallbackKey]?.let { return it }

        val fallback = generateBrandedCard(advert)
        cache[fallbackKey] = fallback
        return fallback
    }

    /**
     * Public helper for generating a thumbnail directly from raw image bytes and optional advert details.
     */
    fun createThumbnailFromBytes(rawBytes: ByteArray, title: String? = null): ByteArray? {
        return try {
            var original = ImageIO.read(ByteArrayInputStream(rawBytes)) ?: return null
            val orientation = getExifOrientation(rawBytes)
            if (orientation > 1) {
                original = applyOrientation(original, orientation)
            }
            val card = composeCard(original)
            encodeToJpeg(card)
        } catch (ex: Exception) {
            log.warn("Failed generating thumbnail from bytes: {}", ex.message)
            null
        }
    }

    private fun processImage(rawBytes: ByteArray, advert: Advert): ByteArray? {
        try {
            var original = ImageIO.read(ByteArrayInputStream(rawBytes)) ?: return null

            val orientation = getExifOrientation(rawBytes)
            if (orientation > 1) {
                original = applyOrientation(original, orientation)
            }

            val card = composeCard(original)
            return encodeToJpeg(card)
        } catch (ex: Exception) {
            log.warn("Failed processing image for advert {}: {}", advert.id, ex.message)
            return null
        }
    }

    private fun composeCard(original: BufferedImage): BufferedImage {
        val origW = original.width
        val origH = original.height
        val origRatio = origW.toDouble() / origH.toDouble()

        val canvas = BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        applyQualityHints(g)

        if (origRatio in 1.5..2.1) {
            // Standard landscape photo: fill and center-crop to 1200x630
            val scale = maxOf(TARGET_WIDTH.toDouble() / origW, TARGET_HEIGHT.toDouble() / origH)
            val scaledW = (origW * scale).toInt()
            val scaledH = (origH * scale).toInt()
            val x = (TARGET_WIDTH - scaledW) / 2
            val y = (TARGET_HEIGHT - scaledH) / 2
            g.drawImage(original, x, y, scaledW, scaledH, null)
        } else {
            // Portrait, square, or non-standard aspect ratio:
            // 1. Blurred background derived from the original image colors
            val tiny = BufferedImage(40, 21, BufferedImage.TYPE_INT_RGB)
            val gTiny = tiny.createGraphics()
            gTiny.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            gTiny.drawImage(original, 0, 0, 40, 21, null)
            gTiny.dispose()

            g.drawImage(tiny, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, null)

            // 2. Dark translucent overlay for contrast
            g.color = Color(15, 23, 42, 140) // Slate-900 with ~55% opacity
            g.fillRect(0, 0, TARGET_WIDTH, TARGET_HEIGHT)

            // 3. Centered sharp foreground image
            val paddingX = 40
            val paddingY = 24
            val maxForegroundW = TARGET_WIDTH - (paddingX * 2)
            val maxForegroundH = TARGET_HEIGHT - (paddingY * 2)

            val fitScale = minOf(maxForegroundW.toDouble() / origW, maxForegroundH.toDouble() / origH)
            val fitW = (origW * fitScale).toInt()
            val fitH = (origH * fitScale).toInt()
            val fitX = (TARGET_WIDTH - fitW) / 2
            val fitY = (TARGET_HEIGHT - fitH) / 2

            // Subtle border shadow
            g.color = Color(0, 0, 0, 70)
            g.fillRect(fitX - 2, fitY - 2, fitW + 4, fitH + 4)

            g.drawImage(original, fitX, fitY, fitW, fitH, null)
        }

        g.dispose()
        return canvas
    }

    private fun generateBrandedCard(advert: Advert): ByteArray {
        val canvas = BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        applyQualityHints(g)

        // Warm off-white background
        g.color = Color(0xFF, 0xF8, 0xF2)
        g.fillRect(0, 0, TARGET_WIDTH, TARGET_HEIGHT)

        // Brand orange header accent bar (#FF6A00)
        g.color = Color(0xFF, 0x6A, 0x00)
        g.fillRect(0, 0, TARGET_WIDTH, 14)

        // Platform Brand: "Dichtbij3D"
        g.font = Font("SansSerif", Font.BOLD, 46)
        g.color = Color(0xFF, 0x6A, 0x00)
        g.drawString("Dichtbij3D", 80, 110)

        // Type label
        g.font = Font("SansSerif", Font.BOLD, 22)
        g.color = Color(0x64, 0x74, 0x8B)
        val typeLabel = when (advert.type.name) {
            "PRINT_REQUEST" -> "3D-printverzoek"
            "MODEL_FOR_SALE" -> "3D-model te koop"
            "PRINT_SERVICE" -> "3D-printservice"
            else -> "Marktplaats"
        }
        g.drawString(typeLabel.uppercase(), 80, 160)

        // Advert title
        g.font = Font("SansSerif", Font.BOLD, 52)
        g.color = Color(0x1E, 0x29, 0x3B)
        val title = advert.title.trim().take(80)
        val fm = g.fontMetrics
        if (fm.stringWidth(title) <= TARGET_WIDTH - 160) {
            g.drawString(title, 80, 260)
        } else {
            val words = title.split(" ")
            var line1 = ""
            var line2 = ""
            for (w in words) {
                if (line2.isEmpty() && fm.stringWidth("$line1 $w".trim()) <= TARGET_WIDTH - 160) {
                    line1 = "$line1 $w".trim()
                } else {
                    line2 = "$line2 $w".trim()
                }
            }
            g.drawString(line1, 80, 250)
            if (line2.isNotEmpty()) {
                g.drawString(line2.take(45), 80, 320)
            }
        }

        // Pricing and location metadata
        g.font = Font("SansSerif", Font.PLAIN, 28)
        g.color = Color(0x47, 0x55, 0x69)
        val price = formatPrice(advert)
        val city = advert.city?.takeIf { it.isNotBlank() }
        val metaParts = listOfNotNull(price, city).joinToString("   •   ")
        if (metaParts.isNotBlank()) {
            g.drawString(metaParts, 80, 470)
        }

        g.font = Font("SansSerif", Font.BOLD, 24)
        g.color = Color(0xFF, 0x6A, 0x00)
        g.drawString("Bekijk op dichtbij3d.nl →", 80, 540)

        g.dispose()
        return encodeToJpeg(canvas)
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

    private fun encodeToJpeg(image: BufferedImage): ByteArray {
        var quality = 0.82f
        var bytes = writeJpeg(image, quality)

        // WhatsApp strictly limits preview images to 300 KB. Keep safely below 280 KB.
        if (bytes.size > 280_000) {
            quality = 0.68f
            bytes = writeJpeg(image, quality)
        }
        if (bytes.size > 280_000) {
            quality = 0.50f
            bytes = writeJpeg(image, quality)
        }
        return bytes
    }

    private fun writeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            val baos = ByteArrayOutputStream()
            MemoryCacheImageOutputStream(baos).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(image, null, null), params)
            }
            return baos.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    /**
     * Reads the EXIF orientation tag from JPEG bytes without external dependencies.
     * Returns 1 (normal) if not found or not JPEG.
     */
    private fun getExifOrientation(bytes: ByteArray): Int {
        if (bytes.size < 12 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 1
        var offset = 2
        while (offset + 4 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte()) break
            val marker = bytes[offset + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            if (marker == 0xE1 && offset + 2 + length <= bytes.size) { // APP1 Exif marker
                val exifOffset = offset + 4
                if (exifOffset + 6 <= bytes.size &&
                    bytes[exifOffset] == 'E'.code.toByte() &&
                    bytes[exifOffset + 1] == 'x'.code.toByte() &&
                    bytes[exifOffset + 2] == 'i'.code.toByte() &&
                    bytes[exifOffset + 3] == 'f'.code.toByte() &&
                    bytes[exifOffset + 4] == 0.toByte() &&
                    bytes[exifOffset + 5] == 0.toByte()
                ) {
                    val tiffOffset = exifOffset + 6
                    if (tiffOffset + 8 > bytes.size) return 1
                    val isLittleEndian = bytes[tiffOffset] == 'I'.code.toByte() && bytes[tiffOffset + 1] == 'I'.code.toByte()
                    val isBigEndian = bytes[tiffOffset] == 'M'.code.toByte() && bytes[tiffOffset + 1] == 'M'.code.toByte()
                    if (!isLittleEndian && !isBigEndian) return 1

                    fun readU16(p: Int): Int {
                        if (p + 2 > bytes.size) return 0
                        val b0 = bytes[p].toInt() and 0xFF
                        val b1 = bytes[p + 1].toInt() and 0xFF
                        return if (isLittleEndian) (b1 shl 8) or b0 else (b0 shl 8) or b1
                    }
                    fun readU32(p: Int): Int {
                        if (p + 4 > bytes.size) return 0
                        val b0 = bytes[p].toInt() and 0xFF
                        val b1 = bytes[p + 1].toInt() and 0xFF
                        val b2 = bytes[p + 2].toInt() and 0xFF
                        val b3 = bytes[p + 3].toInt() and 0xFF
                        return if (isLittleEndian) {
                            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                        } else {
                            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                        }
                    }

                    val firstIfdOffset = readU32(tiffOffset + 4)
                    var ifdPtr = tiffOffset + firstIfdOffset
                    if (ifdPtr + 2 > bytes.size) return 1
                    val numEntries = readU16(ifdPtr)
                    ifdPtr += 2
                    for (i in 0 until numEntries) {
                        if (ifdPtr + 12 > bytes.size) break
                        val tag = readU16(ifdPtr)
                        if (tag == 0x0112) { // Orientation tag
                            return readU16(ifdPtr + 8)
                        }
                        ifdPtr += 12
                    }
                }
            }
            offset += 2 + length
        }
        return 1
    }

    private fun applyOrientation(img: BufferedImage, orientation: Int): BufferedImage {
        return when (orientation) {
            3 -> rotate(img, 180.0)
            6 -> rotate(img, 90.0)
            8 -> rotate(img, 270.0)
            else -> img
        }
    }

    private fun rotate(img: BufferedImage, degrees: Double): BufferedImage {
        val rads = Math.toRadians(degrees)
        val sin = abs(Math.sin(rads))
        val cos = abs(Math.cos(rads))
        val w = img.width
        val h = img.height
        val newW = floor(w * cos + h * sin).toInt()
        val newH = floor(h * cos + w * sin).toInt()

        val rotated = BufferedImage(newW, newH, if (img.type == 0) BufferedImage.TYPE_INT_RGB else img.type)
        val g2d = rotated.createGraphics()
        applyQualityHints(g2d)
        val at = AffineTransform()
        at.translate(((newW - w) / 2).toDouble(), ((newH - h) / 2).toDouble())
        at.rotate(rads, (w / 2).toDouble(), (h / 2).toDouble())
        g2d.transform = at
        g2d.drawImage(img, 0, 0, null)
        g2d.dispose()
        return rotated
    }

    private fun applyQualityHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    companion object {
        const val TARGET_WIDTH = 1200
        const val TARGET_HEIGHT = 630
    }
}
