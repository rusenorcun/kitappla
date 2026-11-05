package app.kitappla.service;

import app.kitappla.domain.School;
import app.kitappla.domain.TokenType;
import app.kitappla.domain.User;
import app.kitappla.mail.MailService;
import app.kitappla.repo.UserRepository;
import app.kitappla.security.AppUserDetails;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Hesap e-postası doğrulama: kayıt postası, tek tıkla onay, işlem kısıtı ve mobil dönüş bağlantıları. */
@SpringBootTest(properties = {
        "kitappla.mail.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:account-verification;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountEmailVerificationTest {

    @Autowired UserService service;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;
    @MockBean JavaMailSender sender;
    @SpyBean MailService mail;

    @BeforeEach
    void prepareMail() {
        mail.clearOutbox();
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private static String email() {
        return "uye-" + UUID.randomUUID() + "@test.local";
    }

    private User register(String email) {
        return service.register("Üye", email, "sifre123", null, null,
                School.ATATURK_UNIVERSITESI, false, null, null, null);
    }

    /** Son iletideki bağlantı (HTML içinde &amp; olarak kaçışlı gelir). */
    private String lastLink(String to) {
        var message = mail.outbox().getLast();
        assertThat(message.to()).isEqualTo(to);
        var m = Pattern.compile("href=\"(https?://[^\"]*token=[^\"]+)\"").matcher(message.html());
        assertThat(m.find()).isTrue();
        return m.group(1).replace("&amp;", "&");
    }

    private static String token(String link) {
        var m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(link);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void registrationSendsLinkAndClickVerifiesWithoutLogin() throws Exception {
        String email = email();
        User u = register(email);
        assertThat(u.isEmailVerified()).isFalse();
        assertThat(u.isEmailVerificationSent()).isTrue();
        assertThat(service.registrationMessage(u)).contains(email);

        String link = lastLink(email);
        assertThat(link).contains("/eposta-dogrula?token=").doesNotContain("uygulama");
        String token = token(link);
        assertThat(tokens.verify(token, TokenType.ACCOUNT_VERIFY)).isPresent();

        mvc.perform(get("/eposta-dogrula").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hesabın doğrulandı")))
                .andExpect(content().string(not(containsString("kitappla://"))));
        assertThat(users.findById(u.getId()).orElseThrow().isEmailVerified()).isTrue();

        // İkinci tıklama (ya da posta tarayıcısının önceden açması) hata göstermez
        mvc.perform(get("/eposta-dogrula").param("token", token))
                .andExpect(content().string(containsString("zaten doğrulanmış")));
        mvc.perform(get("/eposta-dogrula").param("token", "gecersiz"))
                .andExpect(content().string(containsString("Bağlantı geçersiz")));
    }

    @Test
    void unverifiedMemberCanBrowseButCannotAct() throws Exception {
        User u = register(email());
        var principal = new AppUserDetails(u);

        mvc.perform(get("/panom").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/hesap-dogrulama")));

        mvc.perform(post("/api/v1/requests").with(user(principal)).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(post("/bagis/yeni").with(user(principal)).with(csrf()))
                .andExpect(redirectedUrl("/hesap-dogrulama"));

        mvc.perform(post("/bagis/yeni").with(user(principal)).with(csrf()).header("HX-Request", "true"))
                .andExpect(header().string("HX-Redirect", "/hesap-dogrulama"));

        // Hesabın kendisiyle ilgili uçlar açık
        mvc.perform(post("/profil/sifre").with(user(principal)).with(csrf()))
                .andExpect(redirectedUrl("/profil"));

        mvc.perform(get("/hesap-dogrulama").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(u.getEmail())));
    }

    @Test
    void verifiedMemberIsNotBlocked() throws Exception {
        User u = register(email());
        service.confirmAccountEmail(token(lastLink(u.getEmail())));
        User fresh = users.findById(u.getId()).orElseThrow();
        mvc.perform(get("/hesap-dogrulama").with(user(new AppUserDetails(fresh))))
                .andExpect(redirectedUrl("/panom"));
        mvc.perform(post("/api/v1/requests").with(user(new AppUserDetails(fresh))).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(not("EMAIL_NOT_VERIFIED")))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String registerFromApp(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json")
                        .content("{\"name\":\"Mobil\",\"email\":\"" + email + "\",\"password\":\"sifre123\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Email-Verification-Sent", "true"))
                .andExpect(jsonPath("$.user.emailVerified").value(false));
        return lastLink(email);
    }

    private void verifyFromApp(String token, String expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/auth/verify-email").with(csrf()).contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    /** Uygulamadan kayıt: bağlantı App Link yolunda, onayı oturumsuz olarak uygulama yapar. */
    @Test
    void mobileRegistrationLinkOpensTheAppWhichVerifiesIt() throws Exception {
        String email = email();
        String link = registerFromApp(email);
        assertThat(link).contains("/uygulamada-ac/eposta-dogrula?token=").doesNotContain("uygulama=1");

        verifyFromApp(token(link), "DOGRULANDI");
        assertThat(users.findByEmail(email).orElseThrow().isEmailVerified()).isTrue();

        // İkinci dokunuş hata göstermez; bozuk jeton "geçersiz" sonucudur, hata değil
        verifyFromApp(token(link), "ZATEN_DOGRULANMIS");
        verifyFromApp("gecersiz", "GECERSIZ");
        mvc.perform(post("/api/v1/auth/verify-email").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** Uygulama yüklü değilse aynı bağlantı tarayıcıda açılır: sayfa onaylar ve uygulamaya dönmeyi önerir. */
    @Test
    void mobileRegistrationLinkStillVerifiesInTheBrowser() throws Exception {
        String email = email();
        String link = registerFromApp(email);
        mvc.perform(get("/uygulamada-ac/eposta-dogrula").param("token", token(link)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hesabın doğrulandı")))
                .andExpect(content().string(containsString("kitappla://eposta-dogrulandi")));
        assertThat(users.findByEmail(email).orElseThrow().isEmailVerified()).isTrue();
    }

    /** App Link'ten önce gönderilmiş bağlantılar ({@code &uygulama=1}) çalışmaya devam eder. */
    @Test
    void olderMobileLinksStillReturnToApp() throws Exception {
        User u = register(email());
        String token = token(lastLink(u.getEmail()));
        mvc.perform(get("/eposta-dogrula").param("token", token).param("uygulama", "1"))
                .andExpect(content().string(containsString("Hesabın doğrulandı")))
                .andExpect(content().string(containsString("kitappla://eposta-dogrulandi")));
    }

    @Test
    void mobileResendAndPasswordResetLinksOpenTheApp() throws Exception {
        User u = register(email());
        mvc.perform(post("/api/v1/me/email-verification").with(user(new AppUserDetails(u))).with(csrf()))
                .andExpect(status().isAccepted());
        assertThat(lastLink(u.getEmail())).contains("/uygulamada-ac/eposta-dogrula?token=");

        mvc.perform(post("/api/v1/auth/forgot-password").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"" + u.getEmail() + "\"}"))
                .andExpect(status().isAccepted());
        String link = lastLink(u.getEmail());
        assertThat(link).contains("/uygulamada-ac/sifre-sifirla?token=").doesNotContain("uygulama=1");
        String token = token(link);
        // Tarayıcıda açılırsa: uygulamada açmayı önerir
        mvc.perform(get("/uygulamada-ac/sifre-sifirla").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kitappla://sifre-sifirla?token=" + token)))
                // web formu yedek olarak durur ve jeton harcanmaz
                .andExpect(content().string(containsString("name=\"newPassword\"")));
        assertThat(tokens.verify(token, TokenType.PASSWORD_RESET)).isPresent();
        // Eski biçimli bağlantı da aynı sayfayı açar
        mvc.perform(get("/sifre-sifirla").param("token", token).param("uygulama", "1"))
                .andExpect(content().string(containsString("kitappla://sifre-sifirla?token=" + token)));

        // Web'den istenen sıfırlama uygulamaya yönlendirmez
        mvc.perform(post("/sifremi-unuttum").with(csrf()).param("email", u.getEmail()));
        assertThat(lastLink(u.getEmail())).doesNotContain("uygulama");
    }

    @Test
    void mobileStudentEmailLinkOpensAppBridge() throws Exception {
        User u = register(email());
        u.setEmailVerified(true);
        users.save(u);
        String school = UUID.randomUUID() + "@school.edu.tr";
        mvc.perform(post("/api/v1/me/student").with(user(new AppUserDetails(u))).with(csrf())
                        .contentType("application/json").content("{\"email\":\"" + school + "\"}"))
                .andExpect(status().isOk());
        String link = lastLink(school);
        assertThat(link).contains("/uygulamada-ac/okul-eposta?token=");
        mvc.perform(get("/uygulamada-ac/okul-eposta").param("token", token(link)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kitappla://profil/ogrenci?token=" + token(link))));
    }

    @Test
    void mailDisabledRegistersVerifiedAccount() {
        doReturn(false).when(mail).isEnabled();
        User u = register(email());
        assertThat(u.isEmailVerified()).isTrue();
        assertThat(mail.outbox()).isEmpty();
    }
}
