package app.kitappla.service;

import app.kitappla.api.dto.AdminMonitorDto;
import app.kitappla.api.dto.AdminMonitorDto.ActiveSessionDto;
import app.kitappla.api.dto.AdminMonitorDto.MonitorSummaryDto;
import app.kitappla.domain.User;
import app.kitappla.repo.UserRepository;
import app.kitappla.security.AppUserDetails;
import app.kitappla.security.LoginAttemptService;
import app.kitappla.security.SessionTokenService;
import app.kitappla.security.UserSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Yönetim paneli için canlı oturum, çevrim içi kullanıcı ve sistem çalışma zamanı parametrelerini toplar.
 */
@Service
public class AdminMonitorService {

    private static final Logger log = LoggerFactory.getLogger(AdminMonitorService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SessionRegistry sessionRegistry;
    private final UserSessionService userSessionService;
    private final UserRepository userRepository;
    private final DataSource dataSource;
    private final LoginAttemptService loginAttemptService;
    private final SessionTokenService sessionTokenService;

    public AdminMonitorService(SessionRegistry sessionRegistry,
                               UserSessionService userSessionService,
                               UserRepository userRepository,
                               DataSource dataSource,
                               LoginAttemptService loginAttemptService,
                               SessionTokenService sessionTokenService) {
        this.sessionRegistry = sessionRegistry;
        this.userSessionService = userSessionService;
        this.userRepository = userRepository;
        this.dataSource = dataSource;
        this.loginAttemptService = loginAttemptService;
        this.sessionTokenService = sessionTokenService;
    }

    /**
     * Tam izleç verilerini (özet + oturum listesi) döndürür.
     *
     * @param currentSessionId Sayfayı görüntüleyen yöneticinin mevcut oturum kimliği (rozetleme için)
     */
    public AdminMonitorDto getMonitorData(String currentSessionId) {
        MonitorSummaryDto summary = getSummary();
        List<ActiveSessionDto> sessions = getActiveSessions(currentSessionId);
        return new AdminMonitorDto(summary, sessions);
    }

    /**
     * Yalnızca sistem ve oturum özet sayaçlarını hesaplar.
     */
    public MonitorSummaryDto getSummary() {
        // Oturum ve kullanıcı sayıları
        List<Object> principals = sessionRegistry.getAllPrincipals();
        long activeSessionsCount = 0;
        Set<Long> uniqueUserIds = new HashSet<>();
        long onlineAdmins = 0;
        long onlineStudents = 0;

        for (Object principal : principals) {
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            if (sessions == null || sessions.isEmpty()) continue;

            User user = resolveUser(principal);
            boolean hasActiveSession = false;

            for (SessionInformation session : sessions) {
                if (!session.isExpired()) {
                    activeSessionsCount++;
                    hasActiveSession = true;
                }
            }

            if (hasActiveSession && user != null && user.getId() != null) {
                if (uniqueUserIds.add(user.getId())) {
                    if (user.isAdmin()) onlineAdmins++;
                    if (user.isStudent()) onlineStudents++;
                }
            }
        }

        long onlineUsersCount = uniqueUserIds.size();

        // JVM & Çalışma Zamanı
        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        long usedMem = totalMem - freeMem;
        int memPercent = maxMem > 0 ? (int) Math.round(((double) usedMem / maxMem) * 100) : 0;

        RuntimeMXBean runtimeMx = ManagementFactory.getRuntimeMXBean();
        long uptime = runtimeMx.getUptime();
        Instant startTime = Instant.ofEpochMilli(runtimeMx.getStartTime());

        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        int threads = threadMx.getThreadCount();
        int peakThreads = threadMx.getPeakThreadCount();
        int cpus = rt.availableProcessors();

        DbInfo db = getDbInfo();
        long persistentLogins = db.persistentLogins();
        long failureCount = loginAttemptService.trackedFailureCount();

        return new MonitorSummaryDto(
                activeSessionsCount,
                onlineUsersCount,
                onlineAdmins,
                onlineStudents,
                persistentLogins,
                formatDuration(uptime),
                uptime,
                DATE_TIME_FMT.format(startTime),
                usedMem / (1024 * 1024),
                totalMem / (1024 * 1024),
                maxMem / (1024 * 1024),
                memPercent,
                cpus,
                threads,
                peakThreads,
                db.connected(),
                db.product(),
                db.active(),
                db.idle(),
                failureCount
        );
    }

    /**
     * Tüm aktif (süresi dolmamış) oturumların ayrıntılı listesini döndürür.
     */
    public List<ActiveSessionDto> getActiveSessions(String currentSessionId) {
        List<Object> principals = sessionRegistry.getAllPrincipals();
        record SessionEntry(SessionInformation session, User user, Object principal) {}
        List<SessionEntry> entries = new ArrayList<>();
        Set<String> activeSessionIds = new HashSet<>();

        for (Object principal : principals) {
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            if (sessions == null) continue;

            User user = resolveUser(principal);
            for (SessionInformation session : sessions) {
                if (!session.isExpired()) {
                    entries.add(new SessionEntry(session, user, principal));
                    activeSessionIds.add(session.getSessionId());
                }
            }
        }
        sessionTokenService.retainActive(activeSessionIds);

        // Son istek tarihine göre en yeniden eskiye doğru sırala
        entries.sort((a, b) -> {
            Date da = a.session().getLastRequest();
            Date db = b.session().getLastRequest();
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return db.compareTo(da);
        });

        List<ActiveSessionDto> dtos = new ArrayList<>();
        for (SessionEntry e : entries) {
            SessionInformation s = e.session();
            User u = e.user();
            String sid = s.getSessionId();
            String opaqueToken = sessionTokenService.tokenFor(sid);
            Instant lastReq = s.getLastRequest() != null ? s.getLastRequest().toInstant() : null;
            boolean isCurrent = currentSessionId != null && currentSessionId.equals(sid);

            dtos.add(new ActiveSessionDto(
                    opaqueToken,
                    maskSessionId(sid),
                    u != null ? u.getId() : null,
                    u != null ? u.getName() : (e.principal() != null ? e.principal().toString() : "Bilinmeyen"),
                    u != null ? u.getEmail() : "",
                    u != null ? u.getInitials() : "?",
                    u != null && u.isAdmin(),
                    u != null && u.isStudent(),
                    u != null && u.isBlocked(),
                    lastReq != null ? TIME_FMT.format(lastReq) : "-",
                    formatRelativeTime(lastReq),
                    isCurrent
            ));
        }

        return dtos;
    }

    /**
     * Belirtilen oturumu sonlandırır.
     */
    public boolean expireSession(String sessionId) {
        return userSessionService.expireSession(sessionId);
    }

    /**
     * Opak tanıtıcıyı gerçek oturum kimliğine çözer.
     * Bilinmeyen token için {@code null} döner.
     */
    public String resolveSessionToken(String opaqueToken) {
        return sessionTokenService.resolve(opaqueToken);
    }

    /**
     * Opak tanıtıcıyla oturum sonlandırır.
     * @return {@code true} oturum bulunup sonlandırıldıysa
     */
    public boolean expireSessionByToken(String opaqueToken) {
        String realSessionId = sessionTokenService.resolve(opaqueToken);
        if (realSessionId == null) return false;
        boolean result = userSessionService.expireSession(realSessionId);
        if (result) {
            sessionTokenService.remove(realSessionId);
        }
        return result;
    }

    public long getPersistentLoginCount() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM persistent_logins")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private User resolveUser(Object principal) {
        if (principal instanceof AppUserDetails details) {
            return details.getUser();
        } else if (principal instanceof User u) {
            return u;
        } else if (principal instanceof String email) {
            return userRepository.findByEmail(email).orElse(null);
        }
        return null;
    }

    private String maskSessionId(String sid) {
        if (sid == null) return "-";
        if (sid.length() <= 10) return sid;
        return sid.substring(0, 5) + "..." + sid.substring(sid.length() - 5);
    }

    public static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(" gün ");
        if (hours > 0 || days > 0) sb.append(hours).append(" sa ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append(" dk ");
        if (days == 0 && hours == 0) sb.append(secs).append(" sn");
        return sb.toString().trim();
    }

    public static String formatRelativeTime(Instant instant) {
        if (instant == null) return "Bilinmiyor";
        Duration d = Duration.between(instant, Instant.now());
        long seconds = Math.max(0, d.getSeconds());
        if (seconds < 5) return "Az önce";
        if (seconds < 60) return seconds + " saniye önce";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " dakika önce";
        long hours = minutes / 60;
        if (hours < 24) return hours + " saat önce";
        long days = hours / 24;
        return days + " gün önce";
    }

    private volatile String cachedDbProduct;

    private record DbInfo(boolean connected, String product, Integer active, Integer idle, long persistentLogins) {}

    private DbInfo getDbInfo() {
        boolean connected = false;
        String product = cachedDbProduct != null ? cachedDbProduct : "Bilinmiyor";
        Integer active = null;
        Integer idle = null;
        long persistentLogins = 0;
        try (var conn = dataSource.getConnection()) {
            connected = conn.isValid(1);
            if (cachedDbProduct == null) {
                var md = conn.getMetaData();
                product = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
                cachedDbProduct = product;
            }
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM persistent_logins")) {
                if (rs.next()) {
                    persistentLogins = rs.getLong(1);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            connected = false;
        }

        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            var poolMx = hikari.getHikariPoolMXBean();
            if (poolMx != null) {
                try {
                    active = poolMx.getActiveConnections();
                    idle = poolMx.getIdleConnections();
                } catch (Exception ignored) {}
            }
        }
        return new DbInfo(connected, product, active, idle, persistentLogins);
    }
}
