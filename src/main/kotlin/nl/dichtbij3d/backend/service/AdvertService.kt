package nl.dichtbij3d.backend.service

import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate
import jakarta.servlet.http.HttpServletRequest
import nl.dichtbij3d.backend.config.ViewProperties
import nl.dichtbij3d.backend.domain.*
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AdvertFilter(
    val query: String? = null,
    val types: List<AdvertType> = emptyList(),
    val categories: List<Category> = emptyList(),
    val tags: List<String> = emptyList(),
    val statuses: List<AdvertStatus> = emptyList(),
    val minPriceCents: Int? = null,
    val maxPriceCents: Int? = null,
    val city: String? = null,
    val authorId: UUID? = null,
    val postedAfter: LocalDate? = null,
    val postedBefore: LocalDate? = null,
    val onlyBiddable: Boolean = false,
    val sort: String = "newest",
    val page: Int = 0,
    val size: Int = 20,
)

@Service
class AdvertService(
    private val advertRepository: AdvertRepository,
    private val advertViewRepository: AdvertViewRepository,
    private val reactionRepository: AdvertReactionRepository,
    private val bidRepository: BidRepository,
    private val tagRepository: TagRepository,
    private val userRepository: UserRepository,
    private val modelRepository: Model3dRepository,
    private val entitlementRepository: ModelEntitlementRepository,
    private val purchaseRepository: ModelPurchaseRequestRepository,
    private val notifications: NotificationService,
    private val chat: ChatService,
    private val blocks: BlockService,
    private val auditLog: AuditLogRepository,
    private val mapper: DtoMapper,
    private val viewProps: ViewProperties,
) {

    // ------------------------------------------------------------- querying

    @Transactional(readOnly = true)
    fun search(filter: AdvertFilter, viewer: AppPrincipal?, locale: String): PageResponse<AdvertSummaryDto> {
        val pageable = PageRequest.of(
            filter.page.coerceAtLeast(0),
            filter.size.coerceIn(1, 60),
            sortOf(filter.sort),
        )
        val page = advertRepository.findAll(specification(filter, viewer), pageable)
        val highestBids = page.content
            .filter { it.allowBidding }
            .associate { it.id!! to bidRepository.highestBid(it.id!!) }
        return PageResponse.of(page.map { mapper.advertSummary(it, locale, highestBids[it.id]) })
    }

    private fun sortOf(sort: String): Sort = when (sort.lowercase()) {
        "oldest" -> Sort.by(Sort.Direction.ASC, "createdAt")
        "views", "most_viewed" -> Sort.by(Sort.Direction.DESC, "viewCount").and(Sort.by(Sort.Direction.DESC, "createdAt"))
        "least_viewed" -> Sort.by(Sort.Direction.ASC, "viewCount")
        "price_asc" -> Sort.by(Sort.Direction.ASC, "priceCents")
        "price_desc" -> Sort.by(Sort.Direction.DESC, "priceCents")
        "popular" -> Sort.by(Sort.Direction.DESC, "reactionCount").and(Sort.by(Sort.Direction.DESC, "viewCount"))
        "deadline" -> Sort.by(Sort.Direction.ASC, "deadline")
        else -> Sort.by(Sort.Direction.DESC, "createdAt")
    }

    private fun specification(filter: AdvertFilter, viewer: AppPrincipal?): Specification<Advert> =
        Specification { root, query, cb ->
            val predicates = mutableListOf<Predicate>()
            predicates += cb.isNull(root.get<Instant>("deletedAt"))

            // Hidden-after-accept adverts are only visible to the author, the accepted
            // helper and moderators.
            val hiddenGuard = mutableListOf<Predicate>()
            hiddenGuard += cb.isFalse(root.get("hiddenAfterAccept"))
            hiddenGuard += cb.equal(root.get<AdvertStatus>("status"), AdvertStatus.OPEN)
            if (viewer != null) {
                hiddenGuard += cb.equal(root.get<User>("author").get<UUID>("id"), viewer.id)
                hiddenGuard += cb.equal(root.get<User>("acceptedBy").get<UUID>("id"), viewer.id)
            }
            if (viewer?.isAdmin != true) predicates += cb.or(*hiddenGuard.toTypedArray())

            filter.query?.trim()?.takeIf { it.isNotBlank() }?.let { q ->
                val like = "%${q.lowercase()}%"
                val matches = mutableListOf<Predicate>(
                    cb.like(cb.lower(root.get("title")), like),
                    cb.like(cb.lower(root.get("description")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("city"), "")), like),
                )
                // Searching for "homelab" or "speelgoed" should return the whole category,
                // not only the adverts that happen to spell the word out.
                Category.matching(q).takeIf { it.isNotEmpty() }?.let { categories ->
                    matches += root.get<Category>("category").`in`(categories)
                }
                predicates += cb.or(*matches.toTypedArray())
            }
            if (filter.types.isNotEmpty()) predicates += root.get<AdvertType>("type").`in`(filter.types)
            if (filter.categories.isNotEmpty()) predicates += root.get<Category>("category").`in`(filter.categories)
            // Blocked in either direction means out of sight, unless it is your own advert.
            blocks.hiddenFor(viewer).takeIf { it.isNotEmpty() }?.let { hidden ->
                predicates += cb.not(root.get<User>("author").get<UUID>("id").`in`(hidden))
            }
            if (filter.statuses.isNotEmpty()) predicates += root.get<AdvertStatus>("status").`in`(filter.statuses)
            if (filter.tags.isNotEmpty()) {
                // Subquery instead of a join so pagination + count queries stay duplicate free.
                val sub = query!!.subquery(UUID::class.java)
                val subRoot = sub.from(Advert::class.java)
                val tagJoin = subRoot.join<Advert, Tag>("tags", JoinType.INNER)
                sub.select(subRoot.get("id"))
                    .where(cb.lower(tagJoin.get("slug")).`in`(filter.tags.map { it.lowercase() }))
                predicates += root.get<UUID>("id").`in`(sub)
            }
            filter.minPriceCents?.let { predicates += cb.greaterThanOrEqualTo(root.get("priceCents"), it) }
            filter.maxPriceCents?.let { predicates += cb.lessThanOrEqualTo(root.get("priceCents"), it) }
            filter.city?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.like(cb.lower(root.get("city")), "%${it.lowercase()}%")
            }
            filter.authorId?.let { predicates += cb.equal(root.get<User>("author").get<UUID>("id"), it) }
            filter.postedAfter?.let {
                predicates += cb.greaterThanOrEqualTo(root.get("createdAt"), it.atStartOfDay(UTC).toInstant())
            }
            filter.postedBefore?.let {
                predicates += cb.lessThan(root.get("createdAt"), it.plusDays(1).atStartOfDay(UTC).toInstant())
            }
            if (filter.onlyBiddable) predicates += cb.isTrue(root.get("allowBidding"))

            cb.and(*predicates.toTypedArray())
        }

    @Transactional(readOnly = true)
    fun detail(id: UUID, viewer: AppPrincipal?, locale: String): AdvertDetailDto {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        if (advert.deletedAt != null && viewer?.isAdmin != true) throw ApiException.notFound("Advert")
        if (!isVisibleTo(advert, viewer)) {
            throw ApiException.forbidden("This advert is only visible to the people involved")
        }
        val isOwner = viewer != null && viewer.id == advert.author.id
        val canModerate = viewer?.isAdmin == true
        val reactions = reactionRepository.findAllByAdvertIdAndDeletedAtIsNullOrderByCreatedAtAsc(id)
        val bids = if (advert.allowBidding) bidRepository.findAllByAdvertIdOrderByAmountCentsDesc(id) else emptyList()

        return AdvertDetailDto(
            id = advert.id!!,
            type = advert.type,
            category = advert.category,
            title = advert.title,
            description = advert.description,
            status = advert.status,
            priceCents = advert.priceCents,
            currency = advert.currency,
            allowBidding = advert.allowBidding,
            budgetMinCents = advert.budgetMinCents,
            budgetMaxCents = advert.budgetMaxCents,
            hiddenAfterAccept = advert.hiddenAfterAccept,
            city = advert.city,
            postalCode = advert.postalCode,
            deadline = advert.deadline,
            viewCount = advert.viewCount,
            imageUrls = advert.images.sortedBy { it.sortOrder }.map { "/api/files/${it.objectKey}" },
            imageKeys = advert.images.sortedBy { it.sortOrder }.map { it.objectKey },
            tags = advert.tags.map { mapper.tag(it, locale) }.sortedBy { it.label },
            author = mapper.publicUser(advert.author),
            acceptedBy = advert.acceptedBy?.let { mapper.publicUser(it) },
            acceptedAt = advert.acceptedAt,
            // A deleted model is not shown as if it were still for sale; the advert
            // survives but says out loud that the files are gone.
            model = advert.model?.takeIf { it.deletedAt == null }?.let { m ->
                mapper.modelSummary(
                    m,
                    hasAccess = viewer != null &&
                        (viewer.id == m.owner.id || entitlementRepository.existsByModelIdAndUserId(m.id!!, viewer.id))
                )
            },
            modelRemoved = advert.model?.deletedAt != null,
            reactions = reactions
                .filter { it.author.id !in blocks.hiddenFor(viewer) }
                .map { mapper.reaction(it, viewer) },
            bids = bids.map(mapper::bid),
            highestBidCents = if (advert.allowBidding) bidRepository.highestBid(id) else null,
            canEdit = isOwner,
            canModerate = canModerate,
            createdAt = advert.createdAt,
            updatedAt = advert.updatedAt,
        )
    }

    private fun isVisibleTo(advert: Advert, viewer: AppPrincipal?): Boolean {
        if (!advert.hiddenAfterAccept || advert.status == AdvertStatus.OPEN) return true
        if (viewer == null) return false
        return viewer.isAdmin || viewer.id == advert.author.id || viewer.id == advert.acceptedBy?.id
    }

    // ------------------------------------------------------------- mutations

    @Transactional
    fun create(request: AdvertCreateRequest, principal: AppPrincipal, locale: String): AdvertDetailDto {
        val author = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        if (request.type.isSale && request.priceCents == null && !request.allowBidding) {
            throw ApiException.badRequest("A sale advert needs either a fixed price or bidding enabled")
        }
        val model = request.modelId?.let { id ->
            modelRepository.findById(id).orElseThrow { ApiException.notFound("Model") }.also {
                if (it.owner.id != principal.id) throw ApiException.forbidden("You can only attach your own models")
                if (it.deletedAt != null) throw ApiException.badRequest("That model no longer exists")
            }
        }
        // A "model for sale" without a model is just a promise. Selling a model means
        // handing over files, so the advert always carries the model it sells.
        if (request.type == AdvertType.MODEL_FOR_SALE && model == null) {
            throw ApiException.badRequest("Attach the 3D model you are selling", mapOf("modelId" to "Select or upload a model"))
        }
        model?.let {
            // Advert and model must not disagree about price or category.
            if (request.type == AdvertType.MODEL_FOR_SALE) {
                it.priceCents = request.priceCents ?: it.priceCents
                it.category = request.category
                modelRepository.save(it)
            }
        }
        val advert = Advert(
            author = author,
            type = request.type,
            category = request.category,
            title = request.title.trim(),
            description = request.description.trim(),
            priceCents = request.priceCents,
            allowBidding = request.allowBidding,
            budgetMinCents = request.budgetMinCents,
            budgetMaxCents = request.budgetMaxCents,
            hiddenAfterAccept = request.hiddenAfterAccept,
            city = request.city?.trim()?.ifBlank { null } ?: author.city,
            postalCode = request.postalCode?.trim()?.ifBlank { null },
            deadline = request.deadline,
            model = model,
            tags = resolveTags(request.tags).toMutableSet(),
        )
        request.imageKeys.forEachIndexed { index, key ->
            advert.images.add(AdvertImage(advert = advert, objectKey = key, sortOrder = index))
        }
        advertRepository.save(advert)
        return detail(advert.id!!, principal, locale)
    }

    @Transactional
    fun update(id: UUID, request: AdvertUpdateRequest, principal: AppPrincipal, locale: String): AdvertDetailDto {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        if (advert.author.id != principal.id && !principal.isAdmin) throw ApiException.forbidden()

        request.category?.let { category ->
            advert.category = category
            advert.model?.takeIf { it.deletedAt == null }?.let { it.category = category; modelRepository.save(it) }
        }
        request.title?.let { advert.title = it.trim() }
        request.description?.let { advert.description = it.trim() }
        request.priceCents?.let {
            advert.priceCents = it
            if (advert.type == AdvertType.MODEL_FOR_SALE) {
                advert.model
                    ?.takeIf { model -> model.deletedAt == null }
                    ?.let { model -> model.priceCents = it; modelRepository.save(model) }
            }
        }
        request.allowBidding?.let { advert.allowBidding = it }
        request.budgetMinCents?.let { advert.budgetMinCents = it }
        request.budgetMaxCents?.let { advert.budgetMaxCents = it }
        request.hiddenAfterAccept?.let { advert.hiddenAfterAccept = it }
        request.city?.let { advert.city = it.trim().ifBlank { null } }
        request.postalCode?.let { advert.postalCode = it.trim().ifBlank { null } }
        request.deadline?.let { advert.deadline = it }
        request.status?.let { advert.status = it }
        // Swapping in another model is how an advert recovers after its model was removed.
        request.modelId?.let { modelId ->
            val replacement = modelRepository.findById(modelId).orElseThrow { ApiException.notFound("Model") }
            if (replacement.owner.id != advert.author.id) {
                throw ApiException.forbidden("You can only attach your own models")
            }
            if (replacement.deletedAt != null) throw ApiException.badRequest("That model no longer exists")
            advert.model = replacement
            if (advert.type == AdvertType.MODEL_FOR_SALE) {
                replacement.category = advert.category
                advert.priceCents?.let { replacement.priceCents = it }
                modelRepository.save(replacement)
            }
        }
        request.tags?.let { advert.tags = resolveTags(it).toMutableSet() }
        request.imageKeys?.let { keys ->
            advert.images.clear()
            keys.forEachIndexed { index, key ->
                advert.images.add(AdvertImage(advert = advert, objectKey = key, sortOrder = index))
            }
        }
        advert.updatedAt = Instant.now()
        advertRepository.save(advert)
        return detail(id, principal, locale)
    }

    @Transactional
    fun delete(id: UUID, principal: AppPrincipal, reason: String?) {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        val isOwner = advert.author.id == principal.id
        if (!isOwner && !principal.isAdmin) throw ApiException.forbidden()

        advert.deletedAt = Instant.now()
        advert.deletedBy = principal.id
        advert.deletedReason = reason
        advert.status = AdvertStatus.REMOVED
        advertRepository.save(advert)

        if (!isOwner) {
            notifications.push(
                userId = advert.author.id!!,
                type = NotificationType.ADVERT_REMOVED,
                title = "Your advert \"${advert.title}\" was removed",
                body = reason ?: "This advert violated the platform guidelines.",
            )
            auditLog.save(
                AuditLogEntry(
                    actorId = principal.id,
                    action = "ADVERT_REMOVED",
                    targetType = "ADVERT",
                    targetId = advert.id,
                    detail = reason,
                )
            )
        }
    }

    @Transactional
    fun accept(id: UUID, reactionId: UUID?, principal: AppPrincipal, helperId: UUID?): MessageResponse {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        if (advert.author.id != principal.id) throw ApiException.forbidden("Only the author can accept a helper")
        if (advert.status != AdvertStatus.OPEN) throw ApiException.badRequest("This advert is no longer open")
        if (advert.type.isSale) {
            throw ApiException.badRequest("Sale adverts are closed by accepting a bid or by marking them sold")
        }

        val chosenId = helperId ?: reactionId?.let { rid ->
            reactionRepository.findById(rid).orElseThrow { ApiException.notFound("Reaction") }.author.id
        } ?: throw ApiException.badRequest("Specify who accepted the job")

        val helper = userRepository.findById(chosenId).orElseThrow { ApiException.notFound("User") }
        advert.acceptedBy = helper
        advert.acceptedAt = Instant.now()
        advert.status = AdvertStatus.ACCEPTED
        advertRepository.save(advert)

        notifications.push(
            userId = helper.id!!,
            type = NotificationType.ADVERT_ACCEPTED,
            title = "You got the job: ${advert.title}",
            body = "${advert.author.displayName} accepted you for this request.",
            link = "/advert/${advert.id}",
        )
        // Both sides need a direct line now that they are working together.
        chat.openForAdvert(
            advert = advert,
            peer = helper,
            opener = advert.author,
            systemLine = "${advert.author.displayName} accepted ${helper.displayName} for \"${advert.title}\".",
        )
        return MessageResponse("Job accepted")
    }

    @Transactional
    fun updateStatus(id: UUID, status: AdvertStatus, principal: AppPrincipal): MessageResponse {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        if (advert.author.id != principal.id && !principal.isAdmin) throw ApiException.forbidden()
        advert.status = status
        advertRepository.save(advert)
        return MessageResponse("Status updated")
    }

    // ------------------------------------------------------------- reactions

    @Transactional
    fun addReaction(id: UUID, request: ReactionCreateRequest, principal: AppPrincipal): ReactionDto {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        if (advert.deletedAt != null) throw ApiException.badRequest("This advert is no longer available")
        if (!isVisibleTo(advert, principal)) throw ApiException.forbidden()
        val author = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }

        val reaction = reactionRepository.save(
            AdvertReaction(
                advert = advert,
                author = author,
                body = request.body.trim(),
                // "I want this job" only exists on requests; on a sale advert every reaction is a question.
                isApplication = request.isApplication && advert.type.isRequest,
            )
        )
        advert.reactionCount = reactionRepository.countByAdvertIdAndDeletedAtIsNull(id).toInt()
        advertRepository.save(advert)

        if (advert.author.id != principal.id) {
            notifications.push(
                userId = advert.author.id!!,
                type = NotificationType.ADVERT_REACTION,
                title = "New reaction on \"${advert.title}\"",
                body = "${author.displayName}: ${request.body.take(120)}",
                link = "/advert/${advert.id}",
            )
        }
        return mapper.reaction(reaction, principal)
    }

    @Transactional
    fun deleteReaction(advertId: UUID, reactionId: UUID, principal: AppPrincipal, reason: String?) {
        val reaction = reactionRepository.findById(reactionId).orElseThrow { ApiException.notFound("Reaction") }
        val allowed = principal.isAdmin ||
            reaction.author.id == principal.id ||
            reaction.advert.author.id == principal.id
        if (!allowed) throw ApiException.forbidden()

        reaction.deletedAt = Instant.now()
        reaction.deletedReason = reason
        reactionRepository.save(reaction)

        val advert = reaction.advert
        advert.reactionCount = reactionRepository.countByAdvertIdAndDeletedAtIsNull(advertId).toInt()
        advertRepository.save(advert)

        if (principal.isAdmin && reaction.author.id != principal.id) {
            notifications.push(
                userId = reaction.author.id!!,
                type = NotificationType.ADVERT_REMOVED,
                title = "Your reaction was removed",
                body = reason ?: "The reaction violated the platform guidelines.",
                link = "/advert/$advertId",
            )
        }
    }

    // ------------------------------------------------------------- buying

    /**
     * Buy-now intent on a sale advert. There is no payment provider yet, so - like Marktplaats -
     * the platform introduces buyer and seller in a private thread and lets them settle it there.
     */
    @Transactional
    fun buy(id: UUID, request: PurchaseRequest, principal: AppPrincipal): PurchaseResponse {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        if (advert.deletedAt != null) throw ApiException.badRequest("This advert is no longer available")
        if (!isVisibleTo(advert, principal)) throw ApiException.forbidden()
        if (!advert.type.isSale) throw ApiException.badRequest("This advert is not for sale")
        if (advert.status != AdvertStatus.OPEN) throw ApiException.badRequest("This advert is no longer available")
        if (advert.author.id == principal.id) throw ApiException.badRequest("You cannot buy your own advert")
        if (advert.type == AdvertType.MODEL_FOR_SALE && advert.model?.deletedAt != null) {
            throw ApiException.badRequest("The seller removed this model, so it can no longer be bought")
        }
        val price = advert.priceCents
            ?: throw ApiException.badRequest("This advert has no fixed price; place a bid instead")

        if (blocks.isBlocked(principal.id, advert.author.id!!)) {
            throw ApiException.forbidden("You cannot buy from someone you blocked")
        }
        val buyer = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        val amount = "%.2f".format(price / 100.0)

        val conversation = chat.openForAdvert(
            advert = advert,
            peer = advert.author,
            opener = buyer,
            systemLine = "${buyer.displayName} wants to buy \"${advert.title}\" for $amount ${advert.currency}.",
        )
        request.message?.trim()?.takeIf { it.isNotEmpty() }?.let { chat.sendAs(conversation, buyer, it) }

        // A model advert sells files, so it uses the same purchase request as the model page:
        // the owner hands over access once payment is settled, and the buyer's library fills up.
        val model = advert.model
        if (advert.type == AdvertType.MODEL_FOR_SALE && model != null) {
            val purchase = purchaseRepository.findByModelIdAndBuyerId(model.id!!, principal.id)
                ?: ModelPurchaseRequest(model = model, buyer = buyer)
            purchase.status = PurchaseRequestStatus.PENDING
            purchase.message = request.message?.trim()?.ifBlank { null }
            purchase.conversationId = conversation.id
            purchase.decidedAt = null
            purchaseRepository.save(purchase)
        }

        notifications.push(
            userId = advert.author.id!!,
            type = NotificationType.ADVERT_PURCHASE_REQUEST,
            title = "${buyer.displayName} wants to buy \"${advert.title}\"",
            body = if (advert.type == AdvertType.MODEL_FOR_SALE) {
                "Agree on the payment in your messages, then give them access to the files."
            } else {
                "Agree on the details in your messages, then mark the advert as sold."
            },
            link = if (advert.type == AdvertType.MODEL_FOR_SALE && model != null) {
                "/model/${model.id}"
            } else {
                "/advert/${advert.id}"
            },
        )
        return PurchaseResponse(conversation.id!!, "The seller has been notified")
    }

    // ------------------------------------------------------------- bids

    @Transactional
    fun placeBid(id: UUID, request: BidCreateRequest, principal: AppPrincipal): BidDto {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        if (!advert.allowBidding) throw ApiException.badRequest("Bidding is not enabled for this advert")
        if (advert.status != AdvertStatus.OPEN) throw ApiException.badRequest("This advert is closed")
        if (advert.author.id == principal.id) throw ApiException.badRequest("You cannot bid on your own advert")

        val current = bidRepository.highestBid(id) ?: 0
        if (request.amountCents <= current) {
            throw ApiException.badRequest("Your bid must be higher than the current highest bid")
        }
        val bidder = userRepository.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        val bid = bidRepository.save(
            Bid(
                advert = advert,
                bidder = bidder,
                amountCents = request.amountCents,
                message = request.message?.trim(),
            )
        )
        advert.bidCount = bidRepository.countByAdvertId(id).toInt()
        advertRepository.save(advert)

        notifications.push(
            userId = advert.author.id!!,
            type = NotificationType.BID_PLACED,
            title = "New bid on \"${advert.title}\"",
            body = "${bidder.displayName} bid ${"%.2f".format(request.amountCents / 100.0)} EUR",
            link = "/advert/${advert.id}",
        )
        chat.openForAdvert(
            advert = advert,
            peer = advert.author,
            opener = bidder,
            systemLine = "${bidder.displayName} bid ${"%.2f".format(request.amountCents / 100.0)} EUR on " +
                "\"${advert.title}\".",
        )
        return mapper.bid(bid)
    }

    @Transactional
    fun decideBid(advertId: UUID, bidId: UUID, accept: Boolean, principal: AppPrincipal): MessageResponse {
        val advert = advertRepository.findById(advertId).orElseThrow { ApiException.notFound("Advert") }
        if (advert.author.id != principal.id) throw ApiException.forbidden()
        val bid = bidRepository.findById(bidId).orElseThrow { ApiException.notFound("Bid") }
        if (bid.advert.id != advertId) throw ApiException.badRequest("Bid does not belong to this advert")

        bid.status = if (accept) BidStatus.ACCEPTED else BidStatus.REJECTED
        bid.updatedAt = Instant.now()
        bidRepository.save(bid)

        if (accept) {
            advert.status = AdvertStatus.SOLD
            advert.acceptedBy = bid.bidder
            advert.acceptedAt = Instant.now()
            advertRepository.save(advert)
            bidRepository.findAllByAdvertIdOrderByAmountCentsDesc(advertId)
                .filter { it.id != bidId && it.status == BidStatus.PENDING }
                .forEach {
                    it.status = BidStatus.REJECTED
                    bidRepository.save(it)
                }
        }
        notifications.push(
            userId = bid.bidder.id!!,
            type = if (accept) NotificationType.BID_ACCEPTED else NotificationType.BID_REJECTED,
            title = if (accept) "Your bid was accepted!" else "Your bid was declined",
            body = advert.title,
            link = "/advert/$advertId",
        )
        if (accept) {
            chat.openForAdvert(
                advert = advert,
                peer = bid.bidder,
                opener = advert.author,
                systemLine = "${advert.author.displayName} accepted the bid of ${bid.bidder.displayName} on " +
                    "\"${advert.title}\". Arrange the details here.",
            )
        }
        return MessageResponse(if (accept) "Bid accepted" else "Bid rejected")
    }

    // ------------------------------------------------------------- view counting

    /**
     * Smart view counting.
     *
     * A view only counts when:
     *  - the viewer is not the author (self views never count);
     *  - the client reported enough dwell time (spam clicks are ignored);
     *  - this (advert, viewer, time bucket) combination has not been seen yet.
     *
     * The uniqueness is enforced by a database constraint, so concurrent
     * requests cannot inflate the counter either.
     */
    @Transactional
    fun registerView(id: UUID, viewer: AppPrincipal?, request: HttpServletRequest, dwellMillis: Long): Int {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        if (advert.deletedAt != null) throw ApiException.notFound("Advert")
        if (viewer != null && viewer.id == advert.author.id) return advert.viewCount
        if (dwellMillis in 1 until viewProps.minDwellMillis) return advert.viewCount

        val viewerKey = viewer?.id?.toString() ?: fingerprint(request)
        val bucket = Instant.now().epochSecond / viewProps.dedupWindow.seconds
        val inserted = advertViewRepository.tryRecord(id, viewerKey, bucket)
        if (inserted > 0) {
            advertRepository.incrementViewCount(id)
            return advert.viewCount + 1
        }
        return advert.viewCount
    }

    private fun fingerprint(request: HttpServletRequest): String {
        val ip = request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr ?: "unknown"
        val ua = request.getHeader("User-Agent") ?: ""
        val digest = MessageDigest.getInstance("SHA-256").digest("$ip|$ua".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(64)
    }

    // ------------------------------------------------------------- tags

    @Transactional
    fun resolveTags(slugs: List<String>): List<Tag> {
        val normalized = slugs
            .map { it.trim().lowercase().replace(Regex("[^a-z0-9\\- ]"), "").replace(' ', '-') }
            .filter { it.length in 2..60 }
            .distinct()
            .take(12)
        if (normalized.isEmpty()) return emptyList()

        val existing = tagRepository.findAllBySlugIn(normalized).associateBy { it.slug }
        return normalized.map { slug ->
            val tag = existing[slug] ?: tagRepository.save(
                Tag(
                    slug = slug,
                    labelNl = slug.humanize(),
                    labelEn = slug.humanize(),
                    labelDe = slug.humanize(),
                    labelFr = slug.humanize(),
                    isDefault = false,
                )
            )
            tag.usageCount += 1
            tagRepository.save(tag)
        }
    }

    private fun String.humanize() = split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    companion object {
        private val UTC = java.time.ZoneOffset.UTC
    }
}
