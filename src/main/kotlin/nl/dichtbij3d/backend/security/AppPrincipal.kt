package nl.dichtbij3d.backend.security

import nl.dichtbij3d.backend.domain.Role
import nl.dichtbij3d.backend.domain.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.util.UUID

/**
 * Authenticated principal placed in the SecurityContext by [JwtAuthenticationFilter].
 */
class AppPrincipal(
    val id: UUID,
    val email: String,
    val displayName: String,
    val roles: Set<Role>,
    private val active: Boolean = true,
) : UserDetails {

    override fun getAuthorities(): Collection<GrantedAuthority> =
        roles.map { SimpleGrantedAuthority(it.authority) }

    override fun getPassword(): String = ""
    override fun getUsername(): String = email
    override fun isAccountNonExpired(): Boolean = active
    override fun isAccountNonLocked(): Boolean = active
    override fun isCredentialsNonExpired(): Boolean = active
    override fun isEnabled(): Boolean = active

    val isAdmin: Boolean get() = roles.contains(Role.ADMIN)

    companion object {
        fun of(user: User) = AppPrincipal(
            id = user.id!!,
            email = user.email,
            displayName = user.displayName,
            roles = user.roles.toSet(),
            active = user.enabled && user.deletedAt == null,
        )
    }
}
