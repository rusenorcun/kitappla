package app.kitappla.api.error;

import app.kitappla.api.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private boolean isNotApiRequest(HttpServletRequest request) {
        return request != null && request.getRequestURI() != null && !request.getRequestURI().startsWith("/api/");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String msg = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "İstenen kayıt bulunamadı.";
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(msg, "NOT_FOUND"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        if ("Giriş yapmalısınız.".equals(ex.getMessage())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(ex.getMessage(), "UNAUTHORIZED"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw new RuntimeException(ex);
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.joining(", "));
        if (msg.isBlank()) msg = "Geçersiz form bilgisi.";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(msg));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("Gerekli parametre eksik: " + ex.getParameterName(), "MISSING_PARAMETER"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("Bu HTTP yöntemi desteklenmiyor: " + ex.getMethod(), "METHOD_NOT_ALLOWED"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("Bu işlem için yetkiniz bulunmuyor.", "FORBIDDEN"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("Kimlik doğrulama başarısız.", "UNAUTHORIZED"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiError("Yüklenen dosya çok büyük (Sınır: 5MB).", "FILE_TOO_LARGE"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return ResponseEntity.status(ex.getStatusCode()).body(new ApiError(reason));
    }

    @ExceptionHandler(java.time.DateTimeException.class)
    public ResponseEntity<ApiError> handleDateTime(java.time.DateTimeException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("Geçersiz tarih formatı. ISO-8601 formatı bekleniyor.", "INVALID_DATE"));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("Geçersiz istek gövdesi veya JSON formatı.", "INVALID_JSON"));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("Geçersiz parametre değeri: " + ex.getName(), "TYPE_MISMATCH"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        if (ex instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(er.getStatusCode()).body(new ApiError("Geçersiz istek."));
        }
        log.error("API beklenmeyen hata:", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("Sunucuda beklenmeyen bir hata oluştu. Lütfen tekrar deneyin.", "INTERNAL_ERROR"));
    }
}
