package nl.dichtbij3d.backend.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(nullable = false)
    var email: String = "",

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",

    @Column(name = "display_name", nullable = false, length = 60)
    var displayName: String = "",

    @Column(columnDefinition = "text")
    var bio: String? = null,

    @Column(name = "avatar_key")
    var avatarKey: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var gender: Gender = Gender.RATHER_NOT_SAY,

    @Column(name = "contact_email")
    var contactEmail: String? = null,

    @Column(name = "contact_phone", length = 40)
    var contactPhone: String? = null,

    var website: String? = null,

    var city: String? = null,

    @Column(length = 2, nullable = false)
    var country: String = "NL",

    @Column(nullable = false, length = 5)
    var locale: String = "nl",

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "disabled_reason", columnDefinition = "text")
    var disabledReason: String? = null,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    @Column(name = "totp_secret")
    var totpSecret: String? = null,

    @Column(name = "totp_enabled", nullable = false)
    var totpEnabled: Boolean = false,

    @Column(name = "email_mfa_enabled", nullable = false)
    var emailMfaEnabled: Boolean = false,

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
    @Column(name = "role", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    var roles: MutableSet<Role> = mutableSetOf(Role.CUSTOMER),

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_muted_notifications", joinColumns = [JoinColumn(name = "user_id")])
    @Column(name = "notification_type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    var mutedNotifications: MutableSet<NotificationType> = mutableSetOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }

    val isAdmin: Boolean get() = roles.contains(Role.ADMIN)
}

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID,

    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String,

    @Column(name = "family_id", nullable = false, columnDefinition = "uuid")
    var familyId: UUID,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "replaced_by", length = 64)
    var replacedBy: String? = null,

    @Column(name = "user_agent")
    var userAgent: String? = null,

    @Column(name = "ip_address", length = 64)
    var ipAddress: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "password_reset_tokens")
class PasswordResetToken(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID,

    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(nullable = false)
    var used: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "email_mfa_tokens")
class EmailMfaToken(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID,

    @Column(name = "code_hash", nullable = false, length = 64)
    var codeHash: String,

    @Column(nullable = false, length = 20)
    var purpose: String = "LOGIN",

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(nullable = false)
    var used: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "passkey_credentials")
class PasskeyCredential(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID,

    /** Base64URL encoded credential id. */
    @Column(name = "credential_id", nullable = false, length = 512)
    var credentialId: String,

    /** Base64 encoded, CBOR serialized `AttestedCredentialData`. */
    @Column(name = "attested_data", nullable = false, columnDefinition = "text")
    var attestedData: String,

    @Column(name = "sign_count", nullable = false)
    var signCount: Long = 0,

    @Column(nullable = false, length = 100)
    var label: String = "Passkey",

    var transports: String? = null,

    @Column(name = "backed_up", nullable = false)
    var backedUp: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,
)

@Entity
@Table(name = "webauthn_challenges")
class WebAuthnChallenge(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(nullable = false)
    var challenge: String,

    @Column(name = "user_id", columnDefinition = "uuid")
    var userId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var purpose: WebAuthnPurpose,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "tags")
class Tag(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(nullable = false, length = 60)
    var slug: String = "",

    @Column(name = "label_nl", nullable = false, length = 80)
    var labelNl: String = "",

    @Column(name = "label_en", nullable = false, length = 80)
    var labelEn: String = "",

    @Column(name = "label_de", nullable = false, length = 80)
    var labelDe: String = "",

    @Column(name = "label_fr", nullable = false, length = 80)
    var labelFr: String = "",

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @Column(name = "usage_count", nullable = false)
    var usageCount: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "models")
class Model3d(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    var owner: User,

    @Column(nullable = false, length = 140)
    var title: String,

    @Column(columnDefinition = "text")
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var license: ModelLicense = ModelLicense.CC_BY_NC,

    @Column(name = "price_cents", nullable = false)
    var priceCents: Int = 0,

    @Column(nullable = false, length = 3)
    var currency: String = "EUR",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var visibility: ModelVisibility = ModelVisibility.PUBLIC,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var category: Category = Category.OTHER,

    @Column(name = "thumbnail_key")
    var thumbnailKey: String? = null,

    @Column(name = "download_count", nullable = false)
    var downloadCount: Int = 0,

    @OneToMany(mappedBy = "model", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var files: MutableList<ModelFile> = mutableListOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    val isFree: Boolean get() = priceCents <= 0
}

@Entity
@Table(name = "model_files")
class ModelFile(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id")
    var model: Model3d,

    @Column(name = "object_key", nullable = false, columnDefinition = "text")
    var objectKey: String,

    @Column(name = "file_name", nullable = false)
    var fileName: String,

    @Column(name = "content_type", nullable = false, length = 120)
    var contentType: String = "application/octet-stream",

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "model_entitlements")
class ModelEntitlement(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "model_id", nullable = false, columnDefinition = "uuid")
    var modelId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var source: EntitlementSource = EntitlementSource.PURCHASE,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "adverts")
class Advert(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    var author: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var type: AdvertType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var category: Category = Category.OTHER,

    @Column(nullable = false, length = 140)
    var title: String,

    @Column(nullable = false, columnDefinition = "text")
    var description: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AdvertStatus = AdvertStatus.OPEN,

    @Column(name = "price_cents")
    var priceCents: Int? = null,

    @Column(nullable = false, length = 3)
    var currency: String = "EUR",

    @Column(name = "allow_bidding", nullable = false)
    var allowBidding: Boolean = false,

    @Column(name = "budget_min_cents")
    var budgetMinCents: Int? = null,

    @Column(name = "budget_max_cents")
    var budgetMaxCents: Int? = null,

    /** When true and the job is accepted, only author + accepted user can see it. */
    @Column(name = "hidden_after_accept", nullable = false)
    var hiddenAfterAccept: Boolean = false,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by")
    var acceptedBy: User? = null,

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    var model: Model3d? = null,

    var city: String? = null,

    @Column(name = "postal_code", length = 12)
    var postalCode: String? = null,

    var deadline: LocalDate? = null,

    @Column(name = "view_count", nullable = false)
    var viewCount: Int = 0,

    @Column(name = "reaction_count", nullable = false)
    var reactionCount: Int = 0,

    @Column(name = "bid_count", nullable = false)
    var bidCount: Int = 0,

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "advert_tags",
        joinColumns = [JoinColumn(name = "advert_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")]
    )
    var tags: MutableSet<Tag> = mutableSetOf(),

    @OneToMany(mappedBy = "advert", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var images: MutableList<AdvertImage> = mutableListOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    @Column(name = "deleted_reason", columnDefinition = "text")
    var deletedReason: String? = null,

    @Column(name = "deleted_by", columnDefinition = "uuid")
    var deletedBy: UUID? = null,
) {
    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "advert_images")
class AdvertImage(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advert_id")
    var advert: Advert,

    @Column(name = "object_key", nullable = false, columnDefinition = "text")
    var objectKey: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "advert_reactions")
class AdvertReaction(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advert_id")
    var advert: Advert,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    var author: User,

    @Column(nullable = false, columnDefinition = "text")
    var body: String,

    @Column(name = "is_application", nullable = false)
    var isApplication: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    @Column(name = "deleted_reason", columnDefinition = "text")
    var deletedReason: String? = null,
)

@Entity
@Table(name = "bids")
class Bid(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advert_id")
    var advert: Advert,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bidder_id")
    var bidder: User,

    @Column(name = "amount_cents", nullable = false)
    var amountCents: Int,

    @Column(columnDefinition = "text")
    var message: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: BidStatus = BidStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "advert_views")
class AdvertView(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "advert_id", nullable = false, columnDefinition = "uuid")
    var advertId: UUID,

    @Column(name = "viewer_key", nullable = false, length = 64)
    var viewerKey: String,

    @Column(nullable = false)
    var bucket: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "notifications")
class Notification(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var type: NotificationType,

    @Column(nullable = false, length = 180)
    var title: String,

    @Column(columnDefinition = "text")
    var body: String? = null,

    var link: String? = null,

    @Column(name = "read_at")
    var readAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "printer_models")
class PrinterModel(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(nullable = false, length = 60)
    var brand: String = "",

    @Column(nullable = false, length = 80)
    var name: String = "",

    @Column(nullable = false)
    var watts: Int = 0,

    @Column(name = "standby_watts", nullable = false)
    var standbyWatts: Int = 6,

    @Column(name = "purchase_price_cents", nullable = false)
    var purchasePriceCents: Int = 0,

    @Column(name = "expected_lifetime_hours", nullable = false)
    var expectedLifetimeHours: Int = 5000,

    @Column(nullable = false, length = 20)
    var technology: String = "FDM",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "reports")
class Report(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "advert_id", columnDefinition = "uuid")
    var advertId: UUID? = null,

    @Column(name = "user_id", columnDefinition = "uuid")
    var userId: UUID? = null,

    @Column(name = "reporter_id", nullable = false, columnDefinition = "uuid")
    var reporterId: UUID,

    @Column(nullable = false, columnDefinition = "text")
    var reason: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReportStatus = ReportStatus.OPEN,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
)

@Entity
@Table(name = "audit_log")
class AuditLogEntry(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "actor_id", columnDefinition = "uuid")
    var actorId: UUID? = null,

    @Column(nullable = false, length = 60)
    var action: String,

    @Column(name = "target_type", length = 40)
    var targetType: String? = null,

    @Column(name = "target_id", columnDefinition = "uuid")
    var targetId: UUID? = null,

    @Column(columnDefinition = "text")
    var detail: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "conversations")
class Conversation(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    /** Always the participant with the lowest UUID, so a pair maps to one row. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_a")
    var participantA: User,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_b")
    var participantB: User,

    /** The advert this thread is about, or null for a plain direct message. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advert_id")
    var advert: Advert? = null,

    @Column(name = "last_message_at", nullable = false)
    var lastMessageAt: Instant = Instant.now(),

    @Column(name = "last_message", columnDefinition = "text")
    var lastMessage: String? = null,

    @Column(name = "a_read_at")
    var aReadAt: Instant? = null,

    @Column(name = "b_read_at")
    var bReadAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    fun includes(userId: UUID) = participantA.id == userId || participantB.id == userId

    fun other(userId: UUID): User = if (participantA.id == userId) participantB else participantA

    fun readAtFor(userId: UUID): Instant? = if (participantA.id == userId) aReadAt else bReadAt

    fun markRead(userId: UUID, now: Instant) {
        if (participantA.id == userId) aReadAt = now else bReadAt = now
    }
}

@Entity
@Table(name = "messages")
class Message(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id")
    var conversation: Conversation,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id")
    var sender: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var kind: MessageKind = MessageKind.TEXT,

    @Column(nullable = false, columnDefinition = "text")
    var body: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
)

/** A buyer asking the owner of a paid model for access. Payment happens between the two of them. */
@Entity
@Table(name = "model_purchase_requests")
class ModelPurchaseRequest(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id")
    var model: Model3d,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id")
    var buyer: User,

    @Column(name = "conversation_id", columnDefinition = "uuid")
    var conversationId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: PurchaseRequestStatus = PurchaseRequestStatus.PENDING,

    @Column(columnDefinition = "text")
    var message: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
)


/** One person muting another: no chats, no adverts, no reactions in either direction. */
@Entity
@Table(name = "user_blocks")
class UserBlock(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocker_id")
    var blocker: User,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_id")
    var blocked: User,

    @Column(columnDefinition = "text")
    var reason: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "advert_lists")
class AdvertList(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: User,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "list", cascade = [CascadeType.ALL], orphanRemoval = true)
    var items: MutableList<AdvertListItem> = mutableListOf(),
)

@Entity
@Table(name = "advert_list_items")
class AdvertListItem(
    @Id @GeneratedValue @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "list_id")
    var list: AdvertList,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advert_id")
    var advert: Advert,

    @Column(name = "added_at", nullable = false)
    var addedAt: Instant = Instant.now(),
)
