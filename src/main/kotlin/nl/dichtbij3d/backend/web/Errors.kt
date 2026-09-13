package nl.dichtbij3d.backend.web

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.time.Instant

class ApiException(
    val status: HttpStatus,
    override val message: String,
    val code: String = status.name.lowercase(),
    /** Per-field messages, so a form can mark the input that needs attention. */
    val fieldErrors: Map<String, String>? = null,
) : RuntimeException(message) {
    companion object {
        fun notFound(what: String) = ApiException(HttpStatus.NOT_FOUND, "$what not found", "not_found")
        fun badRequest(message: String, fieldErrors: Map<String, String>? = null) =
            ApiException(HttpStatus.BAD_REQUEST, message, "bad_request", fieldErrors)
        fun forbidden(message: String = "You are not allowed to do this") =
            ApiException(HttpStatus.FORBIDDEN, message, "forbidden")

        fun unauthorized(message: String = "Invalid credentials") =
            ApiException(HttpStatus.UNAUTHORIZED, message, "unauthorized")

        fun conflict(message: String) = ApiException(HttpStatus.CONFLICT, message, "conflict")
    }
}

data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val fieldErrors: Map<String, String>? = null,
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, request: HttpServletRequest): ResponseEntity<ApiError> =
        ResponseEntity.status(ex.status).body(
            ApiError(Instant.now(), ex.status.value(), ex.code, ex.message, request.requestURI, ex.fieldErrors)
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val fields = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return ResponseEntity.badRequest().body(
            ApiError(
                Instant.now(), 400, "validation_failed",
                "Some fields are invalid", request.requestURI, fields
            )
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest) =
        ResponseEntity.badRequest().body(
            ApiError(Instant.now(), 400, "bad_request", "Malformed request body", request.requestURI)
        )

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUpload(ex: MaxUploadSizeExceededException, request: HttpServletRequest) =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ApiError(Instant.now(), 413, "file_too_large", "File is too large", request.requestURI)
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException, request: HttpServletRequest) =
        ResponseEntity.badRequest().body(
            ApiError(Instant.now(), 400, "bad_request", ex.message ?: "Bad request", request.requestURI)
        )

    @ExceptionHandler(Exception::class)
    fun handleUnknown(ex: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.error("Unhandled exception on {}", request.requestURI, ex)
        return ResponseEntity.internalServerError().body(
            ApiError(Instant.now(), 500, "internal_error", "Something went wrong", request.requestURI)
        )
    }
}
