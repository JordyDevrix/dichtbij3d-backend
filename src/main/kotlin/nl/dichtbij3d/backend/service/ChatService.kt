package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.Advert
import nl.dichtbij3d.backend.domain.Conversation
import nl.dichtbij3d.backend.domain.Message
import nl.dichtbij3d.backend.domain.MessageKind
import nl.dichtbij3d.backend.domain.NotificationType
import nl.dichtbij3d.backend.domain.User
import nl.dichtbij3d.backend.dto.ConversationAdvertDto
import nl.dichtbij3d.backend.dto.ConversationDto
import nl.dichtbij3d.backend.dto.MessageCreateRequest
import nl.dichtbij3d.backend.dto.MessageDto
import nl.dichtbij3d.backend.dto.PageResponse
import nl.dichtbij3d.backend.repo.AdvertRepository
import nl.dichtbij3d.backend.repo.ConversationRepository
import nl.dichtbij3d.backend.repo.MessageRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * One-to-one messaging. Threads are scoped to an advert when the conversation
 * starts from one (a bid, a reaction or an accepted job), so both sides always
 * know what the chat is about.
 */
@Service
class ChatService(
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val users: UserRepository,
    private val adverts: AdvertRepository,
    private val notifications: NotificationService,
    private val mapper: DtoMapper,
    private val storage: StorageService,
) {

    @Transactional(readOnly = true)
    fun list(principal: AppPrincipal, page: Int, size: Int): PageResponse<ConversationDto> =
        PageResponse.of(
            conversations.findAllForUser(principal.id, PageRequest.of(page, size.coerceIn(1, 100)))
                .map { toDto(it, principal.id) }
        )

    @Transactional(readOnly = true)
    fun detail(id: UUID, principal: AppPrincipal): ConversationDto =
        toDto(require(id, principal), principal.id)

    @Transactional(readOnly = true)
    fun unreadCount(principal: AppPrincipal): Long = messages.countUnreadForUser(principal.id, Instant.EPOCH)

    /** Newest first; the client reverses for display and pages backwards in time. */
    @Transactional(readOnly = true)
    fun messages(id: UUID, principal: AppPrincipal, page: Int, size: Int): PageResponse<MessageDto> {
        require(id, principal)
        return PageResponse.of(
            messages.findAllByConversationIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                id, PageRequest.of(page, size.coerceIn(1, 100))
            ).map { toDto(it, principal.id) }
        )
    }

    @Transactional
    fun start(principal: AppPrincipal, peerId: UUID, advertId: UUID?, firstMessage: String?): ConversationDto {
        if (peerId == principal.id) throw ApiException.badRequest("You cannot start a chat with yourself")
        val me = users.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        val peer = users.findById(peerId).orElseThrow { ApiException.notFound("User") }
        if (peer.deletedAt != null || !peer.enabled) throw ApiException.badRequest("This user can no longer be reached")

        // The advert is only a label on the thread, so an unknown or removed one
        // simply degrades to a plain direct chat instead of failing.
        val advert = advertId?.let { adverts.findById(it).orElse(null) }?.takeIf { it.deletedAt == null }
        val conversation = findOrCreate(me, peer, advert)
        firstMessage?.trim()?.takeIf { it.isNotEmpty() }?.let {
            post(conversation, me, it, MessageKind.TEXT)
        }
        return toDto(conversation, principal.id)
    }

    @Transactional
    fun send(id: UUID, principal: AppPrincipal, request: MessageCreateRequest): MessageDto {
        val conversation = require(id, principal)
        val me = users.findById(principal.id).orElseThrow { ApiException.notFound("User") }
        val peer = conversation.other(principal.id)
        if (peer.deletedAt != null || !peer.enabled) throw ApiException.badRequest("This user can no longer be reached")
        val message = post(conversation, me, request.body.trim(), MessageKind.TEXT)
        return toDto(message, principal.id)
    }

    @Transactional
    fun markRead(id: UUID, principal: AppPrincipal) {
        val conversation = require(id, principal)
        conversation.markRead(principal.id, Instant.now())
        conversations.save(conversation)
    }

    // ------------------------------------------------------------- internal API for other services

    /**
     * Opens the thread between two people for an advert and drops in a system line
     * explaining why it exists. Used when a job is accepted or a bid is placed, so
     * both sides immediately have a way to reach each other.
     */
    @Transactional
    fun openForAdvert(advert: Advert, peer: User, opener: User, systemLine: String): Conversation {
        val conversation = findOrCreate(opener, peer, advert)
        post(conversation, opener, systemLine, MessageKind.SYSTEM, notify = false)
        return conversation
    }

    // ------------------------------------------------------------- helpers

    private fun require(id: UUID, principal: AppPrincipal): Conversation {
        val conversation = conversations.findById(id).orElseThrow { ApiException.notFound("Conversation") }
        // Private means private: not even admins can read other people's chats.
        if (!conversation.includes(principal.id)) throw ApiException.notFound("Conversation")
        return conversation
    }

    private fun findOrCreate(one: User, two: User, advert: Advert?): Conversation {
        val first = one.id!!.toString() < two.id!!.toString()
        val a = if (first) one else two
        val b = if (first) two else one
        conversations.findPair(a.id!!, b.id!!, advert?.id)?.let { return it }
        return conversations.save(
            Conversation(participantA = a, participantB = b, advert = advert, lastMessageAt = Instant.now())
        )
    }

    private fun post(
        conversation: Conversation,
        sender: User,
        body: String,
        kind: MessageKind,
        notify: Boolean = true,
    ): Message {
        val peer = conversation.other(sender.id!!)
        val hadUnread = messages.countUnread(
            conversation.id!!,
            peer.id!!,
            conversation.readAtFor(peer.id!!) ?: Instant.EPOCH,
        ) > 0

        val message = messages.save(
            Message(conversation = conversation, sender = sender, kind = kind, body = body.take(4000))
        )
        conversation.lastMessage = body.take(180)
        conversation.lastMessageAt = message.createdAt
        // Sending is also reading: the thread should not look unread to its own author.
        conversation.markRead(sender.id!!, message.createdAt)
        conversations.save(conversation)

        // Only nudge on the first unread message of a thread, so a burst of replies
        // does not turn into a wall of notifications.
        if (notify && !hadUnread) {
            notifications.push(
                userId = peer.id!!,
                type = NotificationType.MESSAGE_RECEIVED,
                title = "New message from ${sender.displayName}",
                body = body.take(120),
                link = "/messages/${conversation.id}",
            )
        }
        return message
    }

    private fun toDto(conversation: Conversation, viewerId: UUID): ConversationDto {
        val peer = conversation.other(viewerId)
        return ConversationDto(
            id = conversation.id!!,
            peer = mapper.publicUser(peer),
            advert = conversation.advert?.takeIf { it.deletedAt == null }?.let {
                ConversationAdvertDto(
                    id = it.id!!,
                    title = it.title,
                    type = it.type,
                    coverImageUrl = storage.publicUrl(it.images.minByOrNull { image -> image.sortOrder }?.objectKey),
                )
            },
            lastMessage = conversation.lastMessage,
            lastMessageAt = conversation.lastMessageAt,
            unreadCount = messages.countUnread(
                conversation.id!!,
                viewerId,
                conversation.readAtFor(viewerId) ?: Instant.EPOCH,
            ),
            createdAt = conversation.createdAt,
        )
    }

    private fun toDto(message: Message, viewerId: UUID) = MessageDto(
        id = message.id!!,
        conversationId = message.conversation.id!!,
        body = message.body,
        kind = message.kind,
        senderId = message.sender.id!!,
        mine = message.sender.id == viewerId,
        createdAt = message.createdAt,
    )
}
