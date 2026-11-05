package app.kitappla.security;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gerçek HTTP oturum kimliklerini dışarıya açılmayan opak tanıtıcılara eşler.
 * API yanıtlarında ham oturum kimliği asla sızdırılmaz; istemci yalnızca bu
 * servisten aldığı opak token'ı kullanır.
 *
 * <p>Eşleme yalnızca bellekte tutulur; sunucu yeniden başladığında mevcut
 * token'lar geçersiz olur ama Spring Security oturumları da sıfırlanacağı
 * için bu kabul edilebilirdir.</p>
 */
@Service
public class SessionTokenService {

    /** opak token → gerçek oturum kimliği */
    private final Map<String, String> tokenToSession = new ConcurrentHashMap<>();
    /** gerçek oturum kimliği → opak token */
    private final Map<String, String> sessionToToken = new ConcurrentHashMap<>();

    /**
     * Verilen gerçek oturum kimliği için opak bir tanıtıcı üretir.
     * Aynı oturum için ikinci kez çağrılırsa mevcut token'ı döndürür.
     */
    public String tokenFor(String realSessionId) {
        if (realSessionId == null) return null;
        return sessionToToken.computeIfAbsent(realSessionId, sid -> {
            String token = UUID.randomUUID().toString();
            tokenToSession.put(token, sid);
            return token;
        });
    }

    /**
     * Opak tanıtıcıyı gerçek oturum kimliğine çözer.
     * Bilinmeyen token için {@code null} döner.
     */
    public String resolve(String opaqueToken) {
        if (opaqueToken == null) return null;
        return tokenToSession.get(opaqueToken);
    }

    /**
     * Sonlandırılmış veya artık geçerli olmayan oturum eşlemesini temizler.
     */
    public void remove(String realSessionId) {
        if (realSessionId == null) return;
        String token = sessionToToken.remove(realSessionId);
        if (token != null) {
            tokenToSession.remove(token);
        }
    }

    /**
     * Yalnızca verilen etkin oturum kimliklerini korur; düşmüş/süresi dolmuş oturumların
     * opak token eşlemelerini bellekten temizler.
     */
    public void retainActive(java.util.Collection<String> activeSessionIds) {
        if (activeSessionIds == null) return;
        java.util.Set<String> activeSet = activeSessionIds instanceof java.util.Set<String> s
                ? s : new java.util.HashSet<>(activeSessionIds);
        for (String sid : sessionToToken.keySet()) {
            if (!activeSet.contains(sid)) {
                remove(sid);
            }
        }
    }
}
