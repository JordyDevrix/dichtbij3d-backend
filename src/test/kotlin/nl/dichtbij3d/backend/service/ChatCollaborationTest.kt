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
    fun `start multi-user collaboration chat`() {
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
        val names = dto.participants.map { it.displayName }
        assertTrue(names.contains("Customer Me"))
        assertTrue(names.contains("Alice Designer"))
        assertTrue(names.contains("Bob Printer"))

        val messageCaptor = ArgumentCaptor.forClass(Message::class.java)
        verify(messageRepo, atLeastOnce()).save(messageCaptor.capture())
        assertTrue(messageCaptor.allValues.any { it.body == "Hello team, let's collaborate on this print!" })
    }

    @Test
    fun `add participant to existing conversation`() {
        // Create an existing 1-on-1 conversation
        val conversation = Conversation(
            id = UUID.randomUUID(),
            participantA = me,
            participantB = designer,
            lastMessageAt = Instant.now(),
        )
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = me))
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = designer))

        `when`(conversationRepo.findById(conversation.id!!)).thenReturn(Optional.of(conversation))

        val updated = chatService.addParticipant(
            conversationId = conversation.id!!,
            newUserId = printerId,
            principal = principal,
        )

        assertEquals(3, updated.participants.size)
        assertTrue(updated.isGroup)
        assertTrue(updated.participants.any { it.id == printerId })

        // Check system message was posted
        val messageCaptor = ArgumentCaptor.forClass(Message::class.java)
        verify(messageRepo).save(messageCaptor.capture())
        val systemMsg = messageCaptor.value
        assertEquals(MessageKind.SYSTEM, systemMsg.kind)
        assertTrue(systemMsg.body.contains("Customer Me added Bob Printer to the conversation"))
    }

    @Test
    fun `cannot add already existing participant`() {
        val conversation = Conversation(
            id = UUID.randomUUID(),
            participantA = me,
            participantB = designer,
        )
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = me))
        conversation.participants.add(ConversationParticipant(conversation = conversation, user = designer))

        `when`(conversationRepo.findById(conversation.id!!)).thenReturn(Optional.of(conversation))

        assertThrows(ApiException::class.java) {
            chatService.addParticipant(conversation.id!!, designerId, principal)
        }
    }
}
