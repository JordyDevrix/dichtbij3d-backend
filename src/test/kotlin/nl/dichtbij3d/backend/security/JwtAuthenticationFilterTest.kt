package nl.dichtbij3d.backend.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.domain.User
import nl.dichtbij3d.backend.repo.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.*

class JwtAuthenticationFilterTest {

    private lateinit var tokenService: TokenService
    private lateinit var userRepository: UserRepository
    private lateinit var filter: JwtAuthenticationFilter

    private val userId = UUID.randomUUID()
    private val rawToken = "valid.access.jwt.token"

    @BeforeEach
    fun setup() {
        tokenService = mock(TokenService::class.java)
        userRepository = mock(UserRepository::class.java)
        filter = JwtAuthenticationFilter(tokenService, userRepository)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun teardown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `authenticates active user using current database roles`() {
        val request = mock(HttpServletRequest::class.java)
        val response = mock(HttpServletResponse::class.java)
        val chain = mock(FilterChain::class.java)

        `when`(request.getHeader("Authorization")).thenReturn("Bearer $rawToken")
        `when`(tokenService.parse(rawToken)).thenReturn(
            ParsedToken(
                userId = userId,
                email = "old@example.com",
                displayName = "Old Name",
                roles = setOf(Role.CUSTOMER),
                type = TokenType.ACCESS,
            )
        )

        val freshUser = User(
            id = userId,
            email = "fresh@example.com",
            displayName = "Fresh Name",
            roles = mutableSetOf(Role.PRINTER, Role.CUSTOMER),
            enabled = true,
            deletedAt = null,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(freshUser))

        filter.doFilter(request, response, chain)

        val auth = SecurityContextHolder.getContext().authentication
        assertNotNull(auth)
        assertTrue(auth.isAuthenticated)
        val principal = auth.principal as AppPrincipal
        assertEquals("fresh@example.com", principal.email)
        assertEquals("Fresh Name", principal.displayName)
        assertTrue(principal.roles.contains(Role.PRINTER))
        assertTrue(principal.roles.contains(Role.CUSTOMER))

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `rejects authentication when user is disabled in database`() {
        val request = mock(HttpServletRequest::class.java)
        val response = mock(HttpServletResponse::class.java)
        val chain = mock(FilterChain::class.java)

        `when`(request.getHeader("Authorization")).thenReturn("Bearer $rawToken")
        `when`(tokenService.parse(rawToken)).thenReturn(
            ParsedToken(
                userId = userId,
                email = "user@example.com",
                displayName = "User",
                roles = setOf(Role.CUSTOMER),
                type = TokenType.ACCESS,
            )
        )

        val disabledUser = User(
            id = userId,
            email = "user@example.com",
            displayName = "User",
            roles = mutableSetOf(Role.CUSTOMER),
            enabled = false,
            deletedAt = null,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(disabledUser))

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `rejects authentication when user is deleted in database`() {
        val request = mock(HttpServletRequest::class.java)
        val response = mock(HttpServletResponse::class.java)
        val chain = mock(FilterChain::class.java)

        `when`(request.getHeader("Authorization")).thenReturn("Bearer $rawToken")
        `when`(tokenService.parse(rawToken)).thenReturn(
            ParsedToken(
                userId = userId,
                email = "user@example.com",
                displayName = "User",
                roles = setOf(Role.CUSTOMER),
                type = TokenType.ACCESS,
            )
        )

        val deletedUser = User(
            id = userId,
            email = "user@example.com",
            displayName = "User",
            roles = mutableSetOf(Role.CUSTOMER),
            enabled = true,
            deletedAt = Instant.now(),
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(deletedUser))

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `immediately removes admin authority when admin role is revoked in database`() {
        val request = mock(HttpServletRequest::class.java)
        val response = mock(HttpServletResponse::class.java)
        val chain = mock(FilterChain::class.java)

        `when`(request.getHeader("Authorization")).thenReturn("Bearer $rawToken")
        // Token still has ADMIN in claims
        `when`(tokenService.parse(rawToken)).thenReturn(
            ParsedToken(
                userId = userId,
                email = "exadmin@example.com",
                displayName = "Ex Admin",
                roles = setOf(Role.ADMIN, Role.CUSTOMER),
                type = TokenType.ACCESS,
            )
        )

        // Database no longer has ADMIN
        val demotedUser = User(
            id = userId,
            email = "exadmin@example.com",
            displayName = "Ex Admin",
            roles = mutableSetOf(Role.CUSTOMER),
            enabled = true,
            deletedAt = null,
        )
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(demotedUser))

        filter.doFilter(request, response, chain)

        val auth = SecurityContextHolder.getContext().authentication
        assertNotNull(auth)
        val principal = auth.principal as AppPrincipal
        assertFalse(principal.isAdmin)
        assertFalse(principal.roles.contains(Role.ADMIN))
        assertFalse(auth.authorities.any { it.authority == "ROLE_ADMIN" })

        verify(chain).doFilter(request, response)
    }
}
