package app.kitappla.service;

import app.kitappla.config.Features;
import app.kitappla.domain.School;
import app.kitappla.domain.SchoolLevel;
import app.kitappla.domain.StudentStatus;
import app.kitappla.domain.User;
import app.kitappla.domain.AuthToken;
import app.kitappla.domain.TokenType;
import app.kitappla.mail.MailService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import app.kitappla.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
public class UserService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final Features features;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final app.kitappla.security.UserSessionService userSessions;
    private final TokenService tokens;
    private final MailService mail;
    private final NotificationService notifications;

    @PersistenceContext
    private EntityManager entityManager;

    public UserService(Features features, UserRepository users, PasswordEncoder encoder,
                       app.kitappla.security.UserSessionService userSessions,
                       TokenService tokens, MailService mail, NotificationService notifications) {
        this.notifications = notifications;
        this.features = features;
        this.users = users;
        this.encoder = encoder;
        this.userSessions = userSessions;
        this.tokens = tokens;
        this.mail = mail;
    }

    public static String normalizeEmail(String e) {
        return e == null ? null : e.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean isStudentEmail(String email) {
        String e = normalizeEmail(email);
        return e != null && e.length() <= 254
                && e.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+edu\\.tr");
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * Üye kaydı; belge alanları verilmişse öğrenci doğrulaması PENDING başlar.
     * documentPath dosya kaydedildikten sonra controller tarafından verilir (yoksa null).
     */
    /** Hesap doğrulama bağlantısının sonucu. */
    public enum HesapDogrulama { DOGRULANDI, ZATEN_DOGRULANMIS, GECERSIZ }

    @Transactional
    public User register(String name, String email, String rawPassword, String address, String phone,
                         School school,
                         boolean wantsStudent, SchoolLevel level, String documentNo, String documentPath) {
        return register(name, email, rawPassword, address, phone, school,
                wantsStudent, level, documentNo, documentPath, false);
    }

    /**
     * @param mobil kayıt mobil uygulamadan geldiyse true: doğrulama bağlantısı uygulamanın App Link
     *              yolundadır ({@code /uygulamada-ac/eposta-dogrula}); uygulama yüklüyse tarayıcıya
     *              uğramadan açılır ve onayı uygulama yapar
     */
    @Transactional
    public User register(String name, String email, String rawPassword, String address, String phone,
                         School school,
                         boolean wantsStudent, SchoolLevel level, String documentNo, String documentPath,
                         boolean mobil) {
        name = clean(name, 120);
        email = normalizeEmail(email);
        address = clean(address, 500);
        phone = clean(phone, 40);
        documentNo = clean(documentNo, 100);

        if (name == null || email == null || rawPassword == null || rawPassword.isBlank())
            throw new IllegalArgumentException("Ad, e-posta ve şifre zorunludur.");
        if (!EMAIL.matcher(email).matches())
            throw new IllegalArgumentException("Geçerli bir e-posta adresi girin.");
        if (rawPassword.length() < 6)
            throw new IllegalArgumentException("Şifre en az 6 karakter olmalıdır.");
        if (users.existsByEmail(email))
            throw new IllegalArgumentException("Bu e-posta zaten kayıtlı.");
        if (isStudentEmail(email) && !okulAdresiniAyir(email, null))
            throw new IllegalArgumentException("Bu okul adresi başka bir hesapta öğrenci doğrulaması için kullanılıyor.");

        if (wantsStudent) {
            if (level == null) throw new IllegalArgumentException("Okul seviyesi seçilmelidir.");
            if (documentNo == null) throw new IllegalArgumentException("Öğrenci belge numarası zorunludur.");
            if (documentPath == null) throw new IllegalArgumentException("Öğrenci belgesi yüklenmelidir.");
            // Adres yalnızca kargo akışında gerekir (kampüs içi teslimde istenmez)
            if (features.isAddress() && address == null)
                throw new IllegalArgumentException("Öğrenci doğrulaması için teslimat adresi zorunludur.");
            if (users.existsByDocumentNo(documentNo))
                throw new IllegalArgumentException("Bu belge numarası ile zaten bir kayıt var.");
        }

        User u = new User();
        u.setName(name);
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setAddress(address);
        u.setPhone(phone);
        u.setSchool(school);

        if (isStudentEmail(email)) {
            u.setStudentEmail(email);
            u.setStudentStatus(StudentStatus.PENDING);
            u.setSchoolLevel(SchoolLevel.UNIVERSITE);
        }

        if (wantsStudent) {
            u.setStudentStatus(StudentStatus.PENDING);
            u.setSchoolLevel(level);
            u.setDocumentNo(documentNo);
            u.setDocumentPath(documentPath);
        }
        // Posta kapalıyken (yerel geliştirme) bağlantı hiç gelmeyeceği için hesap doğrulanmış açılır;
        // aksi hâlde yerelde kayıt olan hiç kimse işlem yapamazdı.
        u.setEmailVerified(!mail.isEnabled());
        users.save(u);
        if (!u.isEmailVerified()) {
            // Okul adresiyle kayıtta ayrı bir öğrenci postası gönderilmez: aynı adrese giden hesap
            // doğrulama bağlantısı öğrenci doğrulamasını da tamamlar (bkz. confirmAccountEmail).
            sendAccountVerification(u, mobil);
            u.setStudentVerificationSent(u.getStudentEmail() != null && u.isEmailVerificationSent());
        } else if (u.getStudentEmail() != null) {
            sendStudentVerification(u, mobil);
        }
        return u;
    }

    // ---------- Hesap e-postası doğrulama ----------

    /**
     * Giriş e-postasına doğrulama bağlantısı gönderir. Posta kapalıysa ya da saatlik sınır dolduysa
     * sessizce vazgeçer; sonuç {@link User#isEmailVerificationSent()} ile okunur.
     */
    private void sendAccountVerification(User user, boolean mobil) {
        user.setEmailVerificationSent(false);
        if (user.isEmailVerified() || !mail.isEnabled() || tokens.limitDoldu(user, TokenType.ACCOUNT_VERIFY)) return;
        String token = tokens.issue(user, TokenType.ACCOUNT_VERIFY);
        User alici = user;
        boolean okulAdresi = user.getEmail().equals(user.getStudentEmail());
        boolean sent = mail.send(user.getEmail(), "E-posta adresini doğrula", "hesap-dogrulama",
                Map.of("ad", user.getName(), "eposta", user.getEmail(), "ogrenci", okulAdresi,
                        "link", mail.baseUrl() + (mobil ? "/uygulamada-ac/eposta-dogrula?token=" : "/eposta-dogrula?token=") + token),
                () -> {
                    tokens.invalidate(alici, TokenType.ACCOUNT_VERIFY);
                    notifications.notify(alici, "hesap", "E-posta adresine (" + alici.getEmail() + ") doğrulama "
                            + "bağlantısı gönderilemedi. Doğrulama sayfasından yeniden isteyebilirsin.",
                            "/hesap-dogrulama");
                });
        user.setEmailVerificationSent(sent);
        if (!sent) tokens.invalidate(user, TokenType.ACCOUNT_VERIFY);
    }

    /** Oturumdaki üye doğrulama bağlantısını yeniden ister (web ya da mobil). */
    @Transactional
    public User resendAccountVerification(User user, boolean mobil) {
        User u = lockedUser(user);
        if (u.isEmailVerified()) throw new IllegalStateException("E-posta adresin zaten doğrulanmış.");
        if (!mail.isEnabled()) throw new IllegalStateException("E-posta gönderimi şu an kullanılamıyor. Daha sonra tekrar dene.");
        if (tokens.limitDoldu(u, TokenType.ACCOUNT_VERIFY))
            throw new IllegalStateException("Çok fazla istek gönderildi. Lütfen bir süre sonra tekrar dene.");
        sendAccountVerification(u, mobil);
        if (!u.isEmailVerificationSent())
            throw new IllegalStateException("Doğrulama e-postası gönderilemedi. Daha sonra tekrar dene.");
        return u;
    }

    /**
     * Bağlantıdaki jetonla hesabı doğrular; oturum gerektirmez. Bağlantıya tıklamak yeterlidir.
     * Adres okul adresiyse ve öğrenci doğrulaması bekliyorsa o da tamamlanır: bağlantı, adresin
     * sahibine gittiği için okul adresi doğrulamasıyla aynı kanıtı taşır.
     */
    @Transactional
    public HesapDogrulama confirmAccountEmail(String token) {
        AuthToken proof = tokens.verify(token, TokenType.ACCOUNT_VERIFY).orElse(null);
        if (proof == null) {
            // Aynı bağlantıya ikinci kez tıklanması (ya da posta tarayıcısının önceden açması)
            // hata gibi görünmesin.
            return tokens.find(token, TokenType.ACCOUNT_VERIFY)
                    .filter(t -> t.getUser().isEmailVerified())
                    .map(t -> HesapDogrulama.ZATEN_DOGRULANMIS)
                    .orElse(HesapDogrulama.GECERSIZ);
        }
        User u = entityManager.find(User.class, proof.getUser().getId(), LockModeType.PESSIMISTIC_WRITE);
        if (u == null || u.isBlocked() || !u.getEmail().equals(proof.getVerificationEmail()))
            return HesapDogrulama.GECERSIZ;

        tokens.consume(proof);
        u.setEmailVerified(true);
        if (u.getEmail().equals(u.getStudentEmail()) && isStudentEmail(u.getEmail())
                && u.getStudentStatus() == StudentStatus.PENDING) {
            u.setStudentStatus(StudentStatus.APPROVED);
            tokens.invalidate(u, TokenType.EMAIL_VERIFY);
        }
        users.save(u);
        return HesapDogrulama.DOGRULANDI;
    }

    /** Kayıttan sonra gösterilecek bilgi. */
    public String registrationMessage(User user) {
        if (user.isEmailVerified())
            return user.getStudentEmail() != null ? studentVerificationMessage(user) : null;
        if (!user.isEmailVerificationSent())
            return "Hesabın açıldı ama doğrulama e-postası gönderilemedi. Giriş yaptıktan sonra yeniden isteyebilirsin. "
                    + "E-postanı doğrulayana kadar bağış, istek, takas ve mesaj gibi işlemleri yapamazsın.";
        String ek = user.getEmail().equals(user.getStudentEmail()) ? " Aynı bağlantı öğrenci önceliğini de açar." : "";
        return user.getEmail() + " adresine doğrulama bağlantısı gönderdik; birkaç dakika içinde gelmezse gereksiz (spam) "
                + "klasörüne bak. Bağlantıya tıklayana kadar giriş yapabilirsin ama bağış, istek, takas ve mesaj gibi "
                + "işlemleri yapamazsın." + ek;
    }

    // ---------- Profil ----------

    /**
     * Ad, adres, telefon ve okulu günceller. Adres {@code null} gelirse (kampüs modunda
     * web formu ve mobil istemci adres göndermez) kayıttaki adres korunur; boş metin siler.
     */
    @Transactional
    public User updateProfile(User user, String name, String address, String phone, School school) {
        User u = users.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("Kullanıcı bulunamadı."));

        String n = clean(name, 120);
        if (n == null) throw new IllegalStateException("Ad boş olamaz.");
        u.setName(n);
        if (address != null) u.setAddress(clean(address, 500));
        u.setPhone(clean(phone, 40));
        if (school != null) u.setSchool(school);
        return users.save(u);
    }

    /** Mevcut şifre doğrulanarak yeni şifre atar; mevcut oturumu koruyup diğer oturumları düşürür. */
    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword, String confirmPassword) {
        User u = users.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("Kullanıcı bulunamadı."));

        if (currentPassword == null || !encoder.matches(currentPassword, u.getPasswordHash()))
            throw new IllegalStateException("Mevcut şifren hatalı.");
        if (newPassword == null || newPassword.length() < 6)
            throw new IllegalStateException("Yeni şifre en az 6 karakter olmalı.");
        if (!newPassword.equals(confirmPassword))
            throw new IllegalStateException("Yeni şifreler birbiriyle eşleşmiyor.");
        if (encoder.matches(newPassword, u.getPasswordHash()))
            throw new IllegalStateException("Yeni şifre eskisiyle aynı olamaz.");

        String newHash = encoder.encode(newPassword);
        u.setPasswordHash(newHash);
        users.save(u);
        user.setPasswordHash(newHash);

        String currentSessionId = null;
        var reqAttrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (reqAttrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
            var session = servletAttrs.getRequest().getSession(false);
            if (session != null) {
                currentSessionId = session.getId();
            }
        }

        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var auth = context.getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof app.kitappla.security.AppUserDetails details
                && details.getUser() != null && u.getId().equals(details.getUser().getId())) {
            details.getUser().setPasswordHash(newHash);
            var refreshed = new app.kitappla.security.AppUserDetails(u);
            var newAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    refreshed, auth.getCredentials(), refreshed.getAuthorities());
            newAuth.setDetails(auth.getDetails());
            context.setAuthentication(newAuth);
        }

        userSessions.expireOtherUserSessions(u.getId(), currentSessionId);

        mail.send(u.getEmail(), "Şifren değiştirildi", "sifre-degisti",
                Map.of("ad", u.getName()));
    }

    @Transactional
    public User verifyStudentEmail(User user, String eduEmail) {
        return verifyStudentEmail(user, eduEmail, false);
    }

    /** @param mobil istek mobil uygulamadan geldiyse true: bağlantı uygulamaya yönlendiren sayfayı açar */
    @Transactional
    public User verifyStudentEmail(User user, String eduEmail, boolean mobil) {
        User u = lockedUser(user);

        String e = normalizeEmail(eduEmail);
        if (e == null || e.isBlank() || !EMAIL.matcher(e).matches())
            throw new IllegalStateException("Geçerli bir e-posta adresi girin.");
        if (!isStudentEmail(e))
            throw new IllegalStateException(
                    "Adres .edu.tr ile bitmelidir. Okulunun verdiği e-posta adresini gir.");
        if (u.getStudentStatus() == StudentStatus.APPROVED)
            throw new IllegalStateException("Zaten onaylı bir öğrencisin.");
        if (!e.equals(u.getEmail()) && users.existsByEmail(e))
            throw new IllegalStateException("Bu adres başka bir hesabın giriş e-postası.");
        if (!e.equals(u.getStudentEmail()) && !okulAdresiniAyir(e, u.getId()))
            throw new IllegalStateException("Bu okul adresi başka bir hesapta kullanılıyor.");

        u.setStudentVerificationSent(false);
        boolean canSend = mail.isEnabled() && !tokens.limitDoldu(u, TokenType.EMAIL_VERIFY);
        if (!canSend && u.getStudentEmail() != null) return u;

        u.setStudentEmail(e);
        u.setStudentStatus(StudentStatus.PENDING);
        if (u.getSchoolLevel() == null) u.setSchoolLevel(SchoolLevel.UNIVERSITE);
        users.save(u);
        sendStudentVerification(u, mobil);
        return u;
    }

    /**
     * Okul adresini bu hesap için ayırır. Adres başka bir hesapta yalnızca <b>doğrulanmamış</b>
     * duruyorsa o talep düşürülür: aksi hâlde herhangi biri başkasının okul adresini profiline
     * yazıp (bağlantıyı hiç onaylayamasa da) adresin gerçek sahibinin o adresle kayıt olmasını
     * ve doğrulama yapmasını engelleyebiliyordu. Doğrulanmış (onaylı) adres korunur.
     *
     * @param benimId adresi isteyen hesap; yeni kayıtta null
     * @return adres bu hesap için kullanılabilir mi
     */
    private boolean okulAdresiniAyir(String eduEmail, Long benimId) {
        User sahibi = users.findByStudentEmail(eduEmail).orElse(null);
        if (sahibi == null || sahibi.getId().equals(benimId)) return true;
        if (sahibi.getStudentStatus() == StudentStatus.APPROVED) return false;

        sahibi.setStudentEmail(null);
        if (sahibi.getStudentStatus() == StudentStatus.PENDING && sahibi.getDocumentPath() == null)
            sahibi.setStudentStatus(StudentStatus.NONE);
        users.saveAndFlush(sahibi);   // benzersizlik kısıtı yeni sahibin yazımından önce boşalsın
        tokens.invalidate(sahibi, TokenType.EMAIL_VERIFY);
        return true;
    }

    private User lockedUser(User user) {
        User u = entityManager.find(User.class, user.getId(), LockModeType.PESSIMISTIC_WRITE);
        if (u == null || u.isBlocked()) throw new IllegalStateException("Kullanıcı bulunamadı.");
        return u;
    }

    private void sendStudentVerification(User user, boolean mobil) {
        user.setStudentVerificationSent(false);
        if (!mail.isEnabled() || tokens.limitDoldu(user, TokenType.EMAIL_VERIFY)) return;
        String token = tokens.issue(user, TokenType.EMAIL_VERIFY);
        // Teslim arka planda yapılır; başarısız olursa bağlantı geçersiz kılınır ve üye site içinden
        // bilgilendirilir (istek anında "gönderiliyor" denmişti, sessiz kalmamalı).
        User alici = user;
        String okulAdresi = user.getStudentEmail();
        boolean sent = mail.send(okulAdresi, "Okul e-postanı doğrula", "ogrenci-dogrulama",
                Map.of("ad", user.getName(), "eposta", okulAdresi,
                        "link", mail.baseUrl() + (mobil ? "/uygulamada-ac/okul-eposta?token=" : "/profil/ogrenci/eposta/onay?token=") + token),
                () -> {
                    tokens.invalidate(alici, TokenType.EMAIL_VERIFY);
                    notifications.notify(alici, "hesap", "Okul e-postana (" + okulAdresi + ") doğrulama "
                            + "bağlantısı gönderilemedi. Profilindeki öğrenci doğrulama sayfasından tekrar deneyebilirsin.",
                            "/profil/ogrenci");
                });
        user.setStudentVerificationSent(sent);
        if (!sent) tokens.invalidate(user, TokenType.EMAIL_VERIFY);
    }

    public String studentVerificationMessage(User user) {
        return user.isStudentVerificationSent()
                ? "Doğrulama bağlantısı okul adresine gönderiliyor; birkaç dakika içinde gelmezse gereksiz (spam) klasörüne bak. Bağlantıyı onaylayana kadar öğrenci önceliğin yok."
                : "Öğrenci doğrulaman beklemede; e-posta gönderilmedi. Profilinden daha sonra tekrar dene. Normal üyeliğini kullanabilirsin.";
    }

    @Transactional
    public User confirmStudentEmail(User user, String token) {
        User u = lockedUser(user);
        if (!mail.isEnabled()) throw new IllegalStateException("E-posta doğrulaması şu an kullanılamıyor.");
        AuthToken proof = tokens.verify(token, TokenType.EMAIL_VERIFY)
                .filter(t -> t.getUser().getId().equals(u.getId()))
                .filter(t -> t.getVerificationEmail() != null && t.getVerificationEmail().equals(u.getStudentEmail()))
                .filter(t -> isStudentEmail(t.getVerificationEmail()))
                .orElseThrow(() -> new IllegalStateException("Bağlantı geçersiz ya da süresi dolmuş. Yeniden doğrulama iste."));
        if (u.getStudentStatus() != StudentStatus.PENDING)
            throw new IllegalStateException("Bekleyen e-posta doğrulaması bulunamadı.");
        tokens.consume(proof);
        u.setStudentStatus(StudentStatus.APPROVED);
        return users.save(u);
    }

    /**
     * Üyeyken öğrenci doğrulamasına başvurur; belge admin onayına gider.
     * Onaylı öğrenci ya da incelemedeki başvuru varsa tekrar başvurulamaz.
     */
    @Transactional
    public User applyForStudent(User user, SchoolLevel level, String documentNo, String documentPath) {
        User u = lockedUser(user);

        if (u.getStudentStatus() == StudentStatus.APPROVED)
            throw new IllegalStateException("Zaten onaylı bir öğrencisin.");
        if (u.getStudentStatus() == StudentStatus.PENDING && u.getDocumentPath() != null)
            throw new IllegalStateException("Belgen zaten incelemede.");
        if (level == null) throw new IllegalStateException("Okul seviyesi seçilmeli.");

        String docNo = clean(documentNo, 100);
        if (docNo == null) throw new IllegalStateException("Öğrenci belge numarası zorunlu.");
        if (documentPath == null) throw new IllegalStateException("Öğrenci belgesi yüklenmeli.");
        if (features.isAddress() && (u.getAddress() == null || u.getAddress().isBlank()))
            throw new IllegalStateException("Önce profilinden teslimat adresi eklemelisin.");

        if (users.existsByDocumentNo(docNo) && !docNo.equals(u.getDocumentNo()))
            throw new IllegalStateException("Bu belge numarası başka bir kayıtta kullanılıyor.");

        u.setStudentStatus(StudentStatus.PENDING);
        u.setSchoolLevel(level);
        u.setDocumentNo(docNo);
        u.setDocumentPath(documentPath);
        return users.save(u);
    }
}
