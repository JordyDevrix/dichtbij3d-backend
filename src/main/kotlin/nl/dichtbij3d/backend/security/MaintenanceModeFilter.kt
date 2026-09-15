package nl.dichtbij3d.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.dichtbij3d.backend.service.PlatformService
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class MaintenanceModeFilter(
    private val platformService: PlatformService,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Preflight CORS requests are always allowed
        if (request.method.equals(HttpMethod.OPTIONS.name(), ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }

        // Only enforce when maintenance mode is actively enabled
        if (!platformService.isMaintenanceEnabled()) {
            filterChain.doFilter(request, response)
            return
        }

        val uri = request.requestURI

        // Whitelisted endpoints during maintenance
        if (isWhitelisted(uri)) {
            filterChain.doFilter(request, response)
            return
        }

        // Administrators can navigate and perform all operations
        val auth = SecurityContextHolder.getContext().authentication
        val isAdmin = auth != null && auth.isAuthenticated && auth.authorities.any { it.authority == "ROLE_ADMIN" }
        if (isAdmin) {
            filterChain.doFilter(request, response)
            return
        }

        // Block all other requests with HTTP 503 Service Unavailable
        val maintenance = platformService.getMaintenanceStatus()
        response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "status" to 503,
                    "error" to "maintenance_mode",
                    "message" to (maintenance.message.ifBlank { "Website temporarily down for maintenance" }),
                    "maintenance" to maintenance,
                )
            )
        )
    }

    private fun isWhitelisted(uri: String): Boolean {
        return uri.startsWith("/api/public/maintenance") ||
            uri.startsWith("/api/auth/") ||
            uri.startsWith("/api/admin/") ||
            uri == "/api/users/me" ||
            uri.startsWith("/actuator/") ||
            uri.startsWith("/v3/api-docs") ||
            uri.startsWith("/swagger-ui")
    }
}
