package nl.dichtbij3d.backend.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.dichtbij3d.backend.repo.UserRepository
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Reads the `Authorization: Bearer <accessToken>` header and populates the SecurityContext
 * after verifying that the user account exists, is active (not deleted or disabled),
 * and uses the user's latest database permissions/roles instead of stale token claims.
 * Anonymous requests are left untouched so public endpoints keep working.
 */
@Component
class JwtAuthenticationFilter(
    private val tokenService: TokenService,
    private val userRepository: UserRepository,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith(BEARER, ignoreCase = true) &&
            SecurityContextHolder.getContext().authentication == null
        ) {
            val parsed = tokenService.parse(header.substring(BEARER.length).trim())
            if (parsed != null && parsed.type == TokenType.ACCESS) {
                val user = userRepository.findById(parsed.userId).orElse(null)
                if (user != null && user.enabled && user.deletedAt == null) {
                    val principal = AppPrincipal.of(user)
                    val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
                    auth.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val BEARER = "Bearer "
    }
}
