package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.Notification
import nl.dichtbij3d.backend.domain.NotificationType
import nl.dichtbij3d.backend.dto.NotificationDto
import nl.dichtbij3d.backend.dto.PageResponse
import nl.dichtbij3d.backend.repo.NotificationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class NotificationService(
    private val repository: NotificationRepository,
    private val mapper: DtoMapper,
) {

    @Transactional
    fun push(userId: UUID, type: NotificationType, title: String, body: String? = null, link: String? = null) {
        repository.save(
            Notification(
                userId = userId,
                type = type,
                title = title.take(180),
                body = body,
                link = link,
            )
        )
    }

    fun list(userId: UUID, page: Int, size: Int): PageResponse<NotificationDto> =
        PageResponse.of(
            repository.findAllByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(mapper::notification)
        )

    fun unreadCount(userId: UUID): Long = repository.countByUserIdAndReadAtIsNull(userId)

    @Transactional
    fun markRead(userId: UUID, id: UUID) {
        repository.findById(id).ifPresent {
            if (it.userId == userId && it.readAt == null) {
                it.readAt = Instant.now()
                repository.save(it)
            }
        }
    }

    @Transactional
    fun markAllRead(userId: UUID) = repository.markAllRead(userId, Instant.now())

    @Transactional
    fun delete(userId: UUID, id: UUID) {
        repository.findById(id).ifPresent { if (it.userId == userId) repository.delete(it) }
    }
}
