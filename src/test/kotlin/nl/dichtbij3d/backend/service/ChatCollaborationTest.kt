package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.*
import nl.dichtbij3d.backend.repo.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.web.ApiException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.time.Instant
import java.util.*

class ChatCollaborationTest {

    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var userRepo: UserRepository
    private lateinit var advertRepo: AdvertRepository
    private lateinit var notificationService: NotificationService
    private lateinit var blockService: BlockService
    private lateinit var mapper: DtoMapper
    private lateinit var storage: StorageService
    private lateinit var modelRepo: Model3dRepository
    private lateinit var passkeyRepo: PasskeyCredentialRepository
    private lateinit var chatService: ChatService

    private val meId = UUID.randomUUID()
    private val designerId = UUID.randomUUID()
    private val printerId = UUID.randomUUID()

    private lateinit var me: User
    private lateinit var designer: User
    private lateinit var printer: User
    private lateinit var principal: AppPrincipal

    @BeforeEach
    fun setup() {
        conversationRepo = mock(ConversationRepository::class.java)
        messageRepo = mock(MessageRepository::class.java)
        userRepo = mock(UserRepository::class.java)
        advertRepo = mock(AdvertRepository::class.java)
        notificationService = mock(NotificationService::class.java)
        blockService = mock(BlockService::class.java)
        storage = mock(StorageService::class.java)
        modelRepo = mock(Model3dRepository::class.java)
        passkeyRepo = mock(PasskeyCredentialRepository::class.java)
        mapper = DtoMapper(storage, passkeyRepo)

        chatService = ChatService(
            conversationRepo,
            messageRepo,
            userRepo,
            advertRepo,
            notificationService,
            blockService,
            mapper,
            storage,
            modelRepo,
        )

        me = User(id = meId, email = "me@example.com", displayName = "Customer Me")
        designer = User(id = designerId, email = "designer@example.com", displayName = "Alice Designer", roles = mutableSetOf(Role.MODELLER))
        printer = User(id = printerId, email = "printer@example.com", displayName = "Bob Printer", roles = mutableSetOf(Role.PRINTER))

        principal = AppPrincipal(id = meId, email = me.email, displayName = me.displayName, roles = setOf(Role.CUSTOMER))

        `when`(userRepo.findById(meId)).thenReturn(Optional.of(me))
        `when`(userRepo.findById(designerId)).thenReturn(Optional.of(designer))
        `when`(userRepo.findById(printerId)).thenReturn(Optional.of(printer))

        `when`(conversationRepo.save(any(Conversation::class.java))).thenAnswer { invocation ->
            val c = invocation.getArgument(0) as Conversation
            if (c.id == null) c.id = UUID.randomUUID()
            c
        }

        `when`(messageRepo.save(any(Message::class.java))).thenAnswer { invocation ->
            val m = invocation.getArgument(0) as Message
            if (m.id == null) m.id = UUID.randomUUID()
            m.createdAt = Instant.now()
            m
        }
    }

    @Test
    fun `start multi-user collaboration chat invites targets`() {
        val dto = chatService.start(
            principal = principal,
            peerIds = listOf(designerId, printerId),
            title = "Drone Frame Collaboration",
            firstMessage = "Hello team, let's collaborate on this print!",
        )

        assertNotNull(dto.id)
        assertEquals("Drone Frame Collaboration", dto.title)
        assertTrue(dto.isGroup)
        assertEquals(3, dto.participants.size)
        assertEquals(ParticipantStatus.JOINED, dto.myStatus)

        val designerDetail = dto.participantDetails.find { it.user.id == designerId }
        val printerDetail = dto.participantDetails.find { it.user.id == printerId }
        assertNotNull(designerDetail)
        assertNotNull(printerDetail)
        assertEquals(ParticipantStatus.INVITED, designerDetail!!.status)
        assertEquals(ParticipantStatus.INVITED, printerDetail!!.status)

        // Verify push notifications sent to invited users
        val notifUserCaptor = ArgumentCaptor.forClass(UUID::class.java)
        verify(notificationService, atLeastOnce()).push(
            capture(notifUserCaptor),
            anyNonNull(),
            anyNonNull(),
            any(),
            any(),
        )
        assertTrue(notifUserCaptor.allValues.contains(designerId))
        assertTrue(notifUserCaptor.allValues.contains(printerId))

        val messageCaptor = ArgumentCaptor.forClass(Message::class.java)
        verify(messageRepo, atLeastOnce()).save(capture(messageCaptor))
        assertTrue(messageCaptor.allValues.any { it.body == "Hello team, let's collaborate on this print!" })
    }

    @Test
    fun `add participant to existing conversation creates invitation`() {
        // Create an existing 1-on-1 conversation
        val conversation = Conversation(
            id = UUID.randomUUID(),
            participantA = me,
            participantB = designer,
            lastMessageAt = Instant.now(),
        )
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = me, status = ParticipantStatus.JOINED))
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = designer, status = ParticipantStatus.JOINED))

        `when`(conversationRepo.findById(conversation.id!!)).thenReturn(Optional.of(conversation))

        val updated = chatService.addParticipant(
            conversationId = conversation.id!!,
            newUserId = printerId,
            principal = principal,
        )

        assertEquals(3, updated.participants.size)
        assertTrue(updated.isGroup)
        assertTrue(updated.participants.any { it.id == printerId })

        val printerDetail = updated.participantDetails.find { it.user.id == printerId }
        assertNotNull(printerDetail)
        assertEquals(ParticipantStatus.INVITED, printerDetail!!.status)

        // Check system message was posted
        val messageCaptor = ArgumentCaptor.forClass(Message::class.java)
        verify(messageRepo).save(capture(messageCaptor))
        val systemMsg = messageCaptor.value
        assertEquals(MessageKind.SYSTEM, systemMsg.kind)
        assertTrue(systemMsg.body.contains("Customer Me invited Bob Printer to the conversation"))

        // Check notification was sent to invited user
        val addNotifCaptor = ArgumentCaptor.forClass(UUID::class.java)
        verify(notificationService).push(
            capture(addNotifCaptor),
            anyNonNull(),
            anyNonNull(),
            any(),
            any(),
        )
        assertEquals(printerId, addNotifCaptor.value)
    }

    @Test
    fun `cannot add already existing joined participant`() {
        val conversation = Conversation(
            id = UUID.randomUUID(),
            participantA = me,
            participantB = designer,
        )
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = me, status = ParticipantStatus.JOINED))
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = designer, status = ParticipantStatus.JOINED))

        `when`(conversationRepo.findById(conversation.id!!)).thenReturn(Optional.of(conversation))

        assertThrows(ApiException::class.java) {
            chatService.addParticipant(conversation.id!!, designerId, principal)
        }
    }

    @Test
    fun `invited user cannot send message until accepted`() {
        val conversation = Conversation(
            id = UUID.randomUUID(),
            title = "Test Collab",
        )
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = me, status = ParticipantStatus.JOINED))
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = printer, status = ParticipantStatus.INVITED))

        `when`(conversationRepo.findById(conversation.id!!)).thenReturn(Optional.of(conversation))

        val printerPrincipal = AppPrincipal(id = printerId, email = printer.email, displayName = printer.displayName, roles = setOf(Role.PRINTER))

        val ex = assertThrows(ApiException::class.java) {
            chatService.send(conversation.id!!, printerPrincipal, nl.dichtbij3d.backend.dto.MessageCreateRequest(body = "I want to talk"))
        }
        assertTrue(ex.message!!.contains("accept the conversation invitation first"))
    }

    @Test
    fun `invited user can accept invitation and join`() {
        val conversation = Conversation(
            id = UUID.randomUUID(),
            title = "Test Collab",
        )
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = me, status = ParticipantStatus.JOINED))
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = printer, status = ParticipantStatus.INVITED))

        `when`(conversationRepo.findById(conversation.id!!)).thenReturn(Optional.of(conversation))

        val printerPrincipal = AppPrincipal(id = printerId, email = printer.email, displayName = printer.displayName, roles = setOf(Role.PRINTER))

        val updated = chatService.acceptInvite(conversation.id!!, printerPrincipal)
        assertEquals(ParticipantStatus.JOINED, updated.myStatus)

        val printerParticipant = conversation.participantFor(printerId)
        assertNotNull(printerParticipant)
        assertEquals(ParticipantStatus.JOINED, printerParticipant!!.status)

        // System message for joining
        val messageCaptor = ArgumentCaptor.forClass(Message::class.java)
        verify(messageRepo).save(capture(messageCaptor))
        assertTrue(messageCaptor.value.body.contains("Bob Printer joined the conversation"))
    }

    @Test
    fun `invited user can decline invitation`() {
        val conversation = Conversation(
            id = UUID.randomUUID(),
            title = "Test Collab",
        )
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = me, status = ParticipantStatus.JOINED))
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = printer, status = ParticipantStatus.INVITED))

        `when`(conversationRepo.findById(conversation.id!!)).thenReturn(Optional.of(conversation))

        val printerPrincipal = AppPrincipal(id = printerId, email = printer.email, displayName = printer.displayName, roles = setOf(Role.PRINTER))

        val updated = chatService.declineInvite(conversation.id!!, printerPrincipal)
        assertEquals(ParticipantStatus.DECLINED, updated.myStatus)

        val printerParticipant = conversation.participantFor(printerId)
        assertNotNull(printerParticipant)
        assertEquals(ParticipantStatus.DECLINED, printerParticipant!!.status)

        // System message for declining
        val messageCaptor = ArgumentCaptor.forClass(Message::class.java)
        verify(messageRepo).save(capture(messageCaptor))
        assertTrue(messageCaptor.value.body.contains("Bob Printer declined the invitation"))
    }

    companion object {
        private inline fun <reified T : Any> capture(captor: ArgumentCaptor<T>): T {
            captor.capture()
            return createDummy(T::class.java)
        }

        private inline fun <reified T : Any> anyNonNull(): T {
            any(T::class.java)
            return createDummy(T::class.java)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> createDummy(clazz: Class<T>): T = when (clazz) {
            UUID::class.java -> UUID.randomUUID() as T
            String::class.java -> "" as T
            NotificationType::class.java -> NotificationType.SYSTEM as T
            Message::class.java -> Message(conversation = Conversation(), sender = User(email = "", displayName = ""), kind = MessageKind.TEXT, body = "") as T
            else -> mock(clazz)
        }
    }
}
