package nl.dichtbij3d.backend.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.dichtbij3d.backend.domain.AdvertStatus
import nl.dichtbij3d.backend.domain.AuditLogEntry
import nl.dichtbij3d.backend.domain.Category
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.*
import org.springframework.core.io.InputStreamResource
import org.springframework.data.domain.PageRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------- users

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userRepository: UserRepository,
    private val storage: StorageService,
    private val blockService: BlockService,
    private val mapper: DtoMapper,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passkeyRepository: PasskeyCredentialRepository,
    private val advertRepository: AdvertRepository,
    private val auditLogRepository: AuditLogRepository,
) {

    @GetMapping
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) role: Role?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: AppPrincipal?,
    ): PageResponse<PublicUserDto> {
        val excluded = buildSet {
            if (principal != null) {
                add(principal.id)
                addAll(blockService.hiddenFor(principal))
            }
        }.takeIf { it.isNotEmpty() }
        val results = userRepository.searchCollaborators(
            q = q?.trim()?.ifBlank { null },
            role = role,
            excludedIds = excluded,
            pageable = PageRequest.of(page, size.coerceIn(1, 50)),
        )
        return PageResponse.of(results.map { mapper.publicUser(it) })
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AppPrincipal): UserProfileDto =
        mapper.profile(userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") })

    @PatchMapping("/me")
    @Transactional
    fun updateMe(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): UserProfileDto {
        val user = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        request.displayName?.let { user.displayName = it.trim() }
        request.bio?.let { user.bio = it.trim().ifBlank { null } }
        request.gender?.let { user.gender = it }
        request.contactEmail?.let { user.contactEmail = it.trim().ifBlank { null } }
        request.contactPhone?.let { user.contactPhone = it.trim().ifBlank { null } }
        request.website?.let { user.website = it.trim().ifBlank { null } }
        request.city?.let { user.city = it.trim().ifBlank { null } }
        request.locale?.let { locale ->
            if (locale.lowercase() in setOf("nl", "en", "de", "fr")) user.locale = locale.lowercase()
        }
        request.roles?.let { roles ->
            // Users manage their own marketplace roles; the admin role can never be self-assigned.
            val keepAdmin = user.roles.contains(nl.dichtbij3d.backend.domain.Role.ADMIN)
            val next = roles.filter { it != nl.dichtbij3d.backend.domain.Role.ADMIN }.toMutableSet()
            if (next.isEmpty()) next.add(nl.dichtbij3d.backend.domain.Role.CUSTOMER)
            if (keepAdmin) next.add(nl.dichtbij3d.backend.domain.Role.ADMIN)
            user.roles = next
        }
        request.mutedNotifications?.let { user.mutedNotifications = it.toMutableSet() }
        return mapper.profile(userRepository.save(user))
    }

    @PostMapping("/me/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Transactional
    fun uploadAvatar(
        @AuthenticationPrincipal principal: AppPrincipal,
        @RequestPart("file") file: MultipartFile,
    ): UserProfileDto {
        if (file.isEmpty) throw ApiException.badRequest("No file uploaded")
        if (file.contentType?.startsWith("image/") != true) throw ApiException.badRequest("Only images are allowed")
        val user = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        user.avatarKey?.let(storage::delete)
        user.avatarKey = storage.store(file, "avatars").key
        return mapper.profile(userRepository.save(user))
    }

    @DeleteMapping("/me")
    @Transactional
    fun deleteMe(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody(required = false) request: DeleteAccountRequest?,
    ): MessageResponse {
        val user = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        if (user.deletedAt != null) throw ApiException.notFound("User")

        if (user.googleId == null) {
            val rawPassword = request?.password?.trim()
            if (rawPassword.isNullOrBlank()) {
                throw ApiException.badRequest("Password is required to delete your account")
            }
            if (!passwordEncoder.matches(rawPassword, user.passwordHash)) {
                throw ApiException.badRequest("Incorrect password")
            }
        }

        user.avatarKey?.let { key ->
            try {
                storage.delete(key)
            } catch (_: Exception) {
                // storage deletion failure should not block account erasure
            }
            user.avatarKey = null
        }

        val now = Instant.now()
        val userId = user.id!!

        refreshTokenRepository.revokeAllForUser(userId, now)
        passkeyRepository.deleteAllByUserId(userId)

        val userAdverts = advertRepository.findAllByAuthorIdAndDeletedAtIsNull(userId)
        userAdverts.forEach { advert ->
            advert.deletedAt = now
            advert.deletedBy = userId
            advert.deletedReason = "Account deleted"
            advert.status = AdvertStatus.CANCELLED
        }
        if (userAdverts.isNotEmpty()) {
            advertRepository.saveAll(userAdverts)
        }

        auditLogRepository.save(
            AuditLogEntry(
                actorId = userId,
                action = "USER_DELETE_SELF",
                targetType = "USER",
                targetId = userId,
                detail = request?.reason?.trim()?.takeIf { it.isNotBlank() } ?: "Account self-deleted",
                createdAt = now,
            )
        )

        user.email = "deleted-$userId@dichtbij3d.invalid"
        user.passwordHash = ""
        user.displayName = "Verwijderde gebruiker"
        user.bio = null
        user.contactEmail = null
        user.contactPhone = null
        user.website = null
        user.city = null
        user.googleId = null
        user.totpSecret = null
        user.totpEnabled = false
        user.lastTotpStep = null
        user.emailMfaEnabled = false
        user.failedMfaAttempts = 0
        user.mfaLockedUntil = null
        user.roles.clear()
        user.mutedNotifications.clear()
        user.enabled = false
        user.disabledReason = request?.reason?.trim()?.takeIf { it.isNotBlank() } ?: "Account deleted by user"
        user.deletedAt = now
        userRepository.save(user)

        return MessageResponse("Account successfully deleted")
    }

    @GetMapping("/blocks")
    fun blocks(@AuthenticationPrincipal principal: AppPrincipal): List<BlockedUserDto> = blockService.list(principal)

    @GetMapping("/{id}")
    fun publicProfile(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AppPrincipal?,
    ): PublicUserDto {
        val user = userRepository.findById(id).orElseThrow { ApiException.notFound("User") }
        if (user.deletedAt != null) throw ApiException.notFound("User")
        val blocked = principal != null && blockService.hasBlocked(principal.id, id)
        return mapper.publicUser(user).copy(blocked = blocked)
    }

    @PostMapping("/{id}/block")
    fun block(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: BlockRequest?,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = blockService.block(id, body?.reason, principal)

    @DeleteMapping("/{id}/block")
    fun unblock(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = blockService.unblock(id, principal)
}

// ---------------------------------------------------------------- tags

@RestController
@RequestMapping("/api/tags")
class TagController(
    private val tagRepository: TagRepository,
    private val mapper: DtoMapper,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "60") limit: Int,
        request: HttpServletRequest,
    ): List<TagDto> {
        val locale = request.locale()
        val pageable = PageRequest.of(0, limit.coerceIn(1, 200))
        val tags = if (q.isNullOrBlank()) {
            tagRepository.findAllOrdered(pageable).content
        } else {
            tagRepository.searchBySlug(q.trim(), pageable)
        }
        return tags.map { mapper.tag(it, locale) }
    }
}

// ---------------------------------------------------------------- notifications

@RestController
@RequestMapping("/api/notifications")
class NotificationController(private val service: NotificationService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AppPrincipal,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "30") size: Int,
    ): PageResponse<NotificationDto> = service.list(principal.id, page, size.coerceIn(1, 100))

    @GetMapping("/unread-count")
    fun unread(@AuthenticationPrincipal principal: AppPrincipal): Map<String, Long> =
        mapOf("count" to service.unreadCount(principal.id))

    @PostMapping("/{id}/read")
    fun read(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: UUID): MessageResponse {
        service.markRead(principal.id, id)
        return MessageResponse("ok")
    }

    @PostMapping("/read-all")
    fun readAll(@AuthenticationPrincipal principal: AppPrincipal): MessageResponse {
        service.markAllRead(principal.id)
        return MessageResponse("ok")
    }

    @DeleteMapping("/{id}")
    fun delete(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: UUID): MessageResponse {
        service.delete(principal.id, id)
        return MessageResponse("ok")
    }
}

// ---------------------------------------------------------------- models

@RestController
@RequestMapping("/api/models")
class ModelController(private val service: ModelService) {

    @GetMapping
    fun browse(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) category: List<Category>?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal principal: AppPrincipal?,
    ): PageResponse<ModelSummaryDto> = service.browse(q, category.orEmpty(), page, size, principal)

    @GetMapping("/mine")
    fun mine(@AuthenticationPrincipal principal: AppPrincipal): List<ModelSummaryDto> = service.mine(principal)

    @GetMapping("/library")
    fun library(@AuthenticationPrincipal principal: AppPrincipal): List<ModelSummaryDto> = service.library(principal)

    @GetMapping("/{id}")
    fun detail(@PathVariable id: UUID, @AuthenticationPrincipal principal: AppPrincipal?): ModelDetailDto =
        service.detail(id, principal)

    @PostMapping
    fun create(
        @Valid @RequestBody request: ModelCreateRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): ModelDetailDto = service.create(request, principal)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID, @AuthenticationPrincipal principal: AppPrincipal): MessageResponse {
        service.delete(id, principal)
        return MessageResponse("Model removed")
    }

    @PostMapping("/{id}/acquire")
    fun acquire(@PathVariable id: UUID, @AuthenticationPrincipal principal: AppPrincipal): MessageResponse =
        service.acquire(id, principal)

    @PostMapping("/{id}/purchase")
    fun requestPurchase(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: PurchaseRequest?,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): PurchaseResponse = service.requestPurchase(id, body ?: PurchaseRequest(), principal)

    @PostMapping("/{id}/purchase-requests/{requestId}/grant")
    fun grantPurchase(
        @PathVariable id: UUID,
        @PathVariable requestId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = service.decidePurchase(id, requestId, true, principal)

    @PostMapping("/{id}/purchase-requests/{requestId}/decline")
    fun declinePurchase(
        @PathVariable id: UUID,
        @PathVariable requestId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = service.decidePurchase(id, requestId, false, principal)

    @GetMapping("/{id}/files/{fileId}/download")
    fun download(
        @PathVariable id: UUID,
        @PathVariable fileId: UUID,
        @AuthenticationPrincipal principal: AppPrincipal?,
    ): ResponseEntity<InputStreamResource> {
        val (stream, fileName, contentType) = service.download(id, fileId, principal)
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName).build().toString()
            )
            .contentType(MediaType.parseMediaType(contentType))
            .body(InputStreamResource(stream))
    }
}

// ---------------------------------------------------------------- calculator

@RestController
@RequestMapping("/api")
class CalculatorController(private val service: CalculatorService) {

    @GetMapping("/printers")
    fun printers(): List<PrinterModelDto> = service.printers()

    @PostMapping("/calculator/estimate")
    fun estimate(@Valid @RequestBody request: CostEstimateRequest): CostEstimateResponse = service.estimate(request)
}

// ---------------------------------------------------------------- files

@RestController
@RequestMapping("/api")
class FileController(private val storage: StorageService) {

    @PostMapping("/uploads", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal principal: AppPrincipal,
        @RequestPart("file") file: MultipartFile,
        @RequestParam(defaultValue = "uploads") folder: String,
    ): UploadResponse {
        if (file.isEmpty) throw ApiException.badRequest("No file uploaded")
        val filteredFolder = folder.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "uploads" }
        val safeFolder = if (filteredFolder == "adverts") "listings" else filteredFolder
        val originalName = file.originalFilename?.lowercase() ?: ""

        // SEC-02: Reject SVG files containing active content / executable scripts
        val isSvg = originalName.endsWith(".svg") || file.contentType?.equals("image/svg+xml", ignoreCase = true) == true
        if (isSvg) {
            val content = file.bytes.toString(Charsets.UTF_8).lowercase()
            val dangerousPatterns = listOf(
                "<script", "javascript:", "onload=", "onerror=", "onclick=",
                "onmouseover=", "onfocus=", "<iframe", "<embed", "<object", "<foreignobject"
            )
            if (dangerousPatterns.any { content.contains(it) }) {
                throw ApiException.badRequest("SVG file contains prohibited scripts or active content")
            }
        }

        if (safeFolder == "models") {
            val isModel = originalName.endsWith(".3mf") || originalName.endsWith(".obj") || originalName.endsWith(".stl")
            val isImage = originalName.endsWith(".png") || originalName.endsWith(".jpg") || originalName.endsWith(".jpeg") ||
                originalName.endsWith(".webp") || originalName.endsWith(".gif") || originalName.endsWith(".heic") || originalName.endsWith(".heif")
            if (!isModel && !isImage) {
                throw ApiException.badRequest("Invalid file type. Only .3mf, .obj, .stl and image files are allowed.")
            }
        }
        val stored = storage.store(file, safeFolder)
        return UploadResponse(stored.key, storage.publicUrl(stored.key) ?: "", stored.fileName, stored.size)
    }

    @GetMapping("/files/**")
    fun serve(request: HttpServletRequest): ResponseEntity<InputStreamResource> {
        val key = request.requestURI.substringAfter("/api/files/")
            .let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        if (key.isBlank() || key.contains("..")) throw ApiException.badRequest("Invalid file key")

        // SEC-01: Prohibit direct public access to private/restricted folders like models/ and chat/
        val normalizedKey = if (key.startsWith("adverts/")) key.replaceFirst("adverts/", "listings/") else key
        val allowedPublicPrefixes = listOf("avatars/", "listings/", "adverts/", "banner/", "banners/", "announcements/", "thumbnails/", "public/")
        if (allowedPublicPrefixes.none { normalizedKey.startsWith(it) }) {
            throw ApiException.notFound("File")
        }

        val (stream, size) = storage.readWithAlias(key) ?: throw ApiException.notFound("File")
        val ext = key.substringAfterLast('.', "").lowercase()
        val contentType = when (ext) {
            "png" -> MediaType.IMAGE_PNG
            "jpg", "jpeg" -> MediaType.IMAGE_JPEG
            "gif" -> MediaType.IMAGE_GIF
            "webp" -> MediaType.parseMediaType("image/webp")
            "svg" -> MediaType.parseMediaType("image/svg+xml")
            "heic" -> MediaType.parseMediaType("image/heic")
            "heif" -> MediaType.parseMediaType("image/heif")
            "mp4", "m4v" -> MediaType.parseMediaType("video/mp4")
            "webm" -> MediaType.parseMediaType("video/webm")
            "mov" -> MediaType.parseMediaType("video/quicktime")
            "ogg", "ogv" -> MediaType.parseMediaType("video/ogg")
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
        val builder = ResponseEntity.ok()
            .contentType(contentType)
            .header("X-Content-Type-Options", "nosniff")
            .header("Accept-Ranges", "bytes")
            .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())

        // SEC-02: Protect against SVG XSS by setting a strict CSP and safe inline disposition
        if (ext == "svg" || contentType == MediaType.parseMediaType("image/svg+xml")) {
            builder.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
            builder.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"image.svg\"")
        }

        if (size != null && size > 0) {
            builder.contentLength(size)
        }
        return builder.body(InputStreamResource(stream))
    }
}

// ---------------------------------------------------------------- reports

@RestController
@RequestMapping("/api/reports")
class ReportController(private val adminService: AdminService) {

    @PostMapping
    fun report(
        @Valid @RequestBody request: ReportCreateRequest,
        @AuthenticationPrincipal principal: AppPrincipal,
    ): MessageResponse = adminService.createReport(request, principal)
}
