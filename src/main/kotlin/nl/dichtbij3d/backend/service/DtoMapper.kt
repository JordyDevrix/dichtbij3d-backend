package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.*
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.PasskeyCredentialRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import org.springframework.stereotype.Service

@Service
class DtoMapper(
    private val storage: StorageService,
    private val passkeyRepository: PasskeyCredentialRepository,
) {

    fun tag(tag: Tag, locale: String = "nl"): TagDto {
        val labels = mapOf(
            "nl" to tag.labelNl,
            "en" to tag.labelEn,
            "de" to tag.labelDe,
            "fr" to tag.labelFr,
        )
        return TagDto(
            id = tag.id!!,
            slug = tag.slug,
            label = labels[locale] ?: tag.labelEn,
            labels = labels,
            isDefault = tag.isDefault,
            usageCount = tag.usageCount,
        )
    }

    fun publicUser(user: User): PublicUserDto = PublicUserDto(
        id = user.id!!,
        displayName = user.displayName,
        avatarUrl = storage.publicUrl(user.avatarKey),
        roles = user.roles.filter { it != Role.ADMIN }.toSet().ifEmpty { setOf(Role.CUSTOMER) },
        city = user.city,
        bio = user.bio,
        memberSince = user.createdAt,
    )

    fun profile(user: User): UserProfileDto = UserProfileDto(
        id = user.id!!,
        email = user.email,
        displayName = user.displayName,
        bio = user.bio,
        avatarUrl = storage.publicUrl(user.avatarKey),
        gender = user.gender,
        contactEmail = user.contactEmail,
        contactPhone = user.contactPhone,
        website = user.website,
        city = user.city,
        locale = user.locale,
        roles = user.roles.toSet(),
        mutedNotifications = user.mutedNotifications.toSet(),
        enabled = user.enabled,
        totpEnabled = user.totpEnabled,
        passkeyCount = passkeyRepository.countByUserId(user.id!!).toInt(),
        createdAt = user.createdAt,
    )

    fun advertSummary(advert: Advert, locale: String, highestBid: Int? = null): AdvertSummaryDto = AdvertSummaryDto(
        id = advert.id!!,
        type = advert.type,
        category = advert.category,
        title = advert.title,
        excerpt = advert.description.take(200),
        status = advert.status,
        priceCents = advert.priceCents,
        currency = advert.currency,
        allowBidding = advert.allowBidding,
        highestBidCents = highestBid,
        budgetMinCents = advert.budgetMinCents,
        budgetMaxCents = advert.budgetMaxCents,
        city = advert.city,
        deadline = advert.deadline,
        viewCount = advert.viewCount,
        reactionCount = advert.reactionCount,
        bidCount = advert.bidCount,
        coverImageUrl = storage.publicUrl(advert.images.minByOrNull { it.sortOrder }?.objectKey),
        tags = advert.tags.map { tag(it, locale) }.sortedBy { it.label },
        author = publicUser(advert.author),
        createdAt = advert.createdAt,
    )

    fun reaction(reaction: AdvertReaction, viewer: AppPrincipal?): ReactionDto = ReactionDto(
        id = reaction.id!!,
        body = reaction.body,
        isApplication = reaction.isApplication,
        author = publicUser(reaction.author),
        createdAt = reaction.createdAt,
        canDelete = viewer != null &&
            (viewer.isAdmin || viewer.id == reaction.author.id || viewer.id == reaction.advert.author.id),
    )

    fun bid(bid: Bid): BidDto = BidDto(
        id = bid.id!!,
        amountCents = bid.amountCents,
        message = bid.message,
        status = bid.status,
        bidder = publicUser(bid.bidder),
        createdAt = bid.createdAt,
    )

    fun modelPurchaseRequest(request: ModelPurchaseRequest): ModelPurchaseRequestDto = ModelPurchaseRequestDto(
        id = request.id!!,
        buyer = publicUser(request.buyer),
        status = request.status,
        message = request.message,
        conversationId = request.conversationId,
        createdAt = request.createdAt,
    )

    fun modelSummary(model: Model3d, hasAccess: Boolean): ModelSummaryDto = ModelSummaryDto(
        id = model.id!!,
        title = model.title,
        description = model.description,
        category = model.category,
        license = model.license,
        priceCents = model.priceCents,
        currency = model.currency,
        visibility = model.visibility,
        thumbnailUrl = storage.publicUrl(model.thumbnailKey),
        fileCount = model.files.size,
        downloadCount = model.downloadCount,
        owner = publicUser(model.owner),
        hasAccess = hasAccess,
        createdAt = model.createdAt,
    )

    fun modelFile(file: ModelFile, hasAccess: Boolean): ModelFileDto = ModelFileDto(
        id = file.id!!,
        fileName = file.fileName,
        contentType = file.contentType,
        sizeBytes = file.sizeBytes,
        downloadUrl = if (hasAccess) "/api/models/${file.model.id}/files/${file.id}/download" else null,
    )

    fun notification(notification: Notification): NotificationDto = NotificationDto(
        id = notification.id!!,
        type = notification.type,
        title = notification.title,
        body = notification.body,
        link = notification.link,
        readAt = notification.readAt,
        createdAt = notification.createdAt,
    )

    fun printer(printer: PrinterModel): PrinterModelDto = PrinterModelDto(
        id = printer.id!!,
        brand = printer.brand,
        name = printer.name,
        watts = printer.watts,
        standbyWatts = printer.standbyWatts,
        technology = printer.technology,
        purchasePriceCents = printer.purchasePriceCents,
        expectedLifetimeHours = printer.expectedLifetimeHours,
    )
}
