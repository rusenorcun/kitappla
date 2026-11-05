package app.kitappla.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Web Sitesi (Thymeleaf/HTML) Girdi-Çıktı Sözleşme Testleri")
class WebContractTest extends ContractTestBase {

    @Test
    @DisplayName("GET / - Ana sayfa 200 OK ve navigasyon bağlantılarını içeren HTML döner")
    void homePage_returns200AndBrandHtml() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("KitAppLa")))
                .andExpect(content().string(containsString("/kesfet")))
                .andExpect(content().string(containsString("/login")))
                .andExpect(content().string(containsString("/register")));
    }

    @Test
    @DisplayName("GET /kesfet - Keşfet sayfası 200 OK ve arama/filtreleme formunu döner")
    void discoverPage_returns200AndSearchForm() throws Exception {
        mvc.perform(get("/kesfet"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Bağışları keşfet")));
    }

    @Test
    @DisplayName("GET Statik Bilgi Sayfaları - /sss, /kurallar, /gizlilik, /iletisim 200 OK döner")
    void staticPublicPages_return200() throws Exception {
        String[] pages = {"/sss", "/kurallar", "/gizlilik", "/iletisim"};
        for (String p : pages) {
            mvc.perform(get(p))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"));
        }
    }

    @Test
    @DisplayName("GET /saglik - Sağlık kontrolü 200 OK döner")
    void healthCheck_returns200() throws Exception {
        mvc.perform(get("/saglik"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /login - Giriş sayfası 200 OK ve form alanlarını döner")
    void loginPage_returnsForm() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"password\"")));
    }

    @Test
    @DisplayName("POST /login - Doğru bilgiler girildiğinde oturum açar ve yönlendirir (302)")
    void webLogin_validCredentials_redirects() throws Exception {
        AuthUser user = registerNewUser("Web Giriş Üyesi", "web-login");

        mvc.perform(post("/login")
                        .with(csrf())
                        .param("email", user.email())
                        .param("password", user.password()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/panom"));
    }

    @Test
    @DisplayName("POST /login - Yanlış şifre girildiğinde /login?error adresine yönlendirir (302)")
    void webLogin_invalidCredentials_redirectsToError() throws Exception {
        AuthUser user = registerNewUser("Web Hatalı Giriş", "web-fail");

        mvc.perform(post("/login")
                        .with(csrf())
                        .param("email", user.email())
                        .param("password", "yanlis_sifre"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    @DisplayName("POST /register - Form başarıyla doldurulduğunda kayıt tamamlanır ve yönlendirilir (302)")
    void webRegister_success_redirectsToLogin() throws Exception {
        String email = "web-reg-" + UUID.randomUUID() + "@test.local";

        mvc.perform(post("/register")
                        .with(csrf())
                        .param("name", "Web Yeni Üye")
                        .param("email", email)
                        .param("password", "sifre12345")
                        .param("school", "ATATURK_UNIVERSITESI")
                        .param("phone", "05559876543"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?kayit"));
    }

    @Test
    @DisplayName("POST /register - Aynı e-posta ile tekrar kayıt olunduğunda form hata mesajı ile tekrar sunulur")
    void webRegister_duplicateEmail_showsErrorMessage() throws Exception {
        AuthUser user = registerNewUser("Mevcut Kullanıcı", "mevcut");

        mvc.perform(post("/register")
                        .with(csrf())
                        .param("name", "Tekrar Kayıt")
                        .param("email", user.email())
                        .param("password", "sifre12345")
                        .param("school", "ATATURK_UNIVERSITESI")
                        .param("phone", "05559876543"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeExists("error"))
                .andExpect(content().string(containsString("zaten kayıtlı")));
    }

    @Test
    @DisplayName("Korumalı Sayfalar: Oturumsuz erişimde /login sayfasına yönlendirir (302)")
    void protectedPages_unauthenticated_redirectsToLogin() throws Exception {
        mvc.perform(get("/profil"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        mvc.perform(get("/bagis/yeni"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("GET /profil - Oturum açmış kullanıcı profil sayfasını 200 ile görüntüler")
    void profilePage_authenticated_returns200() throws Exception {
        AuthUser user = registerNewUser("Profil Sayfası Test", "web-profile");

        mvc.perform(get("/profil").session(user.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(user.name())));
    }

    @Test
    @DisplayName("GET /admin - Normal kullanıcı için 403, yönetici için 200 döner")
    void adminPage_authorization() throws Exception {
        AuthUser normal = registerNewUser("Normal Ziyaretçi", "norm-adm");
        mvc.perform(get("/admin").session(normal.session()))
                .andExpect(status().isForbidden());

        MockHttpSession adminSession = loginUser("admin@test.local", "admin123");
        mvc.perform(get("/admin").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Yönetim")));
    }

    @Test
    @DisplayName("GET /olmayan-sayfa - Web arayüzünde markalı 404 hata sayfası görüntülenir")
    void notFoundWebPage_returnsBranded404() throws Exception {
        mvc.perform(get("/error")
                        .requestAttr("jakarta.servlet.error.status_code", 404)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("404")))
                .andExpect(content().string(containsString("KitAppLa")));
    }
}
