package app.kitappla.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Başarısız giriş denemelerini kayan pencerede sayar; kaba kuvvet denemelerini ve password spraying saldırılarını engeller.
 * Bellek içi boyut ve süre sınırlı Caffeine önbelleğinde tutulur.
 */
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final int maxAttemptsIp;
    private final Duration window;
    private final Cache<String, Deque<Instant>> failures;

    public LoginAttemptService(@Value("${kitappla.login.max-attempts:8}") int maxAttempts,
                               @Value("${kitappla.login.max-attempts-ip:40}") int maxAttemptsIp,
                               @Value("${kitappla.login.window-minutes:15}") int windowMinutes) {
        this.maxAttempts = maxAttempts;
        this.maxAttemptsIp = maxAttemptsIp;
        this.window = Duration.ofMinutes(windowMinutes);
        this.failures = Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterAccess(this.window.plusMinutes(5))
                .build();
    }

    /** Aynı e-posta + IP çifti anahtarı. */
    public static String key(String email, String ip) {
        return (email == null ? "?" : email.trim().toLowerCase(Locale.ROOT)) + "|" + (ip == null ? "?" : ip.trim());
    }

    /** Yalnız-IP anahtarı (password spraying tespiti için). */
    public static String ipKey(String ip) {
        return "ip:" + (ip == null ? "?" : ip.trim());
    }

    /** E-posta + IP veya yalnız-IP sınırının aşılıp aşılmadığını denetler. */
    public boolean isBlocked(String email, String ip) {
        return isBlocked(key(email, ip)) || isIpBlocked(ip);
    }

    /** Belirli bir anahtarın (e-posta|IP) kilitli olup olmadığını denetler. */
    public boolean isBlocked(String key) {
        return isKeyBlocked(key, maxAttempts);
    }

    /** Belirli bir IP'nin küresel kilitli olup olmadığını denetler. */
    public boolean isIpBlocked(String ip) {
        return isKeyBlocked(ipKey(ip), maxAttemptsIp);
    }

    /** Başarısız denemeyi hem e-posta+IP hem de yalnız-IP sayacına kaydeder. */
    public void recordFailure(String email, String ip) {
        recordFailure(key(email, ip));
        recordFailureForKey(ipKey(ip));
    }

    /** Başarısız denemeyi tekil anahtara kaydeder (thread-safe). */
    public void recordFailure(String key) {
        recordFailureForKey(key);
    }

    /** Başarılı girişte e-posta+IP sayacı sıfırlanır. */
    public void reset(String email, String ip) {
        reset(key(email, ip));
    }

    /** Belirli bir anahtarı sıfırlar. */
    public void reset(String key) {
        if (key != null) {
            failures.invalidate(key);
        }
    }

    /** IP sayacını sıfırlar. */
    public void resetIp(String ip) {
        if (ip != null) {
            failures.invalidate(ipKey(ip));
        }
    }

    public int windowMinutes() {
        return (int) window.toMinutes();
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int maxAttemptsIp() {
        return maxAttemptsIp;
    }

    /** Önbellekte takip edilen başarısız giriş deneme anahtarı sayısı. */
    public long trackedFailureCount() {
        return failures.estimatedSize();
    }

    private boolean isKeyBlocked(String k, int max) {
        if (k == null) return false;
        Deque<Instant> hits = failures.getIfPresent(k);
        if (hits == null || hits.isEmpty()) return false;
        synchronized (hits) {
            Instant limit = Instant.now().minus(window);
            while (!hits.isEmpty() && hits.peekFirst().isBefore(limit)) {
                hits.pollFirst();
            }
            if (hits.isEmpty()) {
                failures.invalidate(k);
                return false;
            }
            return hits.size() >= max;
        }
    }

    private void recordFailureForKey(String k) {
        if (k == null) return;
        failures.asMap().compute(k, (key, hits) -> {
            if (hits == null) {
                hits = new ArrayDeque<>();
            }
            synchronized (hits) {
                Instant limit = Instant.now().minus(window);
                while (!hits.isEmpty() && hits.peekFirst().isBefore(limit)) {
                    hits.pollFirst();
                }
                hits.addLast(Instant.now());
            }
            return hits;
        });
    }
}
