package nl.dichtbij3d.backend.config

import nl.dichtbij3d.backend.domain.*
import nl.dichtbij3d.backend.repo.*
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@ConfigurationProperties(prefix = "dichtbij3d.demo-data")
data class DemoDataProperties(val enabled: Boolean = true)

@Configuration
class DataInitializer(
    private val userRepository: UserRepository,
    private val advertRepository: AdvertRepository,
    private val tagRepository: TagRepository,
    private val modelRepository: Model3dRepository,
    private val passwordEncoder: PasswordEncoder,
    private val adminProperties: AdminProperties,
    private val demoProperties: DemoDataProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun bootstrapRunner() = ApplicationRunner { bootstrap() }

    @Transactional
    fun bootstrap() {
        ensureAdmin()
        if (demoProperties.enabled && advertRepository.count() == 0L) seedDemoContent()
    }

    private fun ensureAdmin() {
        val email = adminProperties.email.lowercase()
        if (userRepository.existsByEmail(email)) return
        userRepository.save(
            User(
                email = email,
                passwordHash = passwordEncoder.encode(adminProperties.password),
                displayName = "Beheerder",
                emailVerified = true,
                city = "Amsterdam",
                bio = "Platformbeheerder van Dichtbij3D.",
                roles = mutableSetOf(Role.ADMIN, Role.CUSTOMER),
            )
        )
        log.info("Bootstrap admin created: {} (change the password after first login!)", email)
    }

    private fun seedDemoContent() {
        log.info("Seeding demo marketplace content")
        val password = passwordEncoder.encode("Demo12345!")

        fun user(
            email: String,
            name: String,
            city: String,
            bio: String,
            roles: MutableSet<Role>,
        ): User = userRepository.findByEmail(email) ?: userRepository.save(
            User(
                email = email,
                passwordHash = password,
                displayName = name,
                city = city,
                bio = bio,
                emailVerified = true,
                roles = roles,
            )
        )

        val sanne = user(
            "sanne@dichtbij3d.nl", "Sanne de Vries", "Utrecht",
            "Productontwerper. Ik zoek betaalbare prototypes voor mijn startup.",
            mutableSetOf(Role.CUSTOMER)
        )
        val bram = user(
            "bram@dichtbij3d.nl", "Bram Jansen", "Eindhoven",
            "Bambu Lab X1C en P1S. Ik print graag functionele onderdelen in PETG en ASA.",
            mutableSetOf(Role.PRINTER, Role.CUSTOMER)
        )
        val lieke = user(
            "lieke@dichtbij3d.nl", "Lieke Bakker", "Groningen",
            "3D-modelleur. Fusion 360 en Blender. Ik maak printklare modellen op maat.",
            mutableSetOf(Role.MODELLER, Role.CUSTOMER)
        )
        val tom = user(
            "tom@dichtbij3d.nl", "Tom Hendriks", "Rotterdam",
            "Hobbyist met een Prusa MK4S. Verkoop van eigen ontwerpen.",
            mutableSetOf(Role.PRINTER, Role.MODELLER, Role.CUSTOMER)
        )

        val tags = tagRepository.findAll().associateBy { it.slug }
        fun tagsOf(vararg slugs: String) = slugs.mapNotNull { tags[it] }.toMutableSet()

        val plantModel = modelRepository.save(
            Model3d(
                owner = tom,
                title = "Zelfwaterende plantenpot (parametrisch)",
                description = "Een plantenpot met waterreservoir. STL en 3MF inbegrepen, print zonder supports.",
                license = ModelLicense.CC_BY_NC,
                priceCents = 450,
                visibility = ModelVisibility.PUBLIC,
            )
        )
        modelRepository.save(
            Model3d(
                owner = lieke,
                title = "Kabelgoot clip set (6 maten)",
                description = "Gratis set clips om kabels netjes weg te werken achter je bureau.",
                license = ModelLicense.CC0,
                priceCents = 0,
                visibility = ModelVisibility.PUBLIC,
            )
        )

        fun advert(
            author: User,
            type: AdvertType,
            title: String,
            description: String,
            daysAgo: Long,
            views: Int,
            priceCents: Int? = null,
            allowBidding: Boolean = false,
            budgetMin: Int? = null,
            budgetMax: Int? = null,
            city: String? = null,
            tagSet: MutableSet<Tag> = mutableSetOf(),
            model: Model3d? = null,
            deadlineDays: Long? = null,
        ) {
            val created = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
            advertRepository.save(
                Advert(
                    author = author,
                    type = type,
                    title = title,
                    description = description,
                    priceCents = priceCents,
                    allowBidding = allowBidding,
                    budgetMinCents = budgetMin,
                    budgetMaxCents = budgetMax,
                    city = city ?: author.city,
                    deadline = deadlineDays?.let { LocalDate.now().plusDays(it) },
                    viewCount = views,
                    tags = tagSet,
                    model = model,
                    createdAt = created,
                    updatedAt = created,
                )
            )
        }

        advert(
            sanne, AdvertType.PRINT_REQUEST,
            "Prototype behuizing voor sensor gezocht",
            "Voor mijn startup heb ik een behuizing nodig voor een kleine sensor (60x40x25 mm). " +
                "Ik heb al een STEP-bestand. Graag geprint in PETG, zwart. Het gaat om 3 stuks voor een " +
                "eerste testronde. Grote B2B-partijen vragen hier belachelijke bedragen voor, dus ik zoek " +
                "liever iemand in de buurt.",
            daysAgo = 1, views = 128, budgetMin = 1500, budgetMax = 4000,
            tagSet = tagsOf("printer", "prototype", "spoed", "functioneel"), deadlineDays = 10,
        )
        advert(
            sanne, AdvertType.MODEL_REQUEST,
            "Modelleur gezocht voor ergonomische handgreep",
            "Ik zoek iemand die een ergonomische handgreep kan modelleren op basis van schetsen en " +
                "een paar foto's. Het model moet printbaar zijn zonder supports en parametrisch opgezet, " +
                "zodat we later de maat kunnen aanpassen.",
            daysAgo = 3, views = 74, budgetMin = 5000, budgetMax = 12000,
            tagSet = tagsOf("modeller", "prototype"), deadlineDays = 21,
        )
        advert(
            bram, AdvertType.PRINT_FOR_SALE,
            "Set van 4 geprinte planthangers (PETG)",
            "Zelf ontworpen en geprint in PETG, UV-bestendig. Geschikt voor binnen en buiten. " +
                "Kleur naar keuze: zwart, wit, terracotta. Ophalen in Eindhoven of verzenden voor 4,95.",
            daysAgo = 2, views = 210, priceCents = 1750,
            tagSet = tagsOf("fdm", "kleur", "functioneel"),
        )
        advert(
            tom, AdvertType.PRINT_FOR_SALE,
            "Grote drakensculptuur - 38 cm, bieden vanaf 25 euro",
            "Indrukwekkende drakensculptuur, geprint in meerdere delen en netjes afgewerkt en " +
                "geschilderd. Hoogte 38 cm. Bieden mag, hoogste bod krijgt 'm.",
            daysAgo = 5, views = 486, allowBidding = true, priceCents = 2500,
            tagSet = tagsOf("miniatuur", "cosplay", "groot-formaat"),
        )
        advert(
            tom, AdvertType.MODEL_FOR_SALE,
            "Zelfwaterende plantenpot - STL + 3MF",
            "Parametrische plantenpot met ingebouwd waterreservoir. Print zonder supports. " +
                "Bestanden voor 3 formaten inbegrepen.",
            daysAgo = 7, views = 342, priceCents = 450,
            tagSet = tagsOf("modeller", "functioneel"), model = plantModel,
        )
        advert(
            lieke, AdvertType.MODEL_FOR_SALE,
            "Kabelgoot clips - gratis download",
            "Set van zes clips in verschillende maten om kabels weg te werken. Gratis te downloaden, " +
                "een review wordt gewaardeerd.",
            daysAgo = 9, views = 901, priceCents = 0,
            tagSet = tagsOf("modeller", "functioneel", "fdm"),
        )
        advert(
            bram, AdvertType.PRINT_REQUEST,
            "Reserveonderdeel vaatwasser - wielsteun",
            "Het wieltje van het onderste rek van mijn vaatwasser is gebroken. Ik heb foto's en " +
                "afmetingen. Zoek iemand die dit kan natekenen en printen in iets stevigs.",
            daysAgo = 4, views = 155, budgetMin = 1000, budgetMax = 2500,
            tagSet = tagsOf("printer", "modeller", "reserveonderdeel"), deadlineDays = 14,
        )
        advert(
            lieke, AdvertType.PRINT_REQUEST,
            "Resin print gezocht voor miniatuur (32 mm schaal)",
            "Ik heb een eigen ontwerp voor een 32 mm miniatuur en zoek iemand met een resin printer " +
                "voor 10 exemplaren. Detail is belangrijker dan snelheid.",
            daysAgo = 6, views = 233, budgetMin = 2000, budgetMax = 5000,
            tagSet = tagsOf("printer", "resin", "miniatuur"),
        )

        log.info("Demo content seeded. Demo logins: sanne@dichtbij3d.nl / Demo12345!")
    }
}
