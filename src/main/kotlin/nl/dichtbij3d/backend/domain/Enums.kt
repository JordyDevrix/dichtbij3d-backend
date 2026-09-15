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

/**
 * Fixed platform taxonomy. Categories are deliberately not user-made: they are
 * what the marketplace filters and searches on, for prints as well as models.
 */
enum class Category {
    HOME_LIVING,
    HOMELAB_IT,
    ELECTRONICS_CASES,
    TOOLS_WORKSHOP,
    SPARE_PARTS_REPAIR,
    AUTOMOTIVE,
    RC_DRONES,
    TOYS_GAMES,
    TABLETOP_MINIATURES,
    COSPLAY_PROPS,
    ART_DECOR,
    JEWELRY_FASHION,
    KITCHEN_DINING,
    GARDEN_OUTDOOR,
    SPORTS_OUTDOOR,
    PETS,
    EDUCATION_SCIENCE,
    MEDICAL_ASSISTIVE,
    OTHER;

    /** Words that should lead a free-text search to this category, in all four languages. */
    val keywords: List<String>
        get() = when (this) {
            HOME_LIVING -> listOf("home", "living", "interieur", "huis", "wonen", "möbel", "einrichtung", "maison", "vaas", "lamp", "opberg", "organizer")
            HOMELAB_IT -> listOf("homelab", "home lab", "server", "rack", "19 inch", "raspberry", "pi", "nas", "netwerk", "network", "it", "pc", "computer", "kabel", "cable")
            ELECTRONICS_CASES -> listOf("elektronica", "electronics", "electronique", "elektronik", "behuizing", "case", "gehäuse", "boitier", "arduino", "esp32", "pcb", "sensor")
            TOOLS_WORKSHOP -> listOf("gereedschap", "tool", "tools", "werkplaats", "workshop", "werkstatt", "atelier", "mal", "jig")
            SPARE_PARTS_REPAIR -> listOf("onderdeel", "onderdelen", "reserveonderdeel", "spare", "part", "parts", "ersatzteil", "pièce", "reparatie", "repair", "reparatur")
            AUTOMOTIVE -> listOf("auto", "car", "voertuig", "vehicle", "fiets", "bike", "bicycle", "camper", "caravan", "motor", "moto")
            RC_DRONES -> listOf("rc", "drone", "drones", "fpv", "modelbouw", "model building", "quad", "vliegtuig", "boot")
            TOYS_GAMES -> listOf("speelgoed", "toy", "toys", "spielzeug", "jouet", "spel", "game", "games", "puzzel", "puzzle", "lego")
            TABLETOP_MINIATURES -> listOf("tabletop", "miniatuur", "miniaturen", "miniature", "dnd", "d&d", "warhammer", "dobbelsteen", "dice", "terrain", "figur")
            COSPLAY_PROPS -> listOf("cosplay", "prop", "props", "kostuum", "costume", "helm", "helmet", "masker", "mask", "zwaard", "sword")
            ART_DECOR -> listOf("kunst", "art", "decoratie", "decor", "deko", "décoration", "sculptuur", "sculpture", "beeld", "ornament")
            JEWELRY_FASHION -> listOf("sieraad", "sieraden", "jewelry", "jewellery", "schmuck", "bijoux", "mode", "fashion", "ring", "armband", "bracelet", "ketting")
            KITCHEN_DINING -> listOf("keuken", "kitchen", "küche", "cuisine", "servies", "bestek", "mok", "mug", "koken", "cooking", "eten", "food")
            GARDEN_OUTDOOR -> listOf("tuin", "garden", "garten", "jardin", "plant", "planten", "pot", "kas", "buiten")
            SPORTS_OUTDOOR -> listOf("sport", "sports", "fitness", "camping", "outdoor", "wandel", "hiking", "klim", "climbing")
            PETS -> listOf("dier", "dieren", "huisdier", "pet", "pets", "haustier", "animal", "hond", "dog", "kat", "cat", "aquarium", "vogel")
            EDUCATION_SCIENCE -> listOf("onderwijs", "education", "school", "bildung", "éducation", "wetenschap", "science", "lab", "les", "leren", "study")
            MEDICAL_ASSISTIVE -> listOf("zorg", "medisch", "medical", "hulpmiddel", "assistive", "prothese", "prosthetic", "orthese", "gezondheid", "health")
            OTHER -> listOf("overig", "other", "sonstiges", "autre", "divers")
        }

    companion object {
        /** Categories whose name or keywords match a free-text query. */
        fun matching(query: String): List<Category> {
            val q = query.trim().lowercase()
            if (q.length < 2) return emptyList()
            return entries.filter { category ->
                category.name.lowercase().replace('_', ' ').contains(q) ||
                    category.keywords.any { it == q || (q.length >= 3 && it.contains(q)) }
            }
        }
    }
}

enum class PurchaseRequestStatus { PENDING, GRANTED, DECLINED }

enum class EntitlementSource { PURCHASE, SHARE, OWNER }

enum class NotificationType {
    ADVERT_REACTION,
    ADVERT_ACCEPTED,
    ADVERT_REMOVED,
    BID_PLACED,
    ADVERT_PURCHASE_REQUEST,
    BID_ACCEPTED,
    BID_REJECTED,
    MODEL_PURCHASED,
    MODEL_PURCHASE_REQUEST,
    MODEL_ACCESS_GRANTED,
    MODEL_PURCHASE_DECLINED,
    MODEL_SHARED,
    MESSAGE_RECEIVED,
    ACCOUNT_DISABLED,
    ACCOUNT_ENABLED,
    SYSTEM
}

/** A normal chat message, an automatic line the platform posts itself, or a file attachment. */
enum class MessageKind { TEXT, SYSTEM, FILE }

enum class WebAuthnPurpose { REGISTRATION, AUTHENTICATION }

enum class ReportStatus { OPEN, RESOLVED, DISMISSED }

/** Status of a user in a conversation (invitation flow for collaboration). */
enum class ParticipantStatus { INVITED, JOINED, DECLINED }

enum class AnnouncementType { INFO, EVENT, UPDATE, WARNING }

