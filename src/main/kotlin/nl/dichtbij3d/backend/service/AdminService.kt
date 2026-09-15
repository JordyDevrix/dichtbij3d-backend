package nl.dichtbij3d.backend.service

import jakarta.persistence.EntityManager
import nl.dichtbij3d.backend.domain.*
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.repo.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class AdminService(
    private val userRepository: UserRepository,
    private val advertRepository: AdvertRepository,
    private val modelRepository: Model3dRepository,
    private val reportRepository: ReportRepository,
    private val auditLogRepository: AuditLogRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val notifications: NotificationService,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun metrics(): AdminMetricsDto {
        val weekAgo = Instant.now().minus(7, ChronoUnit.DAYS)
        val allUsers = userRepository.count()
        val disabled = userRepository.countByEnabledFalse()
        return AdminMetricsDto(
            totalUsers = allUsers,
            newUsers7d = userRepository.countByCreatedAtAfter(weekAgo),
            activeUsers = allUsers - disabled,
            disabledUsers = disabled,
            totalAdverts = advertRepository.countByDeletedAtIsNull(),
            newAdverts7d = advertRepository.countByCreatedAtAfter(weekAgo),
            openAdverts = advertRepository.countByStatusAndDeletedAtIsNull(AdvertStatus.OPEN),
            acceptedAdverts = advertRepository.countByStatusAndDeletedAtIsNull(AdvertStatus.ACCEPTED),
            totalModels = modelRepository.countByDeletedAtIsNull(),
            totalViews = advertRepository.totalViews(),
            openReports = reportRepository.countByStatus(ReportStatus.OPEN),
            advertsByType = advertRepository.countGroupedByType().associate { it.type to it.total },
            signupsPerDay = dailySeries("users", null),
            advertsPerDay = dailySeries("adverts", "deleted_at is null"),
        )
    }

    private fun dailySeries(table: String, extraWhere: String?): List<DayCount> {
        return try {
            val where = extraWhere?.let { " and t.$it" } ?: ""
            val sql = """
                select to_char(d.day, 'YYYY-MM-DD') as day, count(t.id) as total
                from generate_series(date_trunc('day', now()) - interval '29 days', date_trunc('day', now()), interval '1 day') as d(day)
                left join $table t on date_trunc('day', t.created_at) = d.day$where
                group by d.day order by d.day
            """.trimIndent()
            val rows = entityManager.createNativeQuery(sql).resultList
            rows.mapNotNull { row ->
                when (row) {
                    is Array<*> -> {
                        val day = row[0]?.toString() ?: return@mapNotNull null
                        val count = (row[1] as? Number)?.toLong() ?: 0L
                        DayCount(day, count)
                    }
                    is List<*> -> {
                        val day = row[0]?.toString() ?: return@mapNotNull null
                        val count = (row[1] as? Number)?.toLong() ?: 0L
                        DayCount(day, count)
                    }
                    else -> null
                }
            }
        } catch (ex: Exception) {
            log.error("Failed to calculate daily series for {}: {}", table, ex.message)
            emptyList()
        }
    }

    @Transactional(readOnly = true)
    fun users(query: String?, page: Int, size: Int): PageResponse<AdminUserDto> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "createdAt"))
        return PageResponse.of(
            userRepository.search(query?.takeIf { it.isNotBlank() }, pageable).map { user ->
                AdminUserDto(
                    id = user.id!!,
                    email = user.email,
                    displayName = user.displayName,
                    roles = user.roles.toSet(),
                    enabled = user.enabled,
                    disabledReason = user.disabledReason,
                    totpEnabled = user.totpEnabled,
                    emailMfaEnabled = user.emailMfaEnabled,
                    advertCount = advertRepository.findAllByAuthorIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        user.id!!, PageRequest.of(0, 1)
                    ).totalElements,
                    lastLoginAt = user.lastLoginAt,
                    createdAt = user.createdAt,
                    deletedAt = user.deletedAt,
                )
            }
        )
    }

    @Transactional
    fun updateUser(id: UUID, request: AdminUserUpdateRequest, actor: AppPrincipal): MessageResponse {
        val user = userRepository.findById(id).orElseThrow { ApiException.notFound("User") }
        if (user.id == actor.id && request.enabled == false) {
            throw ApiException.badRequest("You cannot disable your own account")
        }
        request.roles?.let { roles ->
            if (user.id == actor.id && !roles.contains(Role.ADMIN)) {
                throw ApiException.badRequest("You cannot remove your own admin role")
            }
            user.roles = roles.toMutableSet().ifEmpty { mutableSetOf(Role.CUSTOMER) }
        }
        request.enabled?.let { enabled ->
            if (user.enabled != enabled) {
                user.enabled = enabled
                user.disabledReason = if (enabled) null else request.reason
                if (!enabled) refreshTokenRepository.revokeAllForUser(id, Instant.now())
                notifications.push(
                    userId = id,
                    type = if (enabled) NotificationType.ACCOUNT_ENABLED else NotificationType.ACCOUNT_DISABLED,
                    title = if (enabled) "Your account has been re-activated" else "Your account has been disabled",
                    body = request.reason,
                )
            }
        }
        userRepository.save(user)
        audit(actor, "USER_UPDATED", "USER", id, request.reason)
        return MessageResponse("User updated")
    }

    @Transactional
    fun deleteUser(id: UUID, actor: AppPrincipal, reason: String?): MessageResponse {
        if (id == actor.id) throw ApiException.badRequest("You cannot delete your own account")
        val user = userRepository.findById(id).orElseThrow { ApiException.notFound("User") }
        user.deletedAt = Instant.now()
        user.enabled = false
        user.disabledReason = reason
        // Scramble the email so the address can be reused and PII is gone.
        user.email = "deleted-${user.id}@dichtbij3d.invalid"
        user.displayName = "Verwijderde gebruiker"
        user.bio = null
        user.contactEmail = null
        user.contactPhone = null
        userRepository.save(user)
        refreshTokenRepository.revokeAllForUser(id, Instant.now())
        audit(actor, "USER_DELETED", "USER", id, reason)
        return MessageResponse("User deleted")
    }

    @Transactional(readOnly = true)
    fun reports(): List<ReportDto> = reportRepository.findAllByStatusOrderByCreatedAtDesc(ReportStatus.OPEN)
        .map { report ->
            ReportDto(
                id = report.id!!,
                advertId = report.advertId,
                userId = report.userId,
                reporter = userRepository.findById(report.reporterId).map { it.displayName }.orElse("?"),
                reason = report.reason,
                status = report.status,
                createdAt = report.createdAt,
            )
        }

    @Transactional
    fun createReport(request: ReportCreateRequest, reporter: AppPrincipal): MessageResponse {
        reportRepository.save(
            Report(
                advertId = request.advertId,
                userId = request.userId,
                reporterId = reporter.id,
                reason = request.reason.trim(),
            )
        )
        return MessageResponse("Thanks, our moderators will look into it")
    }

    @Transactional
    fun resolveReport(id: UUID, dismiss: Boolean, actor: AppPrincipal): MessageResponse {
        val report = reportRepository.findById(id).orElseThrow { ApiException.notFound("Report") }
        report.status = if (dismiss) ReportStatus.DISMISSED else ReportStatus.RESOLVED
        report.resolvedAt = Instant.now()
        reportRepository.save(report)
        audit(actor, "REPORT_${report.status}", "REPORT", id, null)
        return MessageResponse("Report handled")
    }

    @Transactional(readOnly = true)
    fun auditLog(page: Int, size: Int): PageResponse<AuditLogDto> = PageResponse.of(
        auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size.coerceIn(1, 100)))
            .map { entry ->
                AuditLogDto(
                    id = entry.id!!,
                    actor = entry.actorId?.let { id -> userRepository.findById(id).map { it.displayName }.orElse(null) },
                    action = entry.action,
                    targetType = entry.targetType,
                    targetId = entry.targetId,
                    detail = entry.detail,
                    createdAt = entry.createdAt,
                )
            }
    )

    @Transactional(readOnly = true)
    fun adverts(page: Int, size: Int): PageResponse<AdminAdvertDto> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "createdAt"))
        return PageResponse.of(
            advertRepository.findAll(pageable).map {
                AdminAdvertDto(
                    id = it.id!!,
                    title = it.title,
                    type = it.type,
                    status = it.status,
                    author = it.author.displayName,
                    authorId = it.author.id!!,
                    viewCount = it.viewCount,
                    createdAt = it.createdAt,
                    deletedAt = it.deletedAt,
                    deletedReason = it.deletedReason,
                )
            }
        )
    }

    @Transactional
    fun restoreAdvert(id: UUID, actor: AppPrincipal): MessageResponse {
        val advert = advertRepository.findById(id).orElseThrow { ApiException.notFound("Advert") }
        advert.deletedAt = null
        advert.deletedReason = null
        advert.deletedBy = null
        advert.status = AdvertStatus.OPEN
        advertRepository.save(advert)
        audit(actor, "ADVERT_RESTORED", "ADVERT", id, null)
        return MessageResponse("Advert restored")
    }

    private fun audit(actor: AppPrincipal, action: String, targetType: String, targetId: UUID, detail: String?) {
        auditLogRepository.save(
            AuditLogEntry(
                actorId = actor.id,
                action = action,
                targetType = targetType,
                targetId = targetId,
                detail = detail,
            )
        )
    }
}

data class AdminAdvertDto(
    val id: UUID,
    val title: String,
    val type: AdvertType,
    val status: AdvertStatus,
    val author: String,
    val authorId: UUID,
    val viewCount: Int,
    val createdAt: Instant,
    val deletedAt: Instant?,
    val deletedReason: String?,
)
