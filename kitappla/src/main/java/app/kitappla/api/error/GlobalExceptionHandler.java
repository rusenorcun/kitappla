package app.kitappla.api.error;

import app.kitappla.api.dto.ApiError;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Kapsamlı ve açıklayıcı Global İstisna Yöneticisi (Global Exception Handler).
 * <p>
 * Tüm REST ve API isteklerinde oluşan istisnaları yakalar, hatanın nedenini açık ve anlaşılır
 * Türkçe mesajlarla detaylandırır, makinece okunabilir hata kodları ve doğrulama ayrıntıları döner.
 * Web (Thymeleaf/HTML) istekleri ise markalı hata şablonlarına yönlendirilmek üzere yukarı iletilir.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private boolean isNotApiRequest(HttpServletRequest request) {
        if (request == null) return false;
        String uri = request.getRequestURI();
        if (uri != null) {
            String ctx = request.getContextPath();
            String path = (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) ? uri.substring(ctx.length()) : uri;
            if (path.startsWith("/api/")) return false;
        }
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) return false;
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE)) return false;
        return true;
    }

    private String path(HttpServletRequest request) {
        return request != null ? request.getRequestURI() : null;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String msg = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : "İstenen kayıt bulunamadı.";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(msg, "NOT_FOUND", path(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        String msg = "İstenen kaynak bulunamadı: " + ex.getResourcePath();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(msg, "NOT_FOUND", path(request)));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNoHandlerFound(NoHandlerFoundException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        String msg = "İstenen uç nokta adresi bulunamadı: " + ex.getHttpMethod() + " " + ex.getRequestURL();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(msg, "NOT_FOUND", path(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String raw = ex.getMessage();
        String msg = (raw != null && !raw.isBlank() && !raw.startsWith("No enum constant "))
                ? raw
                : "Geçersiz istek parametresi veya argüman.";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(msg, "BAD_REQUEST", path(request)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String raw = ex.getMessage() != null ? ex.getMessage() : "İşlem şu anki durumda gerçekleştirilemiyor.";
        if ("Giriş yapmalısınız.".equals(raw)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiError(raw, "UNAUTHORIZED", path(request)));
        }
        String kucuk = raw.toLowerCase(java.util.Locale.forLanguageTag("tr"));
        if (kucuk.contains("e-posta") && (kucuk.contains("doğrula") || kucuk.contains("onayla"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiError(raw, "EMAIL_NOT_VERIFIED", path(request)));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(raw, "ILLEGAL_STATE", path(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw new RuntimeException(ex);
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String defaultMessage = fe.getDefaultMessage() != null && !fe.getDefaultMessage().isBlank()
                    ? fe.getDefaultMessage()
                    : "Geçersiz değer";
            details.put(fe.getField(), defaultMessage);
        }
        String msg = details.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));
        if (msg.isBlank()) msg = "Geçersiz form bilgisi.";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(msg, "VALIDATION_ERROR", path(request), details));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBindException(BindException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw new RuntimeException(ex);
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fe : ex.getFieldErrors()) {
            String defaultMessage = fe.getDefaultMessage() != null && !fe.getDefaultMessage().isBlank()
                    ? fe.getDefaultMessage()
                    : "Geçersiz değer";
            details.put(fe.getField(), defaultMessage);
        }
        String msg = details.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));
        if (msg.isBlank()) msg = "Geçersiz form parametresi.";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(msg, "VALIDATION_ERROR", path(request), details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        Map<String, String> details = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(cv ->
                details.put(cv.getPropertyPath() != null ? cv.getPropertyPath().toString() : "param", cv.getMessage())
        );
        String msg = details.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));
        if (msg.isBlank()) msg = "Doğrulama kısıtı ihlali.";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(msg, "VALIDATION_ERROR", path(request), details));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        String typeInfo = ex.getParameterType() != null ? " (Beklenen tür: " + ex.getParameterType() + ")" : "";
        String msg = "Gerekli parametre eksik: " + ex.getParameterName() + typeInfo;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(msg, "MISSING_PARAMETER", path(request)));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        String msg = "Gerekli dosya/form parçası eksik: " + ex.getRequestPartName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(msg, "MISSING_PART", path(request)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String typeName = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "uygun format";
        String val = ex.getValue() != null ? String.valueOf(ex.getValue()) : "boş";
        String msg = "Geçersiz parametre değeri: " + ex.getName() + " ('" + val + "'). Beklenen tür: " + typeName;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(msg, "TYPE_MISMATCH", path(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String reason = "Geçersiz istek gövdesi veya JSON formatı.";
        Throwable root = ex.getMostSpecificCause();
        if (root instanceof JsonParseException) {
            reason = "Geçersiz istek gövdesi veya JSON formatı: Sözdizimi hatası.";
        } else if (root instanceof InvalidFormatException ife) {
            String target = ife.getTargetType() != null ? ife.getTargetType().getSimpleName() : "alan";
            reason = "Geçersiz istek gövdesi veya JSON formatı: '" + ife.getValue() + "' değeri '" + target + "' türüne dönüştürülemedi.";
        } else if (root instanceof MismatchedInputException mie) {
            reason = "Geçersiz istek gövdesi veya JSON formatı: Beklenmeyen veri yapısı.";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(reason, "INVALID_JSON", path(request)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        String supported = ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()
                ? " (Desteklenen yöntemler: " + ex.getSupportedHttpMethods() + ")"
                : "";
        String msg = "Bu HTTP yöntemi desteklenmiyor: " + ex.getMethod() + supported;
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError(msg, "METHOD_NOT_ALLOWED", path(request)));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        String supported = ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()
                ? " (Desteklenen türler: " + ex.getSupportedMediaTypes() + ")"
                : "";
        String msg = "Desteklenmeyen içerik türü: " + ex.getContentType() + supported;
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError(msg, "UNSUPPORTED_MEDIA_TYPE", path(request)));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        String msg = "İstenen yanıt içerik formatı desteklenmiyor. Desteklenen türler: " + ex.getSupportedMediaTypes();
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .body(new ApiError(msg, "NOT_ACCEPTABLE", path(request)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("Bu işlem için yetkiniz bulunmuyor.", "FORBIDDEN", path(request)));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("Kimlik doğrulama başarısız.", "UNAUTHORIZED", path(request)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("Yüklenen dosya çok büyük (Sınır: 5MB).", "FILE_TOO_LARGE", path(request)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String msg = "Veritabanı kısıt ihlali: Bu kayıt sistemde zaten mevcut veya ilişkili veriler nedeniyle işlem gerçekleştirilemedi.";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(msg, "DATA_INTEGRITY_VIOLATION", path(request)));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLocking(OptimisticLockingFailureException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String msg = "Kayıt başka bir işlem tarafından güncellendi. Lütfen sayfayı yenileyip tekrar deneyin.";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(msg, "CONCURRENT_MODIFICATION", path(request)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ApiError(reason, ex.getStatusCode().toString(), path(request)));
    }

    @ExceptionHandler(DateTimeException.class)
    public ResponseEntity<ApiError> handleDateTime(DateTimeException ex, HttpServletRequest request) {
        if (isNotApiRequest(request)) throw ex;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("Geçersiz tarih formatı. ISO-8601 formatı bekleniyor.", "INVALID_DATE", path(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex, HttpServletRequest request) throws Exception {
        if (isNotApiRequest(request)) throw ex;
        if (ex.getClass().getName().contains("AsyncRequestNotUsableException") || ex instanceof IOException) {
            throw ex;
        }
        if (ex instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            String detail = er.getBody() != null && er.getBody().getDetail() != null
                    ? er.getBody().getDetail()
                    : "Geçersiz istek.";
            return ResponseEntity.status(er.getStatusCode())
                    .body(new ApiError(detail, "BAD_REQUEST", path(request)));
        }
        log.error("API beklenmeyen hata [{}]:", path(request), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("Sunucuda beklenmeyen bir hata oluştu. Lütfen tekrar deneyin.", "INTERNAL_ERROR", path(request)));
    }
}
