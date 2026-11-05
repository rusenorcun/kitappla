package app.kitappla.web;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Web sayfalarında (Thymeleaf) ve Spring Boot hata mekanizmasında her hatanın nedenini
 * açık, anlaşılır ve Türkçe olarak model parametrelerine aktarır.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AppErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attrs = super.getErrorAttributes(webRequest, options);
        Throwable error = getError(webRequest);
        Integer status = (Integer) attrs.get("status");

        if (error != null) {
            if (error instanceof NoSuchElementException) {
                status = 404;
                attrs.put("status", 404);
                attrs.put("error", "Not Found");
            } else if (error instanceof AccessDeniedException) {
                status = 403;
                attrs.put("status", 403);
                attrs.put("error", "Forbidden");
            } else if (error instanceof AuthenticationException) {
                status = 401;
                attrs.put("status", 401);
                attrs.put("error", "Unauthorized");
            } else if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
                status = 400;
                attrs.put("status", 400);
                attrs.put("error", "Bad Request");
            } else if (error instanceof MaxUploadSizeExceededException) {
                status = 413;
                attrs.put("status", 413);
                attrs.put("error", "Payload Too Large");
            }

            String customMessage = resolveErrorMessage(error, status);
            attrs.put("message", customMessage);
            attrs.put("cause", error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        } else {
            String currentMessage = (String) attrs.get("message");
            if (currentMessage == null || currentMessage.isBlank() || "No message available".equals(currentMessage)) {
                attrs.put("message", getDefaultStatusMessage(status));
            }
        }
        return attrs;
    }

    private String resolveErrorMessage(Throwable error, Integer status) {
        if (error instanceof NoSuchElementException) {
            return (error.getMessage() != null && !error.getMessage().isBlank())
                    ? "Aradığınız kayıt veya sayfa bulunamadı: " + error.getMessage()
                    : "Aradığınız kayıt veya sayfa sistemde bulunamadı.";
        }
        if (error instanceof AccessDeniedException) {
            return "Bu sayfaya veya işleme erişim yetkiniz bulunmamaktadır.";
        }
        if (error instanceof AuthenticationException) {
            return "Bu işlem için oturum açmanız gerekmektedir.";
        }
        if (error instanceof MaxUploadSizeExceededException) {
            return "Yüklenen dosya boyutu izin verilen sınırı aşıyor (Maksimum boyut: 5MB).";
        }
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return error.getMessage() != null && !error.getMessage().isBlank()
                    ? error.getMessage()
                    : "Geçersiz istek parametresi veya durumu.";
        }
        return getDefaultStatusMessage(status);
    }

    private String getDefaultStatusMessage(Integer status) {
        if (status == null) return "Bir şeyler ters gitti.";
        return switch (status) {
            case 400 -> "İsteğiniz geçersiz ya da eksik bilgi içeriyor.";
            case 401 -> "Lütfen önce giriş yapın.";
            case 403 -> "Bu sayfaya erişim yetkiniz bulunmuyor.";
            case 404 -> "Aradığınız sayfa veya kayıt bulunamadı.";
            case 405 -> "Bu işlem yöntemi (HTTP metodu) desteklenmiyor.";
            case 413 -> "Yüklenen dosya çok büyük (Sınır: 5MB).";
            default -> "İşlemi tamamlayamadık. Lütfen tekrar deneyin.";
        };
    }
}
