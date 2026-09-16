package nl.dichtbij3d.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import nl.dichtbij3d.backend.security.JwtAuthenticationFilter
import nl.dichtbij3d.backend.security.MaintenanceModeFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val maintenanceModeFilter: MaintenanceModeFilter,
    private val corsProperties: CorsProperties,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Argon2id with OWASP recommended parameters (19 MiB memory, 2 iterations, 1 degree of parallelism).
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder =
        Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers { headers ->
                headers.frameOptions { it.deny() }
                headers.contentTypeOptions { }
                headers.referrerPolicy { }
            }
            .anonymous(AbstractHttpConfigurer<*, *>::disable)
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                auth.requestMatchers(
                    "/api/auth/**",
                    "/api/public/**",
                    "/api/share/**",
                    "/api/files/**",
                    "/api/banners/**",
                    "/actuator/health",
                    "/actuator/info",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                ).permitAll()
                // Authenticated GET endpoints before general wildcards
                auth.requestMatchers(HttpMethod.GET, "/api/users/me", "/api/users/blocks", "/api/models/mine", "/api/models/library").authenticated()
                // Browsing the marketplace does not require an account.
                auth.requestMatchers(HttpMethod.GET, "/api/adverts/**", "/api/models/**", "/api/tags/**", "/api/printers/**", "/api/users", "/api/users/*")
                    .permitAll()
                auth.requestMatchers(HttpMethod.POST, "/api/adverts/*/view", "/api/calculator/estimate").permitAll()
                auth.requestMatchers("/api/admin/**").hasRole("ADMIN")
                auth.anyRequest().authenticated()
            }
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint { _, response, _ -> writeError(response, 401, "unauthorized") }
                ex.accessDeniedHandler { _, response, _ -> writeError(response, 403, "forbidden") }
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterAfter(maintenanceModeFilter, JwtAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun jwtFilterRegistration(filter: JwtAuthenticationFilter): FilterRegistrationBean<JwtAuthenticationFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    @Bean
    fun maintenanceFilterRegistration(filter: MaintenanceModeFilter): FilterRegistrationBean<MaintenanceModeFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    private fun writeError(response: HttpServletResponse, status: Int, code: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf("status" to status, "error" to code, "message" to "Authentication required")
            )
        )
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = corsProperties.allowedOrigins.ifEmpty { listOf("*") } +
                listOf("http://localhost:*", "http://127.0.0.1:*", "exp://*")
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            exposedHeaders = listOf("Content-Disposition")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }
}
