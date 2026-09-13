package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.UserBlock
import nl.dichtbij3d.backend.dto.BlockedUserDto
import nl.dichtbij3d.backend.dto.MessageResponse
import nl.dichtbij3d.backend.repo.UserBlockRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Blocking someone is the "leave me alone" button: they can no longer message you,
 * and you stop seeing each other's adverts and reactions. It is enforced in both
 * directions, so blocking cannot be used to keep reaching someone who blocked you.
 */
@Service
class BlockService(
    private val blocks: UserBlockRepository,
    private val users: UserRepository,
    private val mapper: DtoMapper,
) {

    @Transactional
    fun block(targetId: UUID, reason: String?, principal: AppPrincipal): MessageResponse {
        if (targetId == principal.id) throw ApiException.badRequest("You cannot block yourself")
        val target = users.findById(targetId).orElseThrow { ApiException.notFound("User") }
        if (target.deletedAt != null) throw ApiException.notFound("User")

        val existing = blocks.findByBlockerIdAndBlockedId(principal.id, targetId)
        if (existing != null) {
            existing.reason = reason?.trim()?.ifBlank { null } ?: existing.reason
            blocks.save(existing)
            return MessageResponse("Already blocked")
        }
        val me = users.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        blocks.save(UserBlock(blocker = me, blocked = target, reason = reason?.trim()?.ifBlank { null }))
        return MessageResponse("Blocked")
    }

    @Transactional
    fun unblock(targetId: UUID, principal: AppPrincipal): MessageResponse {
        blocks.findByBlockerIdAndBlockedId(principal.id, targetId)?.let(blocks::delete)
        return MessageResponse("Unblocked")
    }

    @Transactional(readOnly = true)
    fun list(principal: AppPrincipal): List<BlockedUserDto> =
        blocks.findAllByBlockerIdOrderByCreatedAtDesc(principal.id)
            .map { BlockedUserDto(mapper.publicUser(it.blocked), it.reason, it.createdAt) }

    // ------------------------------------------------------------- used by other services

    /** True when either side blocked the other. */
    fun isBlocked(one: UUID, two: UUID): Boolean = one != two && blocks.eitherWayBlocked(one, two)

    /** Everyone a viewer should not see content from, blocked by them or blocking them. */
    fun hiddenFor(viewer: AppPrincipal?): List<UUID> =
        viewer?.let { blocks.entangledIdsOf(it.id) } ?: emptyList()

    /** True when the viewer blocked this person (not the other way around). */
    fun hasBlocked(viewerId: UUID, targetId: UUID): Boolean =
        blocks.findByBlockerIdAndBlockedId(viewerId, targetId) != null
}
