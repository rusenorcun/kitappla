package app.kitappla.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Profil, Hesap ve Kota API Sözleşme Testleri")
class ProfileAndAccountContractTest extends ContractTestBase {

    @Test
    @DisplayName("GET /api/v1/me - Oturum açmış kullanıcının profilini ve kotasını döner")
    void getProfile_returnsMeDto() throws Exception {
        AuthUser user = registerNewUser("Profil Sahibi", "profile-me");

        MvcResult res = apiGet("/api/v1/me", user.session());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.has("user")).isTrue();
        assertThat(json.has("quota")).isTrue();

        JsonNode u = json.get("user");
        assertThat(u.get("name").asText()).isEqualTo(user.name());
        assertThat(u.get("email").asText()).isEqualTo(user.email());
        assertThat(u.has("emailVerified")).isTrue();
    }

    @Test
    @DisplayName("PUT /api/v1/me - Ad, telefon ve okul bilgilerini günceller ve güncel UserDto döner")
    void updateProfile_success_returnsUpdatedUserDto() throws Exception {
        AuthUser user = registerNewUser("Eski İsim", "update-me");

        Map<String, Object> body = Map.of(
                "name", "Yeni İsim",
                "phone", "05551112233",
                "school", "ERZURUM_TEKNIK_UNIVERSITESI"
        );

        MvcResult res = apiPut("/api/v1/me", body, user.session());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode json = mapper.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(json.get("name").asText()).isEqualTo("Yeni İsim");
        assertThat(json.get("phone").asText()).isEqualTo("05551112233");
    }

    @Test
    @DisplayName("POST /api/v1/me/password - Doğru mevcut şifreyle şifreyi değiştirir, mevcut oturumu korur ve diğer oturumları düşürür")
    void changePassword_success_returns204() throws Exception {
        AuthUser user = registerNewUser("Şifre Değişen", "chg-pwd");
        MockHttpSession otherSession = loginUser(user.email(), user.password());

        Map<String, String> body = Map.of(
                "currentPassword", user.password(),
                "newPassword", "yeniSifre123",
                "confirmPassword", "yeniSifre123"
        );

        MvcResult res = apiPost("/api/v1/me/password", body, user.session());
        assertThat(res.getResponse().getStatus()).isEqualTo(204);

        // Mevcut oturum açık kalmalıdır
        MvcResult meRes = apiGet("/api/v1/me", user.session());
        assertThat(meRes.getResponse().getStatus()).isEqualTo(200);

        // Diğer cihaz/tarayıcı oturumu düşürülmelidir
        MvcResult otherRes = apiGet("/api/v1/me", otherSession);
        assertThat(otherRes.getResponse().getStatus()).isEqualTo(401);

        // Yeni şifreyle giriş yapılabilmelidir
        MockHttpSession newSession = loginUser(user.email(), "yeniSifre123");
        assertThat(newSession).isNotNull();
    }

    @Test
    @DisplayName("POST /api/v1/me/password - Yanlış mevcut şifrede 400 ve açık hata döner")
    void changePassword_wrongCurrent_returns400() throws Exception {
        AuthUser user = registerNewUser("Hatalı Şifre", "wrong-pwd");

        Map<String, String> body = Map.of(
                "currentPassword", "tamamen_yanlis",
                "newPassword", "yeniSifre123",
                "confirmPassword", "yeniSifre123"
        );

        MvcResult res = apiPost("/api/v1/me/password", body, user.session());
        assertApiError(res, 400, "Mevcut şifren hatalı", null);
    }

    @Test
    @DisplayName("POST /api/v1/me/email-verification - Posta servisi kapalıyken 400 ve açık hata mesajı döner")
    void resendEmailVerification_mailDisabled_returns400WithConsistentError() throws Exception {
        AuthUser user = registerNewUser("Doğrulama İsteyen", "resend-mail");
        userRepository.findById(user.id()).ifPresent(u -> {
            u.setEmailVerified(false);
            userRepository.save(u);
        });

        MvcResult res = apiPost("/api/v1/me/email-verification", null, user.session());
        assertApiError(res, 400, "E-posta gönderimi şu an kullanılamıyor", null);
    }

    @Test
    @DisplayName("POST /api/v1/me/student - .edu.tr ile bitmeyen okul adresi girildiğinde 400 döner")
    void verifyStudentEmail_nonEdu_returns400() throws Exception {
        AuthUser user = registerAndVerifyUser("Öğrenci Adayı", "stu-cand");

        Map<String, String> body = Map.of("email", "ogrenci@gmail.com");
        MvcResult res = apiPost("/api/v1/me/student", body, user.session());

        assertApiError(res, 400, ".edu.tr ile bitmelidir", null);
    }

    @Test
    @DisplayName("GET /api/v1/quota - Kullanıcının güncel kota bilgilerini döner")
    void getQuota_returnsQuotaDto() throws Exception {
        AuthUser user = registerNewUser("Kota Üyesi", "quota-usr");

        MvcResult res = apiGet("/api/v1/quota", user.session());
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.has("tier")).isTrue();
        assertThat(json.has("weeklyRemaining")).isTrue();
        assertThat(json.has("monthlyRemaining")).isTrue();
    }
}
