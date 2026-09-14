package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.AdvertStatus
import nl.dichtbij3d.backend.domain.AdvertType
import nl.dichtbij3d.backend.domain.Category
import nl.dichtbij3d.backend.domain.EntitlementSource
import nl.dichtbij3d.backend.domain.Model3d
import nl.dichtbij3d.backend.domain.ModelEntitlement
import nl.dichtbij3d.backend.domain.ModelFile
import nl.dichtbij3d.backend.domain.ModelVisibility
import nl.dichtbij3d.backend.domain.ModelPurchaseRequest
import nl.dichtbij3d.backend.domain.NotificationType
import nl.dichtbij3d.backend.domain.PurchaseRequestStatus
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.AdvertRepository
import nl.dichtbij3d.backend.repo.Model3dRepository
import nl.dichtbij3d.backend.repo.ModelEntitlementRepository
import nl.dichtbij3d.backend.repo.ModelFileRepository
import nl.dichtbij3d.backend.repo.ModelPurchaseRequestRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.beans.factory.ObjectProvider
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
    private val purchaseRepository: ModelPurchaseRequestRepository,
    private val advertRepository: AdvertRepository,
    private val userRepository: UserRepository,
    private val notifications: NotificationService,
    private val chat: ChatService,
    private val blocks: BlockService,
    private val adverts: ObjectProvider<AdvertService>,
    private val storage: StorageService,
    private val mapper: DtoMapper,
) {

    @Transactional(readOnly = true)
    fun browse(
        query: String?,
        categories: List<Category>,
        page: Int,
        size: Int,
        viewer: AppPrincipal?,
    ): PageResponse<ModelSummaryDto> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 60), Sort.by(Sort.Direction.DESC, "createdAt"))
        val q = query?.takeIf { it.isNotBlank() }
        val result = modelRepository.searchPublic(
            q = q,
            categories = categories.ifEmpty { null },
            queryCategories = q?.let { Category.matching(it) }?.ifEmpty { null },
            hidden = blocks.hiddenFor(viewer).ifEmpty { null },
            pageable = pageable,
        )
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
        val isOwner = viewer != null && viewer.id == model.owner.id
        return ModelDetailDto(
            model = mapper.modelSummary(model, access),
            files = model.files.map { mapper.modelFile(it, access) },
            purchaseRequests = if (isOwner) {
                purchaseRepository.findAllByModelIdOrderByCreatedAtDesc(id).map { mapper.modelPurchaseRequest(it) }
            } else {
                emptyList()
            },
            myPurchaseStatus = viewer
                ?.takeIf { !isOwner }
                ?.let { purchaseRepository.findByModelIdAndBuyerId(id, it.id)?.status },
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
            category = request.category,
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
        // Uploading with "list on the marketplace" ticked saves the second trip through
        // the advert form: the matching advert is created here and points at this model.
        if (request.listOnMarketplace && request.visibility == ModelVisibility.PUBLIC) {
            adverts.getObject().create(
                AdvertCreateRequest(
                    type = AdvertType.MODEL_FOR_SALE,
                    category = request.category,
                    title = model.title,
                    description = request.description?.trim()?.ifBlank { null }
                        ?: "3D model available for download.",
                    priceCents = request.priceCents,
                    allowBidding = false,
                    city = request.city,
                    modelId = model.id,
                    tags = request.tags,
                    imageKeys = listOfNotNull(request.thumbnailKey),
                ),
                principal,
                owner.locale,
            )
        }
        return detail(model.id!!, principal)
    }

    @Transactional
    fun delete(id: UUID, principal: AppPrincipal) {
        val model = modelRepository.findById(id).orElseThrow { ApiException.notFound("Model") }
        if (model.owner.id != principal.id && !principal.isAdmin) throw ApiException.forbidden()
        model.deletedAt = Instant.now()
        modelRepository.save(model)

        // Adverts selling this model stay up (they carry the conversation history),
        // but their owner is told that they no longer have files behind them.
        advertRepository.findAllByModelIdAndDeletedAtIsNull(id).forEach { advert ->
            if (advert.status == AdvertStatus.OPEN && advert.type == AdvertType.MODEL_FOR_SALE) {
                notifications.push(
                    userId = advert.author.id!!,
                    type = NotificationType.SYSTEM,
                    title = "\"${advert.title}\" no longer has a model attached",
                    body = "You removed \"${model.title}\". Attach another model or close the advert.",
                    link = "/advert/${advert.id}",
                )
            }
        }
    }

    /** Free models grant access instantly. Paid models must be handed over by their owner. */
    @Transactional
    fun acquire(id: UUID, principal: AppPrincipal): MessageResponse {
        val model = modelRepository.findById(id).orElseThrow { ApiException.notFound("Model") }
        if (model.deletedAt != null) throw ApiException.notFound("Model")
        if (model.owner.id == principal.id) return MessageResponse("You already own this model")
        if (entitlementRepository.existsByModelIdAndUserId(id, principal.id)) {
            return MessageResponse("You already have access to this model")
        }
        if (model.priceCents > 0) {
            throw ApiException.badRequest("This model is for sale; ask the owner for access first")
        }
        entitlementRepository.save(
            ModelEntitlement(modelId = id, userId = principal.id, source = EntitlementSource.SHARE)
        )
        notifications.push(
            userId = model.owner.id!!,
            type = NotificationType.MODEL_PURCHASED,
            title = "Someone downloaded \"${model.title}\"",
            body = principal.displayName,
            link = "/model/$id",
        )
        return MessageResponse("Model added to your library")
    }

    /**
     * Asks the owner of a paid model for access. No payment provider is wired up yet, so the
     * two settle it in a private thread and the owner grants access afterwards. Access is never
     * handed out automatically.
     */
    @Transactional
    fun requestPurchase(id: UUID, request: PurchaseRequest, principal: AppPrincipal): PurchaseResponse {
        val model = modelRepository.findById(id).orElseThrow { ApiException.notFound("Model") }
        if (model.deletedAt != null) throw ApiException.notFound("Model")
        if (model.visibility == ModelVisibility.PRIVATE) throw ApiException.forbidden("This model is private")
        if (model.owner.id == principal.id) throw ApiException.badRequest("You already own this model")
        if (model.priceCents <= 0) throw ApiException.badRequest("This model is free; add it to your library instead")
        if (entitlementRepository.existsByModelIdAndUserId(id, principal.id)) {
            throw ApiException.badRequest("You already have access to this model")
        }

        val buyer = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        val note = request.message?.trim()?.takeIf { it.isNotEmpty() }
        val amount = "%.2f".format(model.priceCents / 100.0)
        val conversation = chat.openDirect(
            peer = model.owner,
            opener = buyer,
            systemLine = "${buyer.displayName} wants to buy the model \"${model.title}\" for $amount ${model.currency}.",
        )
        note?.let { chat.sendAs(conversation, buyer, it) }

        val existing = purchaseRepository.findByModelIdAndBuyerId(id, principal.id)
        val purchase = existing ?: ModelPurchaseRequest(model = model, buyer = buyer)
        purchase.status = PurchaseRequestStatus.PENDING
        purchase.message = note
        purchase.conversationId = conversation.id
        purchase.decidedAt = null
        purchaseRepository.save(purchase)

        notifications.push(
            userId = model.owner.id!!,
            type = NotificationType.MODEL_PURCHASE_REQUEST,
            title = "${buyer.displayName} wants to buy \"${model.title}\"",
            body = "Agree on the payment in your messages, then give them access.",
            link = "/model/$id",
        )
        return PurchaseResponse(conversation.id!!, "The owner has been notified")
    }

    /** The owner hands over (or refuses) access after a purchase was settled. */
    @Transactional
    fun decidePurchase(id: UUID, requestId: UUID, grant: Boolean, principal: AppPrincipal): MessageResponse {
        val model = modelRepository.findById(id).orElseThrow { ApiException.notFound("Model") }
        if (model.owner.id != principal.id) throw ApiException.forbidden("Only the owner can give access")
        val purchase = purchaseRepository.findById(requestId).orElseThrow { ApiException.notFound("Request") }
        if (purchase.model.id != id) throw ApiException.badRequest("Request does not belong to this model")

        purchase.status = if (grant) PurchaseRequestStatus.GRANTED else PurchaseRequestStatus.DECLINED
        purchase.decidedAt = Instant.now()
        purchaseRepository.save(purchase)

        val buyerId = purchase.buyer.id!!
        if (grant && !entitlementRepository.existsByModelIdAndUserId(id, buyerId)) {
            entitlementRepository.save(
                ModelEntitlement(modelId = id, userId = buyerId, source = EntitlementSource.PURCHASE)
            )
        }
        notifications.push(
            userId = buyerId,
            type = if (grant) NotificationType.MODEL_ACCESS_GRANTED else NotificationType.MODEL_PURCHASE_DECLINED,
            title = if (grant) "You now have access to \"${model.title}\"" else "\"${model.title}\" was not released",
            body = if (grant) "The files are ready in your library." else "${model.owner.displayName} declined the request.",
            link = "/model/$id",
        )
        return MessageResponse(if (grant) "Access granted" else "Request declined")
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
