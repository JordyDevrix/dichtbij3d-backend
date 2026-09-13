package nl.dichtbij3d.backend.domain

enum class Role {
    /** "Klant" / opdrachtgever: posts requests and buys. */
    CUSTOMER,

    /** Owns a 3D printer and prints for others. */
    PRINTER,

    /** Designs / models 3D files. */
    MODELLER,

    /** Platform moderator. */
    ADMIN;

    val authority: String get() = "ROLE_$name"
}

enum class Gender { MALE, FEMALE, OTHER, RATHER_NOT_SAY }

enum class AdvertType {
    /** "I need this printed" */
    PRINT_REQUEST,

    /** "I need this modelled" */
    MODEL_REQUEST,

    /** "I sell this digital model" */
    MODEL_FOR_SALE,

    /** "I sell this physical 3D print" */
    PRINT_FOR_SALE;

    val isRequest: Boolean get() = this == PRINT_REQUEST || this == MODEL_REQUEST
    val isSale: Boolean get() = !isRequest
}

enum class AdvertStatus { OPEN, ACCEPTED, COMPLETED, SOLD, CANCELLED, REMOVED }

enum class BidStatus { PENDING, ACCEPTED, REJECTED, WITHDRAWN }

enum class ModelVisibility { PUBLIC, UNLISTED, PRIVATE }

enum class ModelLicense { CC0, CC_BY, CC_BY_NC, CC_BY_SA, COMMERCIAL, ALL_RIGHTS_RESERVED }

enum class EntitlementSource { PURCHASE, SHARE, OWNER }

enum class NotificationType {
    ADVERT_REACTION,
    ADVERT_ACCEPTED,
    ADVERT_REMOVED,
    BID_PLACED,
    BID_ACCEPTED,
    BID_REJECTED,
    MODEL_PURCHASED,
    MODEL_SHARED,
    ACCOUNT_DISABLED,
    ACCOUNT_ENABLED,
    SYSTEM
}

enum class WebAuthnPurpose { REGISTRATION, AUTHENTICATION }

enum class ReportStatus { OPEN, RESOLVED, DISMISSED }
