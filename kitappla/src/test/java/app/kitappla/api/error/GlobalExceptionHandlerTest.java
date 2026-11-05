package app.kitappla.api.error;

import app.kitappla.api.dto.ApiError;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.DateTimeException;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Global Exception Handler Kapsamlı ve Sağlamlaştırma Testleri")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/test");
    }

    @Test
    @DisplayName("NoSuchElementException -> 404 NOT_FOUND ve açık hata nedeni döner")
    void testNoSuchElementException() {
        var ex = new NoSuchElementException("Kitap bulunamadı (ID: 42)");
        ResponseEntity<ApiError> res = handler.handleNotFound(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().error()).isEqualTo("Kitap bulunamadı (ID: 42)");
        assertThat(res.getBody().message()).isEqualTo("Kitap bulunamadı (ID: 42)");
        assertThat(res.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().path()).isEqualTo("/api/v1/test");
    }

    @Test
    @DisplayName("NoResourceFoundException -> 404 NOT_FOUND ve kaynak yolu döner")
    void testNoResourceFoundException() throws Exception {
        var ex = new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "static/logo.png");
        ResponseEntity<ApiError> res = handler.handleNoResourceFound(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().message()).contains("static/logo.png");
    }

    @Test
    @DisplayName("NoHandlerFoundException -> 404 NOT_FOUND döner")
    void testNoHandlerFoundException() throws Exception {
        var ex = new NoHandlerFoundException("GET", "/api/v1/unknown", null);
        ResponseEntity<ApiError> res = handler.handleNoHandlerFound(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().message()).contains("/api/v1/unknown");
    }

    @Test
    @DisplayName("IllegalArgumentException -> 400 BAD_REQUEST ve açık neden döner")
    void testIllegalArgumentException() {
        var ex = new IllegalArgumentException("Adet 0 veya negatif olamaz.");
        ResponseEntity<ApiError> res = handler.handleIllegalArgument(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("BAD_REQUEST");
        assertThat(res.getBody().message()).isEqualTo("Adet 0 veya negatif olamaz.");
    }

    @Test
    @DisplayName("IllegalStateException -> Giriş uyarısı 401, e-posta uyarısı 403, diğeri 400 döner")
    void testIllegalStateException() {
        // 1. Giriş yapılmamış
        var loginEx = new IllegalStateException("Giriş yapmalısınız.");
        ResponseEntity<ApiError> loginRes = handler.handleIllegalState(loginEx, request);
        assertThat(loginRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginRes.getBody().code()).isEqualTo("UNAUTHORIZED");

        // 2. E-posta doğrulanmamış
        var emailEx = new IllegalStateException("İşlem için e-posta adresini doğrulamalısın.");
        ResponseEntity<ApiError> emailRes = handler.handleIllegalState(emailEx, request);
        assertThat(emailRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(emailRes.getBody().code()).isEqualTo("EMAIL_NOT_VERIFIED");

        // 3. Genel geçersiz durum
        var genericEx = new IllegalStateException("Kayıt durumu bu işlem için uygun değil.");
        ResponseEntity<ApiError> genericRes = handler.handleIllegalState(genericEx, request);
        assertThat(genericRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(genericRes.getBody().code()).isEqualTo("ILLEGAL_STATE");
        assertThat(genericRes.getBody().message()).isEqualTo("Kayıt durumu bu işlem için uygun değil.");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException -> 400 BAD_REQUEST, VALIDATION_ERROR ve alan detayları döner")
    void testMethodArgumentNotValidException() throws Exception {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "email", "Geçersiz e-posta"));
        bindingResult.addError(new FieldError("target", "title", "Başlık zorunludur"));

        var method = getClass().getDeclaredMethod("setUp");
        var param = new MethodParameter(method, -1);
        var ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<ApiError> res = handler.handleValidation(ex, request);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(res.getBody().message()).contains("email: Geçersiz e-posta");
        assertThat(res.getBody().message()).contains("title: Başlık zorunludur");
        assertThat(res.getBody().details()).containsEntry("email", "Geçersiz e-posta");
        assertThat(res.getBody().details()).containsEntry("title", "Başlık zorunludur");
    }

    @Test
    @DisplayName("BindException -> 400 BAD_REQUEST ve form alan detayları döner")
    void testBindException() {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "form");
        bindingResult.addError(new FieldError("form", "phone", "Telefon formatı geçersiz"));

        var ex = new BindException(bindingResult);
        ResponseEntity<ApiError> res = handler.handleBindException(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(res.getBody().message()).contains("phone: Telefon formatı geçersiz");
    }

    @Test
    @DisplayName("ConstraintViolationException -> 400 BAD_REQUEST ve kısıt açıklaması döner")
    void testConstraintViolationException() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("quantity");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("en az 1 olmalıdır");

        var ex = new ConstraintViolationException("Hata", Set.of(violation));
        ResponseEntity<ApiError> res = handler.handleConstraintViolation(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(res.getBody().message()).contains("quantity: en az 1 olmalıdır");
        assertThat(res.getBody().details()).containsEntry("quantity", "en az 1 olmalıdır");
    }

    @Test
    @DisplayName("MissingServletRequestParameterException -> 400 BAD_REQUEST ve parametre adı döner")
    void testMissingServletRequestParameterException() throws Exception {
        var ex = new MissingServletRequestParameterException("page", "Integer");
        ResponseEntity<ApiError> res = handler.handleMissingParam(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("MISSING_PARAMETER");
        assertThat(res.getBody().message()).contains("page");
        assertThat(res.getBody().message()).contains("Integer");
    }

    @Test
    @DisplayName("MissingServletRequestPartException -> 400 BAD_REQUEST ve parça adı döner")
    void testMissingServletRequestPartException() throws Exception {
        var ex = new MissingServletRequestPartException("file");
        ResponseEntity<ApiError> res = handler.handleMissingPart(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("MISSING_PART");
        assertThat(res.getBody().message()).contains("file");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException -> 400 BAD_REQUEST, TYPE_MISMATCH ve tür açıklaması döner")
    void testMethodArgumentTypeMismatchException() {
        var ex = new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null);
        ResponseEntity<ApiError> res = handler.handleTypeMismatch(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("TYPE_MISMATCH");
        assertThat(res.getBody().message()).contains("id");
        assertThat(res.getBody().message()).contains("abc");
        assertThat(res.getBody().message()).contains("Long");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException -> 400 BAD_REQUEST ve INVALID_JSON döner")
    void testHttpMessageNotReadableException() {
        var cause = new JsonParseException(null, "Unexpected character");
        var ex = new HttpMessageNotReadableException("JSON hatası", cause, new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiError> res = handler.handleMessageNotReadable(ex, request);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("INVALID_JSON");
        assertThat(res.getBody().message()).contains("Geçersiz istek gövdesi");
        assertThat(res.getBody().message()).contains("Sözdizimi hatası");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException -> 405 METHOD_NOT_ALLOWED ve desteklenen metotlar döner")
    void testHttpRequestMethodNotSupportedException() throws Exception {
        var ex = new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));
        ResponseEntity<ApiError> res = handler.handleMethodNotSupported(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(res.getBody().message()).contains("DELETE");
        assertThat(res.getBody().message()).contains("GET");
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException -> 415 UNSUPPORTED_MEDIA_TYPE döner")
    void testHttpMediaTypeNotSupportedException() throws Exception {
        var ex = new HttpMediaTypeNotSupportedException(MediaType.TEXT_HTML, List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<ApiError> res = handler.handleMediaTypeNotSupported(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(res.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(res.getBody().message()).contains("text/html");
    }

    @Test
    @DisplayName("HttpMediaTypeNotAcceptableException -> 406 NOT_ACCEPTABLE döner")
    void testHttpMediaTypeNotAcceptableException() throws Exception {
        var ex = new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<ApiError> res = handler.handleMediaTypeNotAcceptable(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(res.getBody().code()).isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    @DisplayName("AccessDeniedException -> 403 FORBIDDEN döner")
    void testAccessDeniedException() {
        var ex = new AccessDeniedException("Erişim reddedildi");
        ResponseEntity<ApiError> res = handler.handleAccessDenied(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().code()).isEqualTo("FORBIDDEN");
        assertThat(res.getBody().message()).contains("yetkiniz bulunmuyor");
    }

    @Test
    @DisplayName("AuthenticationException -> 401 UNAUTHORIZED döner")
    void testAuthenticationException() {
        var ex = new BadCredentialsException("Hatalı kimlik bilgisi");
        ResponseEntity<ApiError> res = handler.handleAuthentication(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().code()).isEqualTo("UNAUTHORIZED");
        assertThat(res.getBody().message()).contains("Kimlik doğrulama başarısız");
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException -> 413 PAYLOAD_TOO_LARGE döner")
    void testMaxUploadSizeExceededException() {
        var ex = new MaxUploadSizeExceededException(5242880);
        ResponseEntity<ApiError> res = handler.handleMaxUploadSize(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(res.getBody().code()).isEqualTo("FILE_TOO_LARGE");
        assertThat(res.getBody().message()).contains("5MB");
    }

    @Test
    @DisplayName("DataIntegrityViolationException -> 409 CONFLICT ve DATA_INTEGRITY_VIOLATION döner")
    void testDataIntegrityViolationException() {
        var ex = new DataIntegrityViolationException("duplicate key value");
        ResponseEntity<ApiError> res = handler.handleDataIntegrityViolation(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().code()).isEqualTo("DATA_INTEGRITY_VIOLATION");
        assertThat(res.getBody().message()).contains("Veritabanı kısıt ihlali");
    }

    @Test
    @DisplayName("OptimisticLockingFailureException -> 409 CONFLICT ve CONCURRENT_MODIFICATION döner")
    void testOptimisticLockingFailureException() {
        var ex = new OptimisticLockingFailureException("Row was updated by another transaction");
        ResponseEntity<ApiError> res = handler.handleOptimisticLocking(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().code()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(res.getBody().message()).contains("güncellendi");
    }

    @Test
    @DisplayName("ResponseStatusException -> Tanımlı durum kodu ve açıklaması döner")
    void testResponseStatusException() {
        var ex = new ResponseStatusException(HttpStatus.GONE, "Kayıt arşivlendi ve kaldırıldı");
        ResponseEntity<ApiError> res = handler.handleResponseStatus(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(res.getBody().message()).isEqualTo("Kayıt arşivlendi ve kaldırıldı");
    }

    @Test
    @DisplayName("DateTimeException -> 400 BAD_REQUEST ve INVALID_DATE döner")
    void testDateTimeException() {
        var ex = new DateTimeException("Invalid date");
        ResponseEntity<ApiError> res = handler.handleDateTime(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("INVALID_DATE");
        assertThat(res.getBody().message()).contains("ISO-8601");
    }

    @Test
    @DisplayName("Genel Exception -> 500 INTERNAL_SERVER_ERROR ve INTERNAL_ERROR döner")
    void testGeneralException() throws Exception {
        var ex = new RuntimeException("Veritabanı bağlantı hatası");
        ResponseEntity<ApiError> res = handler.handleGeneral(ex, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(res.getBody().message()).contains("beklenmeyen bir hata oluştu");
    }

    @Test
    @DisplayName("Web (HTML) isteklerinde istisna yukarı fırlatılır (Thymeleaf hata görünümüne devredilir)")
    void testWebRequestsAreRethrown() {
        MockHttpServletRequest webRequest = new MockHttpServletRequest();
        webRequest.setRequestURI("/kesfet");
        webRequest.addHeader("Accept", "text/html");

        var ex = new NoSuchElementException("Kitap bulunamadı");
        assertThrows(NoSuchElementException.class, () -> handler.handleNotFound(ex, webRequest));
    }
}
