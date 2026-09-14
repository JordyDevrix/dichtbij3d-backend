package nl.dichtbij3d.backend.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import nl.dichtbij3d.backend.dto.*
import nl.dichtbij3d.backend.security.AppPrincipal
import nl.dichtbij3d.backend.service.AuthService
import nl.dichtbij3d.backend.service.PasskeyService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val passkeyService: PasskeyService,
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest, http: HttpServletRequest): AuthResponse =
        authService.register(request, http)

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, http: HttpServletRequest): AuthResponse =
        authService.login(request, http)

    @PostMapping("/mfa/verify")
    fun verifyMfa(@Valid @RequestBody request: MfaVerifyRequest, http: HttpServletRequest): AuthResponse =
        authService.verifyMfa(request, http)

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest, http: HttpServletRequest): AuthResponse =
        authService.refresh(request.refreshToken, http)

    @PostMapping("/logout")
    fun logout(@RequestBody(required = false) request: RefreshRequest?): MessageResponse {
        authService.logout(request?.refreshToken)
        return MessageResponse("Signed out")
    }

    @PostMapping("/logout-all")
    fun logoutAll(@AuthenticationPrincipal principal: AppPrincipal): MessageResponse {
        authService.logoutEverywhere(principal.id)
        return MessageResponse("Signed out on all devices")
    }

    @PostMapping("/password")
    fun changePassword(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: PasswordChangeRequest,
    ): MessageResponse {
        authService.changePassword(principal.id, request)
        return MessageResponse("Password updated")
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest): MessageResponse {
        authService.requestPasswordReset(request.email)
        return MessageResponse("If an account exists with this email address, a password reset link has been sent.")
    }

    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): MessageResponse {
        authService.resetPassword(request.token, request.newPassword)
        return MessageResponse("Password has been reset successfully.")
    }

    // ---------------------------------------------------------------- TOTP

    @PostMapping("/mfa/totp/setup")
    fun totpSetup(@AuthenticationPrincipal principal: AppPrincipal): TotpSetupResponse =
        authService.startTotpSetup(principal.id)

    @PostMapping("/mfa/totp/enable")
    fun totpEnable(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: TotpEnableRequest,
    ): MessageResponse {
        authService.enableTotp(principal.id, request.code)
        return MessageResponse("Two-factor authentication enabled")
    }

    @PostMapping("/mfa/totp/disable")
    fun totpDisable(
        @AuthenticationPrincipal principal: AppPrincipal,
        @Valid @RequestBody request: TotpEnableRequest,
    ): MessageResponse {
        authService.disableTotp(principal.id, request.code)
        return MessageResponse("Two-factor authentication disabled")
    }

    // ---------------------------------------------------------------- passkeys

    @PostMapping("/passkeys/register/options")
    fun passkeyRegisterOptions(@AuthenticationPrincipal principal: AppPrincipal): Map<String, Any?> =
        passkeyService.registrationOptions(principal.id)

    @PostMapping("/passkeys/register/finish")
    fun passkeyRegisterFinish(
        @AuthenticationPrincipal principal: AppPrincipal,
        @RequestBody request: PasskeyRegisterFinishRequest,
    ): PasskeyDto = passkeyService.finishRegistration(principal.id, request.credential, request.label)

    @PostMapping("/passkeys/login/options")
    fun passkeyLoginOptions(): Map<String, Any?> = passkeyService.authenticationOptions()

    @PostMapping("/passkeys/login/finish")
    fun passkeyLoginFinish(
        @RequestBody request: PasskeyLoginFinishRequest,
        http: HttpServletRequest,
    ): AuthResponse = passkeyService.finishAuthentication(request.credential, http)

    @GetMapping("/passkeys")
    fun passkeys(@AuthenticationPrincipal principal: AppPrincipal): List<PasskeyDto> =
        passkeyService.list(principal.id)

    @DeleteMapping("/passkeys/{id}")
    fun deletePasskey(
        @AuthenticationPrincipal principal: AppPrincipal,
        @PathVariable id: UUID,
    ): MessageResponse {
        passkeyService.delete(principal.id, id)
        return MessageResponse("Passkey removed")
    }
}
