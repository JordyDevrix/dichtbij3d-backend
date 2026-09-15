package nl.dichtbij3d.backend.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import nl.dichtbij3d.backend.domain.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---------------------------------------------------------------- auth

data class RegisterRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank @field:Size(min = 10, max = 200) val password: String,
    @field:NotBlank @field:Size(min = 2, max = 60) val displayName: String,
    val roles: Set<Role>? = null,
    val locale: String? = null,
)

data class LoginRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val password: String,
    val totpCode: String? = null,
    val mfaCode: String? = null,
)

data class MfaVerifyRequest(
    @field:NotBlank val mfaToken: String,
    @field:NotBlank val code: String,
)

data class MfaSendEmailRequest(
    @field:NotBlank val mfaToken: String,
)

data class RefreshRequest(val refreshToken: String)

data class AuthResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
    val tokenType: String = "Bearer",
    val user: UserProfileDto? = null,
    val mfaRequired: Boolean = false,
    val mfaToken: String? = null,
    val mfaMethods: Set<String> = emptySet(),
    val maskedEmail: String? = null,
)

data class TotpSetupResponse(val secret: String, val otpauthUri: String)

data class TotpEnableRequest(@field:NotBlank val code: String)

data class EmailMfaEnableRequest(@field:NotBlank val code: String)

data class EmailMfaDisableRequest(
    val code: String? = null,
    val password: String? = null,
)

data class PasswordChangeRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:Size(min = 10, max = 200) val newPassword: String,
)

data class ForgotPasswordRequest(
    @field:Email @field:NotBlank val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank val token: String,
    @field:NotBlank @field:Size(min = 10, max = 200) val newPassword: String,
)

data class PasskeyRegisterFinishRequest(
    val credential: Map<String, Any?>,
    val label: String? = null,
)

data class PasskeyLoginFinishRequest(val credential: Map<String, Any?>)

data class PasskeyDto(
    val id: UUID,
    val label: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)

// ---------------------------------------------------------------- user

data class UserProfileDto(
    val id: UUID,
    val email: String? = null,
    val displayName: String,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val gender: Gender? = null,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
    val website: String? = null,
    val city: String? = null,
    val locale: String? = null,
    val roles: Set<Role> = emptySet(),
    val mutedNotifications: Set<NotificationType> = emptySet(),
    val enabled: Boolean = true,
    val totpEnabled: Boolean = false,
    val emailMfaEnabled: Boolean = false,
    val passkeyCount: Int = 0,
    val createdAt: Instant? = null,
)

data class PublicUserDto(
    val id: UUID,
    val displayName: String,
    val avatarUrl: String?,
    val roles: Set<Role>,
    val city: String?,
    val bio: String? = null,
    val memberSince: Instant? = null,
    /** Whether the person looking at this profile has blocked them. */
    val blocked: Boolean = false,
)

data class UpdateProfileRequest(
    @field:Size(min = 2, max = 60) val displayName: String? = null,
    @field:Size(max = 2000) val bio: String? = null,
    val gender: Gender? = null,
    @field:Email val contactEmail: String? = null,
    @field:Size(max = 40) val contactPhone: String? = null,
    @field:Size(max = 255) val website: String? = null,
    @field:Size(max = 120) val city: String? = null,
    val locale: String? = null,
    val roles: Set<Role>? = null,
    val mutedNotifications: Set<NotificationType>? = null,
)

// ---------------------------------------------------------------- tags

data class TagDto(
    val id: UUID,
    val slug: String,
    val label: String,
    val labels: Map<String, String>,
    val isDefault: Boolean,
    val usageCount: Int,
)

// ---------------------------------------------------------------- adverts

data class AdvertModelInput(
    val id: UUID? = null,
    val title: String? = null,
    val description: String? = null,
    val files: List<ModelFileRef> = emptyList(),
)

data class AdvertCreateRequest(
    val type: AdvertType,
    val category: Category = Category.OTHER,
    @field:NotBlank @field:Size(min = 4, max = 140) val title: String,
    @field:NotBlank @field:Size(min = 10, max = 8000) val description: String,
    @field:PositiveOrZero val priceCents: Int? = null,
    val allowBidding: Boolean = false,
    @field:PositiveOrZero val budgetMinCents: Int? = null,
    @field:PositiveOrZero val budgetMaxCents: Int? = null,
    val hiddenAfterAccept: Boolean = false,
    val city: String? = null,
    val postalCode: String? = null,
    val deadline: LocalDate? = null,
    val modelId: UUID? = null,
    val modelIds: List<UUID> = emptyList(),
    val models: List<AdvertModelInput> = emptyList(),
    val tags: List<String> = emptyList(),
    val imageKeys: List<String> = emptyList(),
)

data class AdvertUpdateRequest(
    val category: Category? = null,
    @field:Size(min = 4, max = 140) val title: String? = null,
    @field:Size(min = 10, max = 8000) val description: String? = null,
    val priceCents: Int? = null,
    val allowBidding: Boolean? = null,
    val budgetMinCents: Int? = null,
    val budgetMaxCents: Int? = null,
    val hiddenAfterAccept: Boolean? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val deadline: LocalDate? = null,
    val status: AdvertStatus? = null,
    val modelId: UUID? = null,
    val modelIds: List<UUID>? = null,
    val models: List<AdvertModelInput>? = null,
    val tags: List<String>? = null,
    val imageKeys: List<String>? = null,
)

data class AdvertSummaryDto(
    val id: UUID,
    val type: AdvertType,
    val category: Category,
    val title: String,
    val excerpt: String,
    val status: AdvertStatus,
    val priceCents: Int?,
    val currency: String,
    val allowBidding: Boolean,
    val highestBidCents: Int?,
    val budgetMinCents: Int?,
    val budgetMaxCents: Int?,
    val city: String?,
    val deadline: LocalDate?,
    val viewCount: Int,
    val reactionCount: Int,
    val bidCount: Int,
    val coverImageUrl: String?,
    val tags: List<TagDto>,
    val author: PublicUserDto,
    val createdAt: Instant,
)

data class AdvertDetailDto(
    val id: UUID,
    val type: AdvertType,
    val category: Category,
    val title: String,
    val description: String,
    val status: AdvertStatus,
    val priceCents: Int?,
    val currency: String,
    val allowBidding: Boolean,
    val budgetMinCents: Int?,
    val budgetMaxCents: Int?,
    val hiddenAfterAccept: Boolean,
    val city: String?,
    val postalCode: String?,
    val deadline: LocalDate?,
    val viewCount: Int,
    val imageUrls: List<String>,
    /** Storage keys behind [imageUrls], so the edit form can resubmit the images it keeps. */
    val imageKeys: List<String>,
    val tags: List<TagDto>,
    val author: PublicUserDto,
    val acceptedBy: PublicUserDto?,
    val acceptedAt: Instant?,
    val model: ModelSummaryDto?,
    val models: List<ModelDetailDto> = emptyList(),
    /** The attached model was removed by its owner, so there are no files to sell anymore. */
    val modelRemoved: Boolean = false,
    val reactions: List<ReactionDto>,
    val bids: List<BidDto>,
    val highestBidCents: Int?,
    val canEdit: Boolean,
    val canModerate: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ReactionCreateRequest(
    @field:NotBlank @field:Size(min = 1, max = 4000) val body: String,
    val isApplication: Boolean = false,
)

data class ReactionDto(
    val id: UUID,
    val body: String,
    val isApplication: Boolean,
    val author: PublicUserDto,
    val createdAt: Instant,
    val canDelete: Boolean,
)

data class PurchaseRequest(
    @field:Size(max = 1000) val message: String? = null,
)

/** Result of a buy-now intent: the buyer is handed the chat thread with the seller. */
data class PurchaseResponse(
    val conversationId: UUID,
    val message: String,
)

data class BidCreateRequest(
    @field:Positive val amountCents: Int,
    @field:Size(max = 1000) val message: String? = null,
)

data class BidDto(
    val id: UUID,
    val amountCents: Int,
    val message: String?,
    val status: BidStatus,
    val bidder: PublicUserDto,
    val createdAt: Instant,
)

data class ModerationRequest(
    @field:NotBlank @field:Size(min = 3, max = 500) val reason: String,
)

data class ViewPingRequest(val dwellMillis: Long = 0)

// ---------------------------------------------------------------- models

data class ModelCreateRequest(
    @field:NotBlank @field:Size(min = 3, max = 140) val title: String,
    @field:Size(max = 8000) val description: String? = null,
    val category: Category = Category.OTHER,
    val license: ModelLicense = ModelLicense.CC_BY_NC,
    @field:PositiveOrZero val priceCents: Int = 0,
    val visibility: ModelVisibility = ModelVisibility.PUBLIC,
    val thumbnailKey: String? = null,
    val files: List<ModelFileRef> = emptyList(),
    /**
     * Also put this model on the marketplace as a "model for sale" advert, so uploading
     * a model and offering it are a single step instead of two disconnected ones.
     */
    val listOnMarketplace: Boolean = false,
    @field:Size(max = 60) val city: String? = null,
    val tags: List<String> = emptyList(),
)

data class ModelFileRef(
    @field:NotBlank val objectKey: String,
    @field:NotBlank val fileName: String,
    val contentType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
)

data class ModelSummaryDto(
    val id: UUID,
    val title: String,
    val category: Category,
    val description: String?,
    val license: ModelLicense,
    val priceCents: Int,
    val currency: String,
    val visibility: ModelVisibility,
    val thumbnailUrl: String?,
    val fileCount: Int,
    val downloadCount: Int,
    val owner: PublicUserDto,
    val hasAccess: Boolean,
    val createdAt: Instant,
)

data class ModelDetailDto(
    val model: ModelSummaryDto,
    val files: List<ModelFileDto>,
    /** Pending/handled buyers, only filled in for the owner of a paid model. */
    val purchaseRequests: List<ModelPurchaseRequestDto> = emptyList(),
    /** Where the viewer's own request stands, if they asked for access. */
    val myPurchaseStatus: PurchaseRequestStatus? = null,
)

data class BlockedUserDto(
    val user: PublicUserDto,
    val reason: String?,
    val createdAt: Instant,
)

data class BlockRequest(
    @field:Size(max = 500) val reason: String? = null,
)

data class ModelPurchaseRequestDto(
    val id: UUID,
    val buyer: PublicUserDto,
    val status: PurchaseRequestStatus,
    val message: String?,
    val conversationId: UUID?,
    val createdAt: Instant,
)

data class ModelFileDto(
    val id: UUID,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val downloadUrl: String?,
)

// ---------------------------------------------------------------- notifications

data class NotificationDto(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String?,
    val link: String?,
    val readAt: Instant?,
    val createdAt: Instant,
)

// ---------------------------------------------------------------- chat

data class ConversationStartRequest(
    val userId: UUID? = null,
    val userIds: List<UUID> = emptyList(),
    val title: String? = null,
    val advertId: UUID? = null,
    @field:Size(max = 4000) val message: String? = null,
)

data class AddParticipantRequest(
    val userId: UUID,
)

data class MessageCreateRequest(
    @field:Size(max = 4000) val body: String? = null,
    val kind: MessageKind = MessageKind.TEXT,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val objectKey: String? = null,
    val contentType: String? = null,
)

data class ConversationParticipantDto(
    val user: PublicUserDto,
    val status: ParticipantStatus = ParticipantStatus.JOINED,
    val joinedAt: Instant,
)

data class ConversationDto(
    val id: UUID,
    val title: String? = null,
    val isGroup: Boolean = false,
    val peer: PublicUserDto,
    val participants: List<PublicUserDto> = emptyList(),
    val participantDetails: List<ConversationParticipantDto> = emptyList(),
    val myStatus: ParticipantStatus = ParticipantStatus.JOINED,
    val advert: ConversationAdvertDto?,
    val lastMessage: String?,
    val lastMessageAt: Instant,
    val unreadCount: Long,
    val createdAt: Instant,
)

data class ConversationAdvertDto(
    val id: UUID,
    val title: String,
    val type: AdvertType,
    val coverImageUrl: String?,
)

data class MessageDto(
    val id: UUID,
    val conversationId: UUID,
    val body: String,
    val kind: MessageKind,
    val senderId: UUID,
    val mine: Boolean,
    val sender: PublicUserDto? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val fileUrl: String? = null,
    val createdAt: Instant,
)

// ---------------------------------------------------------------- calculator

data class PrinterModelDto(
    val id: UUID,
    val brand: String,
    val name: String,
    val watts: Int,
    val standbyWatts: Int,
    val technology: String,
    val purchasePriceCents: Int,
    val expectedLifetimeHours: Int,
)

data class CostEstimateRequest(
    val printerModelId: UUID? = null,
    /** Override / manual entry when the printer is not in the catalogue. */
    @field:Min(1) @field:Max(5000) val wattsOverride: Int? = null,
    @field:Positive val printHours: Double,
    @field:PositiveOrZero val printMinutes: Int = 0,
    /** Price of a full spool, in cents. */
    @field:Positive val filamentPricePerKgCents: Int,
    /** Weight of the finished product in grams. */
    @field:Positive val productWeightGrams: Double,
    /** Extra grams lost to purge/supports/failures. */
    @field:PositiveOrZero val wasteGrams: Double = 0.0,
    @field:PositiveOrZero val electricityPricePerKwhCents: Int = 34,
    @field:PositiveOrZero val failureRatePercent: Double = 5.0,
    @field:PositiveOrZero val labourMinutes: Double = 15.0,
    @field:PositiveOrZero val labourRatePerHourCents: Int = 2000,
    @field:PositiveOrZero val marginPercent: Double = 20.0,
    val includeMachineDepreciation: Boolean = true,
    val includeVat: Boolean = false,
    @field:PositiveOrZero val vatPercent: Double = 21.0,
)

data class CostLine(val key: String, val amountCents: Int)

data class CostEstimateResponse(
    val printerLabel: String?,
    val watts: Int,
    val totalHours: Double,
    val energyKwh: Double,
    val filamentGrams: Double,
    val lines: List<CostLine>,
    val subtotalCents: Int,
    val marginCents: Int,
    val vatCents: Int,
    val totalCents: Int,
    val suggestedPriceCents: Int,
    val currency: String = "EUR",
)

// ---------------------------------------------------------------- admin

data class AdminMetricsDto(
    val totalUsers: Long,
    val newUsers7d: Long,
    val activeUsers: Long,
    val disabledUsers: Long,
    val totalAdverts: Long,
    val newAdverts7d: Long,
    val openAdverts: Long,
    val acceptedAdverts: Long,
    val totalModels: Long,
    val totalViews: Long,
    val openReports: Long,
    val advertsByType: Map<AdvertType, Long>,
    val signupsPerDay: List<DayCount>,
    val advertsPerDay: List<DayCount>,
)

data class DayCount(val day: String, val count: Long)

data class AdminUserDto(
    val id: UUID,
    val email: String,
    val displayName: String,
    val roles: Set<Role>,
    val enabled: Boolean,
    val disabledReason: String?,
    val totpEnabled: Boolean,
    val emailMfaEnabled: Boolean = false,
    val advertCount: Long,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

data class AdminUserUpdateRequest(
    val roles: Set<Role>? = null,
    val enabled: Boolean? = null,
    val reason: String? = null,
)

data class AuditLogDto(
    val id: UUID,
    val actor: String?,
    val action: String,
    val targetType: String?,
    val targetId: UUID?,
    val detail: String?,
    val createdAt: Instant,
)

data class ReportCreateRequest(
    val advertId: UUID? = null,
    val userId: UUID? = null,
    @field:NotBlank @field:Size(min = 3, max = 1000) val reason: String,
)

data class ReportDto(
    val id: UUID,
    val advertId: UUID?,
    val userId: UUID?,
    val reporter: String,
    val reason: String,
    val status: ReportStatus,
    val createdAt: Instant,
)

// ---------------------------------------------------------------- misc

data class UploadResponse(val objectKey: String, val url: String, val fileName: String, val sizeBytes: Long)

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <T> of(page: org.springframework.data.domain.Page<T>) = PageResponse(
            content = page.content,
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
        )
    }
}

data class MessageResponse(val message: String)

// ---------------------------------------------------------------- lists

data class AdvertListDto(
    val id: UUID,
    val name: String,
    val isDefault: Boolean,
    val advertIds: List<UUID>,
    val createdAt: Instant,
)

data class AdvertListCreateRequest(
    @field:NotBlank @field:Size(min = 1, max = 100) val name: String,
)

// ---------------------------------------------------------------- platform banner & announcements

data class PlatformBannerDto(
    val enabled: Boolean,
    val title: String,
    val subtitle: String?,
    val badgeText: String?,
    val buttonText: String?,
    val linkUrl: String?,
    val imageUrl: String?,
    val imageKey: String?,
    val updatedAt: Instant,
)

data class PlatformBannerUpdateRequest(
    val enabled: Boolean = false,
    val title: String = "",
    val subtitle: String? = null,
    val badgeText: String? = null,
    val buttonText: String? = null,
    val linkUrl: String? = null,
    val imageKey: String? = null,
    val imageUrl: String? = null,
)

data class PlatformAnnouncementDto(
    val id: UUID,
    val title: String,
    val content: String,
    val type: AnnouncementType,
    val eventDate: Instant?,
    val linkUrl: String?,
    val linkText: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PlatformAnnouncementRequest(
    @field:NotBlank @field:Size(min = 2, max = 200) val title: String,
    @field:NotBlank @field:Size(min = 2, max = 5000) val content: String,
    val type: AnnouncementType = AnnouncementType.INFO,
    val eventDate: Instant? = null,
    val linkUrl: String? = null,
    val linkText: String? = null,
    val active: Boolean = true,
)
