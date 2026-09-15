package nl.dichtbij3d.backend.repo

import nl.dichtbij3d.backend.domain.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    @Query("select u from User u where lower(u.email) = lower(:email) and u.deletedAt is null")
    fun findByEmail(@Param("email") email: String): User?

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    fun existsByEmail(@Param("email") email: String): Boolean

    fun countByCreatedAtAfter(instant: Instant): Long

    @Query(
        """
        select u from User u
        where u.deletedAt is null
          and (:q is null or lower(u.displayName) like lower(concat('%', cast(:q as string), '%'))
                          or lower(u.email) like lower(concat('%', cast(:q as string), '%')))
        """
    )
    fun search(@Param("q") q: String?, pageable: Pageable): Page<User>

    @Query(
        """
        select distinct u from User u left join u.roles r
        where u.deletedAt is null and u.enabled = true
          and (:excludedIds is null or u.id not in :excludedIds)
          and (:role is null or r = :role)
          and (:q is null
               or lower(u.displayName) like lower(concat('%', cast(:q as string), '%'))
               or lower(coalesce(u.city, '')) like lower(concat('%', cast(:q as string), '%'))
               or lower(coalesce(u.bio, '')) like lower(concat('%', cast(:q as string), '%')))
        order by u.displayName asc
        """
    )
    fun searchCollaborators(
        @Param("q") q: String?,
        @Param("role") role: Role?,
        @Param("excludedIds") excludedIds: Collection<UUID>?,
        pageable: Pageable,
    ): Page<User>
}

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshToken?

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    fun revokeFamily(@Param("familyId") familyId: UUID, @Param("now") now: Instant)

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    fun revokeAllForUser(@Param("userId") userId: UUID, @Param("now") now: Instant)

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant)

    fun findAllByUserIdAndRevokedAtIsNull(userId: UUID): List<RefreshToken>
}

@Repository
interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, UUID> {
    fun findByTokenHash(tokenHash: String): PasswordResetToken?

    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :now or t.used = true")
    fun deleteExpiredOrUsed(@Param("now") now: Instant)
}

@Repository
interface EmailMfaTokenRepository : JpaRepository<EmailMfaToken, UUID> {
    fun findFirstByUserIdAndPurposeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
        userId: UUID,
        purpose: String,
        now: Instant,
    ): EmailMfaToken?

    fun findAllByUserIdAndPurposeAndUsedFalse(
        userId: UUID,
        purpose: String,
    ): List<EmailMfaToken>

    @Modifying
    @Query("delete from EmailMfaToken t where t.expiresAt < :now or t.used = true")
    fun deleteExpiredOrUsed(@Param("now") now: Instant)
}

@Repository
interface PasskeyCredentialRepository : JpaRepository<PasskeyCredential, UUID> {
    fun findByCredentialId(credentialId: String): PasskeyCredential?
    fun findAllByUserId(userId: UUID): List<PasskeyCredential>
    fun countByUserId(userId: UUID): Long
}

@Repository
interface WebAuthnChallengeRepository : JpaRepository<WebAuthnChallenge, UUID> {
    fun findByChallenge(challenge: String): WebAuthnChallenge?

    @Modifying
    @Query("delete from WebAuthnChallenge c where c.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant)
}

@Repository
interface TagRepository : JpaRepository<Tag, UUID> {
    fun findBySlug(slug: String): Tag?
    fun findAllBySlugIn(slugs: Collection<String>): List<Tag>
    fun findAllByIsDefaultTrue(): List<Tag>

    @Query("select t from Tag t order by t.isDefault desc, t.usageCount desc, t.slug asc")
    fun findAllOrdered(pageable: Pageable): Page<Tag>

    @Query(
        "select t from Tag t where lower(t.slug) like lower(concat('%', :q, '%')) " +
            "order by t.isDefault desc, t.usageCount desc"
    )
    fun searchBySlug(@Param("q") q: String, pageable: Pageable): List<Tag>
}

@Repository
interface AdvertRepository : JpaRepository<Advert, UUID>, JpaSpecificationExecutor<Advert> {
    fun countByCreatedAtAfter(instant: Instant): Long
    fun countByStatusAndDeletedAtIsNull(status: AdvertStatus): Long
    fun countByDeletedAtIsNull(): Long

    @Modifying
    @Query("update Advert a set a.viewCount = a.viewCount + 1 where a.id = :id")
    fun incrementViewCount(@Param("id") id: UUID)

    @Query("select coalesce(sum(a.viewCount), 0) from Advert a where a.deletedAt is null")
    fun totalViews(): Long

    fun findAllByAuthorIdAndDeletedAtIsNullOrderByCreatedAtDesc(authorId: UUID, pageable: Pageable): Page<Advert>

    fun findAllByModelIdAndDeletedAtIsNull(modelId: UUID): List<Advert>

    @Query(
        """
        select distinct a from Advert a left join a.models m
        where (m.id = :modelId or a.model.id = :modelId) and a.deletedAt is null
        """
    )
    fun findAllContainingModelId(@Param("modelId") modelId: UUID): List<Advert>

    @Query(
        """
        select a.type as type, count(a) as total from Advert a
        where a.deletedAt is null group by a.type
        """
    )
    fun countGroupedByType(): List<TypeCount>
}

interface TypeCount {
    val type: AdvertType
    val total: Long
}

@Repository
interface AdvertImageRepository : JpaRepository<AdvertImage, UUID>

@Repository
interface AdvertReactionRepository : JpaRepository<AdvertReaction, UUID> {
    fun findAllByAdvertIdAndDeletedAtIsNullOrderByCreatedAtAsc(advertId: UUID): List<AdvertReaction>
    fun countByAdvertIdAndDeletedAtIsNull(advertId: UUID): Long
    fun existsByAdvertIdAndAuthorIdAndDeletedAtIsNull(advertId: UUID, authorId: UUID): Boolean
}

@Repository
interface BidRepository : JpaRepository<Bid, UUID> {
    fun findAllByAdvertIdOrderByAmountCentsDesc(advertId: UUID): List<Bid>
    fun countByAdvertId(advertId: UUID): Long

    @Query("select max(b.amountCents) from Bid b where b.advert.id = :advertId and b.status <> 'WITHDRAWN'")
    fun highestBid(@Param("advertId") advertId: UUID): Int?

    fun findAllByBidderIdOrderByCreatedAtDesc(bidderId: UUID): List<Bid>
}

@Repository
interface AdvertViewRepository : JpaRepository<AdvertView, UUID> {
    @Modifying
    @Query(
        value = """
            insert into advert_views (id, advert_id, viewer_key, bucket, created_at)
            values (gen_random_uuid(), :advertId, :viewerKey, :bucket, now())
            on conflict (advert_id, viewer_key, bucket) do nothing
        """,
        nativeQuery = true
    )
    fun tryRecord(
        @Param("advertId") advertId: UUID,
        @Param("viewerKey") viewerKey: String,
        @Param("bucket") bucket: Long,
    ): Int
}

@Repository
interface Model3dRepository : JpaRepository<Model3d, UUID>, JpaSpecificationExecutor<Model3d> {
    fun findAllByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(ownerId: UUID): List<Model3d>
    fun countByDeletedAtIsNull(): Long

    @Query(
        """
        select m from Model3d m
        where m.deletedAt is null and m.visibility = 'PUBLIC'
          and (:categories is null or m.category in :categories)
          and (:q is null
               or lower(m.title) like lower(concat('%', cast(:q as string), '%'))
               or lower(coalesce(m.description, '')) like lower(concat('%', cast(:q as string), '%'))
               or (:queryCategories is not null and m.category in :queryCategories))
          and (:hidden is null or m.owner.id not in :hidden)
        """
    )
    fun searchPublic(
        @Param("q") q: String?,
        @Param("categories") categories: List<Category>?,
        @Param("queryCategories") queryCategories: List<Category>?,
        @Param("hidden") hidden: List<UUID>?,
        pageable: Pageable,
    ): Page<Model3d>
}

@Repository
interface ModelFileRepository : JpaRepository<ModelFile, UUID> {
    fun findAllByModelIdOrderBySortOrderAsc(modelId: UUID): List<ModelFile>
}

@Repository
interface ModelEntitlementRepository : JpaRepository<ModelEntitlement, UUID> {
    fun existsByModelIdAndUserId(modelId: UUID, userId: UUID): Boolean
    fun findAllByUserId(userId: UUID): List<ModelEntitlement>
}

@Repository
interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID, pageable: Pageable): Page<Notification>
    fun countByUserIdAndReadAtIsNull(userId: UUID): Long

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    fun markAllRead(@Param("userId") userId: UUID, @Param("now") now: Instant)
}

@Repository
interface PrinterModelRepository : JpaRepository<PrinterModel, UUID> {
    fun findAllByOrderByBrandAscNameAsc(): List<PrinterModel>
}

@Repository
interface ReportRepository : JpaRepository<Report, UUID> {
    fun findAllByStatusOrderByCreatedAtDesc(status: ReportStatus): List<Report>
    fun countByStatus(status: ReportStatus): Long
}

@Repository
interface AuditLogRepository : JpaRepository<AuditLogEntry, UUID> {
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<AuditLogEntry>
}

@Repository
interface ConversationParticipantRepository : JpaRepository<ConversationParticipant, UUID> {
    fun findByConversationIdAndUserId(conversationId: UUID, userId: UUID): ConversationParticipant?
    fun findAllByConversationId(conversationId: UUID): List<ConversationParticipant>
    fun existsByConversationIdAndUserId(conversationId: UUID, userId: UUID): Boolean
}

@Repository
interface ConversationRepository : JpaRepository<Conversation, UUID> {

    @Query(
        """
        select distinct c from Conversation c
        left join c.participants p
        where (
            (p.user.id = :userId and p.status <> nl.dichtbij3d.backend.domain.ParticipantStatus.DECLINED)
            or (c.participants is empty and (c.participantA.id = :userId or c.participantB.id = :userId))
        )
        order by c.lastMessageAt desc
        """
    )
    fun findAllForUser(@Param("userId") userId: UUID, pageable: Pageable): Page<Conversation>

    @Query(
        """
        select c from Conversation c
        where c.participantA.id = :a and c.participantB.id = :b
          and ((:advertId is null and c.advert is null) or c.advert.id = :advertId)
        """
    )
    fun findPair(
        @Param("a") a: UUID,
        @Param("b") b: UUID,
        @Param("advertId") advertId: UUID?,
    ): Conversation?
}

@Repository
interface MessageRepository : JpaRepository<Message, UUID> {

    fun findAllByConversationIdAndDeletedAtIsNullOrderByCreatedAtDesc(
        conversationId: UUID,
        pageable: Pageable,
    ): Page<Message>

    @Query(
        """
        select count(m) from Message m
        where m.conversation.id = :conversationId and m.deletedAt is null
          and m.sender.id <> :userId and m.createdAt > :since
        """
    )
    fun countUnread(
        @Param("conversationId") conversationId: UUID,
        @Param("userId") userId: UUID,
        @Param("since") since: Instant,
    ): Long

    /** Total unread messages across every conversation the user takes part in. */
    @Query(
        """
        select count(distinct m.id) from Message m
        join m.conversation c
        left join c.participants p
        where m.deletedAt is null and m.sender.id <> :userId
          and (
            (p.user.id = :userId and p.status = nl.dichtbij3d.backend.domain.ParticipantStatus.JOINED and m.createdAt > coalesce(p.readAt, :epoch))
            or (c.participants is empty and (
                (c.participantA.id = :userId and m.createdAt > coalesce(c.aReadAt, :epoch))
                or (c.participantB.id = :userId and m.createdAt > coalesce(c.bReadAt, :epoch))
            ))
          )
        """
    )
    fun countUnreadForUser(@Param("userId") userId: UUID, @Param("epoch") epoch: Instant): Long
}

interface ModelPurchaseRequestRepository : JpaRepository<ModelPurchaseRequest, UUID> {
    fun findByModelIdAndBuyerId(modelId: UUID, buyerId: UUID): ModelPurchaseRequest?

    fun findAllByModelIdOrderByCreatedAtDesc(modelId: UUID): List<ModelPurchaseRequest>
}

interface UserBlockRepository : JpaRepository<UserBlock, UUID> {
    fun findByBlockerIdAndBlockedId(blockerId: UUID, blockedId: UUID): UserBlock?

    fun findAllByBlockerIdOrderByCreatedAtDesc(blockerId: UUID): List<UserBlock>

    @Query("select b.blocked.id from UserBlock b where b.blocker.id = :userId")
    fun blockedIdsOf(@Param("userId") userId: UUID): List<UUID>

    /** Everyone this person cannot interact with, in either direction. */
    @Query(
        """
        select case when b.blocker.id = :userId then b.blocked.id else b.blocker.id end
        from UserBlock b
        where b.blocker.id = :userId or b.blocked.id = :userId
        """
    )
    fun entangledIdsOf(@Param("userId") userId: UUID): List<UUID>

    @Query("select count(b) > 0 from UserBlock b where (b.blocker.id = :a and b.blocked.id = :b) or (b.blocker.id = :b and b.blocked.id = :a)")
    fun eitherWayBlocked(@Param("a") a: UUID, @Param("b") b: UUID): Boolean
}

@Repository
interface PlatformBannerRepository : JpaRepository<PlatformBanner, UUID>

@Repository
interface PlatformAnnouncementRepository : JpaRepository<PlatformAnnouncement, UUID> {
    fun findAllByActiveTrueOrderByCreatedAtDesc(): List<PlatformAnnouncement>
    fun findAllByOrderByCreatedAtDesc(): List<PlatformAnnouncement>
}
