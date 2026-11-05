package app.kitappla.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Hata Yönetimi ve Geri Bildirim API Sözleşme Testleri")
class ApiErrorContractTest extends ContractTestBase {

    @Test
    @DisplayName("Oturumsuz istek - Korumalı uca istek atıldığında 401 ve standart JSON hata döner (boş gövde dönmez)")
    void unauthenticatedRequest_returns401WithConsistentJson() throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertApiError(res, 401, "Kimlik doğrulama başarısız", "UNAUTHORIZED");
    }

    @Test
    @DisplayName("Oturumsuz POST isteği - 401 döner ve mobil uygulamanın parse edebileceği hem error hem message alanlarını içerir")
    void unauthenticatedPostRequest_returns401WithConsistentJson() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CsrfInfo csrf = fetchCsrf(session);
        MvcResult res = mvc.perform(post("/api/v1/donations")
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Deneme\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertApiError(res, 401, "Kimlik doğrulama başarısız", "UNAUTHORIZED");
    }

    @Test
    @DisplayName("Yetkisiz rol (Normal üye Admin ucuna istek atarsa) - 403 FORBIDDEN ve standart JSON döner")
    void forbiddenRole_returns403WithConsistentJson() throws Exception {
        AuthUser user = registerNewUser("Normal Üye", "role-test");

        MvcResult res = apiGet("/api/v1/admin/stats", user.session());
        assertApiError(res, 403, "Bu işlem için yetkiniz bulunmuyor", "FORBIDDEN");
    }

    @Test
    @DisplayName("E-posta doğrulanmamış üye işlem yapmaya çalıştığında - 403 ve EMAIL_NOT_VERIFIED kodu döner")
    void unverifiedEmail_returns403WithEmailNotVerifiedCode() throws Exception {
        AuthUser user = registerNewUser("Onaysız Üye", "unverified");
        userRepository.findById(user.id()).ifPresent(u -> {
            u.setEmailVerified(false);
            userRepository.save(u);
        });

        Map<String, Object> body = Map.of(
                "title", "Nutuk",
                "author", "Mustafa Kemal Atatürk",
                "quantity", 1,
                "targetLevel", "HEPSI",
                "source", "OWN"
        );

        MvcResult res = apiPost("/api/v1/donations", body, user.session());
        assertApiError(res, 403, "e-posta adresini doğrulamalısın", "EMAIL_NOT_VERIFIED");
    }

    @Test
    @DisplayName("Var olmayan kaynak - 404 NOT_FOUND ve açık hata mesajı döner (400 veya 500 dönmez)")
    void nonExistentResource_returns404() throws Exception {
        MvcResult res = apiGet("/api/v1/donations/99999999", null);
        assertApiError(res, 404, "Bağış bulunamadı", null);
    }

    @Test
    @DisplayName("Bozuk JSON formatı - 400 BAD_REQUEST ve INVALID_JSON kodu döner")
    void invalidJsonBody_returns400WithConsistentError() throws Exception {
        AuthUser user = registerNewUser("JSON Test", "json-test");
        CsrfInfo csrf = fetchCsrf(user.session());

        MvcResult res = mvc.perform(post("/api/v1/donations")
                        .session(user.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json-body..."))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertApiError(res, 400, "Geçersiz istek gövdesi", "INVALID_JSON");
    }

    @Test
    @DisplayName("Desteklenmeyen HTTP Metodu - 405 METHOD_NOT_ALLOWED döner")
    void unsupportedMethod_returns405() throws Exception {
        AuthUser user = registerAndVerifyUser("Metot Test", "method-test");
        CsrfInfo csrf = fetchCsrf(user.session());
        MvcResult res = mvc.perform(delete("/api/v1/features")
                        .session(user.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();

        assertApiError(res, 405, "Bu HTTP yöntemi desteklenmiyor", "METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("Gerekli form alanı eksik olduğunda - 400 BAD_REQUEST ve VALIDATION_ERROR kodu döner")
    void missingRequiredField_returns400WithValidationError() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CsrfInfo csrf = fetchCsrf(session);
        Map<String, String> body = Map.of("email", "deneme@test.local"); // password eksik

        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertApiError(res, 400, null, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("Tip uyuşmazlığı parametre hatası - 400 BAD_REQUEST ve TYPE_MISMATCH kodu döner")
    void typeMismatch_returns400WithTypeMismatchCode() throws Exception {
        MvcResult res = apiGet("/api/v1/donations/gecersiz-sayisal-id", null);
        assertApiError(res, 400, "Geçersiz parametre değeri", "TYPE_MISMATCH");
    }

    @Test
    @DisplayName("Desteklenmeyen Media Type - 415 UNSUPPORTED_MEDIA_TYPE döner")
    void unsupportedMediaType_returns415() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CsrfInfo csrf = fetchCsrf(session);

        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("duz-metin-girisi"))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();

        assertApiError(res, 415, "Desteklenmeyen içerik türü", "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("Doğrulama hatasında alan bazlı detaylar (details) içeren zengin hata döner")
    void validationError_includesFieldLevelDetails() throws Exception {
        MockHttpSession session = new MockHttpSession();
        CsrfInfo csrf = fetchCsrf(session);
        Map<String, String> body = Map.of(); // boş gövde

        MvcResult res = mvc.perform(post("/api/v1/auth/register")
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertApiError(res, 400, null, "VALIDATION_ERROR");
        JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.has("details")).isTrue();
        assertThat(json.get("details").isObject()).isTrue();
    }

    @Test
    @DisplayName("Var olmayan API uç noktası - 404 NOT_FOUND döner")
    void nonExistentEndpoint_returns404() throws Exception {
        AuthUser user = registerNewUser("Gezgin", "api-404");
        MvcResult res = apiGet("/api/v1/olmayan-bir-uc-nokta-adresi", user.session());
        assertApiError(res, 404, null, "NOT_FOUND");
    }
}
