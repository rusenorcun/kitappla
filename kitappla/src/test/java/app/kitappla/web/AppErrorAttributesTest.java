package app.kitappla.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Web Hata Nedenleri ve Açıklamaları (AppErrorAttributes) Testleri")
class AppErrorAttributesTest {

    private AppErrorAttributes errorAttributes;
    private MockHttpServletRequest servletRequest;
    private ServletWebRequest webRequest;

    @BeforeEach
    void setUp() {
        errorAttributes = new AppErrorAttributes();
        servletRequest = new MockHttpServletRequest();
        webRequest = new ServletWebRequest(servletRequest);
    }

    @Test
    @DisplayName("NoSuchElementException durumunda 404, Not Found ve detaylı Türkçe arama mesajı atanır")
    void testNoSuchElementException() {
        servletRequest.setAttribute("jakarta.servlet.error.status_code", 500);
        servletRequest.setAttribute("jakarta.servlet.error.exception", new NoSuchElementException("ID 105 kayıt bulunamadı"));

        Map<String, Object> attrs = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());

        assertThat(attrs.get("status")).isEqualTo(404);
        assertThat(attrs.get("error")).isEqualTo("Not Found");
        assertThat((String) attrs.get("message")).contains("ID 105 kayıt bulunamadı");
        assertThat(attrs.get("cause")).isEqualTo("ID 105 kayıt bulunamadı");
    }

    @Test
    @DisplayName("AccessDeniedException durumunda 403, Forbidden ve yetki uyarısı atanır")
    void testAccessDeniedException() {
        servletRequest.setAttribute("jakarta.servlet.error.status_code", 403);
        servletRequest.setAttribute("jakarta.servlet.error.exception", new AccessDeniedException("Yönetici yetkisi yok"));

        Map<String, Object> attrs = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());

        assertThat(attrs.get("status")).isEqualTo(403);
        assertThat(attrs.get("error")).isEqualTo("Forbidden");
        assertThat((String) attrs.get("message")).contains("yetkiniz bulunmamaktadır");
    }

    @Test
    @DisplayName("AuthenticationException durumunda 401, Unauthorized ve oturum açma uyarısı atanır")
    void testAuthenticationException() {
        servletRequest.setAttribute("jakarta.servlet.error.status_code", 401);
        servletRequest.setAttribute("jakarta.servlet.error.exception", new BadCredentialsException("Şifre yanlış"));

        Map<String, Object> attrs = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());

        assertThat(attrs.get("status")).isEqualTo(401);
        assertThat(attrs.get("error")).isEqualTo("Unauthorized");
        assertThat((String) attrs.get("message")).contains("oturum açmanız gerekmektedir");
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException durumunda 413, dosya sınırı uyarısı atanır")
    void testMaxUploadSizeExceededException() {
        servletRequest.setAttribute("jakarta.servlet.error.status_code", 500);
        servletRequest.setAttribute("jakarta.servlet.error.exception", new MaxUploadSizeExceededException(10485760));

        Map<String, Object> attrs = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());

        assertThat(attrs.get("status")).isEqualTo(413);
        assertThat(attrs.get("error")).isEqualTo("Payload Too Large");
        assertThat((String) attrs.get("message")).contains("5MB");
    }

    @Test
    @DisplayName("İstisna yokken varsayılan durum koduna göre açık Türkçe mesaj sağlanır")
    void testStatusWithoutException() {
        servletRequest.setAttribute("jakarta.servlet.error.status_code", 404);

        Map<String, Object> attrs = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());

        assertThat(attrs.get("status")).isEqualTo(404);
        assertThat((String) attrs.get("message")).contains("Aradığınız sayfa veya kayıt bulunamadı");
    }
}
