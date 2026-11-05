package app.kitappla.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import app.kitappla.repo.UserRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import java.util.List;
import java.util.Locale;

/**
 * Kullanıcı oturumlarını SessionRegistry üzerinden yönetir ve şifre sıfırlama/değiştirme
 * durumlarında açık oturumları ile kalıcı Remember-Me çerezlerini geçersiz kılar.
 */
@Service
public class UserSessionService {

    private static final Logger log = LoggerFactory.getLogger(UserSessionService.class);

    private final SessionRegistry sessionRegistry;
    private final UserRepository users;
    private final PersistentTokenRepository tokenRepository;
    private final SessionTokenService sessionTokenService;

    @org.springframework.beans.factory.annotation.Autowired
    public UserSessionService(SessionRegistry sessionRegistry,
                              UserRepository users,
                              @Lazy PersistentTokenRepository tokenRepository,
                              SessionTokenService sessionTokenService) {
        this.sessionRegistry = sessionRegistry;
        this.users = users;
        this.tokenRepository = tokenRepository;
        this.sessionTokenService = sessionTokenService;
    }

    public UserSessionService(SessionRegistry sessionRegistry,
                              UserRepository users,
                              @Lazy PersistentTokenRepository tokenRepository) {
        this(sessionRegistry, users, tokenRepository, null);
    }

    /**
     * Verilen kullanıcının tüm açık oturumlarını sonlandırır.
     */
    public void expireUserSessions(Long userId) {
        expireOtherUserSessions(userId, null);
    }

    /**
     * Verilen kullanıcının {@code keepSessionId} dışındaki tüm açık oturumlarını ve
     * kalıcı Remember-Me çerezlerini sonlandırır.
     */
    public void expireOtherUserSessions(Long userId, String keepSessionId) {
        if (userId == null) return;
        users.findById(userId).ifPresent(u -> {
            if (tokenRepository != null && u.getEmail() != null) {
                try {
                    tokenRepository.removeUserTokens(u.getEmail().trim().toLowerCase(Locale.ROOT));
                } catch (Exception e) {
                    log.warn("Kullanıcı (id={}) kalıcı oturum token'ları temizlenirken hata: {}", userId, e.getMessage());
                }
            }
        });

        int count = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AppUserDetails details && details.getUser() != null) {
                if (userId.equals(details.getUser().getId())) {
                    List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
                    for (SessionInformation session : sessions) {
                        if (keepSessionId != null && keepSessionId.equals(session.getSessionId())) {
                            continue;
                        }
                        session.expireNow();
                        if (sessionTokenService != null) {
                            sessionTokenService.remove(session.getSessionId());
                        }
                        count++;
                    }
                }
            }
        }
        if (count > 0) {
            log.info("Kullanıcı (id={}) için {} açık oturum sonlandırıldı.", userId, count);
        }
    }

    /**
     * E-posta adresine göre oturumları sonlandırır.
     */
    public void expireUserSessions(String email) {
        if (email == null || email.isBlank()) return;
        String normalized = email.trim();
        if (tokenRepository != null) {
            try {
                tokenRepository.removeUserTokens(normalized.toLowerCase(Locale.ROOT));
            } catch (Exception e) {
                log.warn("Kullanıcı ({}) kalıcı oturum token'ları temizlenirken hata: {}", normalized, e.getMessage());
            }
        }
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AppUserDetails details && details.getUser() != null) {
                if (normalized.equalsIgnoreCase(details.getUsername())) {
                    List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
                    for (SessionInformation session : sessions) {
                        session.expireNow();
                        if (sessionTokenService != null) {
                            sessionTokenService.remove(session.getSessionId());
                        }
                    }
                }
            }
        }
    }

    /**
     * Belirli bir oturumu (Session ID) derhal sonlandırır.
     */
    public boolean expireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        SessionInformation info = sessionRegistry.getSessionInformation(sessionId);
        if (info != null) {
            info.expireNow();
            if (sessionTokenService != null) {
                sessionTokenService.remove(sessionId);
            }
            log.info("Oturum (id={}) sonlandırıldı.", sessionId);
            return true;
        }
        return false;
    }
}
