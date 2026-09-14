package nl.dichtbij3d.backend.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.dichtbij3d.backend.domain.Category
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.TagRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.*
import org.springframework.core.io.InputStreamResource
import org.springframework.data.domain.PageRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

// ---------------------------------------------------------------- users

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userRepository: UserRepository,
    private val storage: StorageService,
    private val blockService: BlockService,
    private val mapper: DtoMapper,
) {

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
        @AuthenticationPrincipal principal: AppPrincipal,
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
        val safeFolder = folder.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "uploads" }
        if (safeFolder == "models") {
            val originalName = file.originalFilename?.lowercase() ?: ""
            if (!originalName.endsWith(".3mf") && !originalName.endsWith(".obj") && !originalName.endsWith(".stl")) {
                throw ApiException.badRequest("Invalid file type. Only .3mf, .obj, and .stl files are allowed.")
            }
        }
        val stored = storage.store(file, safeFolder)
        return UploadResponse(stored.key, "/api/files/${stored.key}", stored.fileName, stored.size)
    }

    @GetMapping("/files/**")
    fun serve(request: HttpServletRequest): ResponseEntity<InputStreamResource> {
        val key = request.requestURI.substringAfter("/api/files/")
            .let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        if (key.isBlank() || key.contains("..")) throw ApiException.badRequest("Invalid file key")
        val stream = storage.read(key) ?: throw ApiException.notFound("File")
        val contentType = when (key.substringAfterLast('.', "").lowercase()) {
            "png" -> MediaType.IMAGE_PNG
            "jpg", "jpeg" -> MediaType.IMAGE_JPEG
            "gif" -> MediaType.IMAGE_GIF
            "webp" -> MediaType.parseMediaType("image/webp")
            "svg" -> MediaType.parseMediaType("image/svg+xml")
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
        return ResponseEntity.ok()
            .contentType(contentType)
            .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
            .body(InputStreamResource(stream))
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
