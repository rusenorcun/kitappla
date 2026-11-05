package app.kitappla.web;

import app.kitappla.config.Marka;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Yönetim ayrı alan adındayken ({@code kitappla.admin-url}) iki alan adını birbirinden ayırır.
 * <ul>
 *   <li>Site alan adına gelen {@code /admin} istekleri yönetim alan adına yönlendirilir.</li>
 *   <li>Yönetim alan adı yalnızca yönetim sayfalarını, giriş/çıkışı, statik dosyaları ve
 *       (şikâyet destek yazışmaları için) mesajları sunar; kalan sayfalar siteye gönderilir.</li>
 * </ul>
 * Oturum çerezleri ana makineye bağlıdır: yönetim oturumu site alan adına hiç gönderilmez,
 * sitede çıkabilecek bir açık yönetim oturumunu taşıyamaz. Spring Security'den önce çalışır;
 * yoksa www'deki /admin önce www'nin giriş sayfasına düşerdi. Yönetim ayrı değilse hiçbir şey yapmaz.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)   // ForwardedHeaderFilter'dan sonra: gerçek ana makine adı okunur
public class YonetimAlanAdiFiltresi extends OncePerRequestFilter {

    /** Yönetim alan adında sunulduğunu şablonlara bildiren istek özniteliği. */
    public static final String YONETIM_ALANI = "kitappla.yonetimAlani";

    /** Yönetim alan adında /admin dışında açık kalan yollar. */
    private static final List<String> YONETIMDE_ACIK = List.of(
            "/login", "/logout", "/error", "/saglik", "/favicon.ico", "/favicon.svg",
            "/css/", "/js/", "/webjars/", "/uploads/covers/", "/mesajlar", "/bildirimler");

    private final Marka marka;

    public YonetimAlanAdiFiltresi(Marka marka) {
        this.marka = marka;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !marka.yonetimAyri();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        String yol = request.getRequestURI().substring(request.getContextPath().length());
        String sorgu = request.getQueryString();
        boolean yonetimYolu = yol.equals("/admin") || yol.startsWith("/admin/");

        if (host.equals(marka.yonetimAlanAdi())) {
            request.setAttribute(YONETIM_ALANI, Boolean.TRUE);
            if (yol.equals("/") || yol.isEmpty()) {
                // Çıkıştan sonra "/?cikis" gelir: bilgi mesajı giriş sayfasında gösterilsin
                String ctx = request.getContextPath();
                response.sendRedirect(ctx + ("cikis".equals(sorgu) ? "/login?cikis" : "/admin"));
                return;
            }
            if (yonetimYolu || acik(yol)) {
                chain.doFilter(request, response);
                return;
            }
            yonlendirVeyaReddet(request, response, marka.siteAdresi() + yol + (sorgu == null ? "" : "?" + sorgu));
            return;
        }

        if (yonetimYolu) {
            yonlendirVeyaReddet(request, response, marka.yonetimLinki(yol) + (sorgu == null ? "" : "?" + sorgu));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean acik(String yol) {
        for (String onek : YONETIMDE_ACIK) {
            if (onek.endsWith("/") ? yol.startsWith(onek) : (yol.equals(onek) || yol.startsWith(onek + "/")))
                return true;
        }
        return false;
    }

    /**
     * Sayfa istekleri yönlendirilir. Form/API istekleri yönlendirilmez: tarayıcılar 302'de
     * gövdeyi düşürüp GET'e çevirir, işlem sessizce kaybolurdu. Yanlış alan adına gelen
     * değiştirici istek 404 alır.
     */
    private static void yonlendirVeyaReddet(HttpServletRequest request, HttpServletResponse response, String hedef)
            throws IOException {
        String yontem = request.getMethod();
        if ("GET".equals(yontem) || "HEAD".equals(yontem)) response.sendRedirect(hedef);
        else response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
