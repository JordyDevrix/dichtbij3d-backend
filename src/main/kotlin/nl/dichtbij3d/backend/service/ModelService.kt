package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.EntitlementSource
import nl.dichtbij3d.backend.domain.Model3d
import nl.dichtbij3d.backend.domain.ModelEntitlement
import nl.dichtbij3d.backend.domain.ModelFile
import nl.dichtbij3d.backend.domain.ModelVisibility
import nl.dichtbij3d.backend.domain.NotificationType
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.Model3dRepository
import nl.dichtbij3d.backend.repo.ModelEntitlementRepository
import nl.dichtbij3d.backend.repo.ModelFileRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.time.Instant
import java.util.UUID

@Service
class ModelService(
    private val modelRepository: Model3dRepository,
    private val fileRepository: ModelFileRepository,
    private val entitlementRepository: ModelEntitlementRepository,
    private val userRepository: UserRepository,
    private val notifications: NotificationService,
    private val storage: StorageService,
    private val mapper: DtoMapper,
) {

    @Transactional(readOnly = true)
    fun browse(query: String?, page: Int, size: Int, viewer: AppPrincipal?): PageResponse<ModelSummaryDto> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 60), Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = modelRepository.searchPublic(query?.takeIf { it.isNotBlank() }, pageable)
        return PageResponse.of(result.map { mapper.modelSummary(it, hasAccess(it, viewer)) })
    }

    @Transactional(readOnly = true)
    fun mine(viewer: AppPrincipal): List<ModelSummaryDto> =
        modelRepository.findAllByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(viewer.id)
            .map { mapper.modelSummary(it, true) }

    @Transactional(readOnly = true)
    fun detail(id: UUID, viewer: AppPrincipal?): ModelDetailDto {
        val model = modelRepository.findById(id).orElseThrow { ApiException.notFound("Model") }
        if (model.deletedAt != null) throw ApiException.notFound("Model")
        if (model.visibility == ModelVisibility.PRIVATE && viewer?.id != model.owner.id && viewer?.isAdmin != true) {
            throw ApiException.forbidden("This model is private")
        }
        val access = hasAccess(model, viewer)
        return ModelDetailDto(
            model = mapper.modelSummary(model, access),
            files = model.files.map { mapper.modelFile(it, access) },
        )
    }

    @Transactional
    fun create(request: ModelCreateRequest, principal: AppPrincipal): ModelDetailDto {
        if (request.files.isEmpty()) throw ApiException.badRequest("Upload at least one model file")
        val owner = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        val model = Model3d(
            owner = owner,
            title = request.title.trim(),
            description = request.description?.trim(),
            license = request.license,
            priceCents = request.priceCents,
            visibility = request.visibility,
            thumbnailKey = request.thumbnailKey,
        )
        request.files.forEachIndexed { index, ref ->
            model.files.add(
                ModelFile(
                    model = model,
                    objectKey = ref.objectKey,
                    fileName = ref.fileName,
                    contentType = ref.contentType,
                    sizeBytes = ref.sizeBytes,
                    sortOrder = index,
                )
            )
        }
        modelRepository.save(model)
        return detail(model.id!!, principal)
    }

    @Transactional
    fun delete(id: UUID, principal: AppPrincipal) {
        val model = modelRepository.findById(id).orElseThrow { ApiException.notFound("Model") }
        if (model.owner.id != principal.id && !principal.isAdmin) throw ApiException.forbidden()
        model.deletedAt = Instant.now()
        modelRepository.save(model)
    }

    /** Free models grant access instantly; paid models are "purchased" (payment provider is out of scope). */
    @Transactional
    fun acquire(id: UUID, principal: AppPrincipal): MessageResponse {
        val model = modelRepository.findById(id).orElseThrow { ApiException.notFound("Model") }
        if (model.deletedAt != null) throw ApiException.notFound("Model")
        if (model.owner.id == principal.id) return MessageResponse("You already own this model")
        if (entitlementRepository.existsByModelIdAndUserId(id, principal.id)) {
            return MessageResponse("You already have access to this model")
        }
        entitlementRepository.save(
            ModelEntitlement(
                modelId = id,
                userId = principal.id,
                source = if (model.isFree) EntitlementSource.SHARE else EntitlementSource.PURCHASE,
            )
        )
        notifications.push(
            userId = model.owner.id!!,
            type = NotificationType.MODEL_PURCHASED,
            title = if (model.isFree) "Someone downloaded \"${model.title}\"" else "\"${model.title}\" was purchased",
            body = principal.displayName,
            link = "/model/$id",
        )
        return MessageResponse(if (model.isFree) "Model added to your library" else "Purchase completed")
    }

    @Transactional
    fun download(modelId: UUID, fileId: UUID, principal: AppPrincipal): Triple<InputStream, String, String> {
        val model = modelRepository.findById(modelId).orElseThrow { ApiException.notFound("Model") }
        if (!hasAccess(model, principal)) throw ApiException.forbidden("Purchase this model to download it")
        val file = fileRepository.findById(fileId).orElseThrow { ApiException.notFound("File") }
        if (file.model.id != modelId) throw ApiException.badRequest("File does not belong to this model")
        val stream = storage.read(file.objectKey) ?: throw ApiException.notFound("File contents")
        model.downloadCount += 1
        modelRepository.save(model)
        return Triple(stream, file.fileName, file.contentType)
    }

    @Transactional(readOnly = true)
    fun library(principal: AppPrincipal): List<ModelSummaryDto> =
        entitlementRepository.findAllByUserId(principal.id)
            .mapNotNull { modelRepository.findById(it.modelId).orElse(null) }
            .filter { it.deletedAt == null }
            .map { mapper.modelSummary(it, true) }

    private fun hasAccess(model: Model3d, viewer: AppPrincipal?): Boolean {
        if (viewer == null) return false
        if (viewer.isAdmin || viewer.id == model.owner.id) return true
        return entitlementRepository.existsByModelIdAndUserId(model.id!!, viewer.id)
    }
}
