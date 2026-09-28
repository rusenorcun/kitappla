package app.kitappla.web;

import app.kitappla.domain.User;
import app.kitappla.security.AppUserDetails;
import app.kitappla.service.AdminService;
import app.kitappla.domain.ReportKind;
import app.kitappla.service.MessageService;
import app.kitappla.service.PickupPointService;
import app.kitappla.service.ReportService;
import app.kitappla.domain.ConversationKind;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import app.kitappla.api.dto.AdminMonitorDto;
import app.kitappla.service.AdminMonitorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;

/**
 * Yönetim paneli. Tüm uçlar SecurityConfig'te ROLE_ADMIN ile korunur.
 * Öğrenci belgeleri statik olarak servis edilmez; yalnızca buradan okunur.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final AdminService admin;
    private final PickupPointService points;
    private final ReportService reports;
    private final MessageService messages;
    private final AdminMonitorService monitorService;

    public AdminController(AdminService admin, PickupPointService points,
                           ReportService reports, MessageService messages,
                           AdminMonitorService monitorService) {
        this.admin = admin;
        this.points = points;
        this.reports = reports;
        this.messages = messages;
        this.monitorService = monitorService;
    }

    /** Alt navigasyondaki bekleyen belge rozeti her yönetim sayfasında görünür. */
    @ModelAttribute("bekleyenSayisi")
    public long bekleyenSayisi() {
        return admin.pendingDocumentCount();
    }

    /** Açık şikâyet rozeti. */
    @ModelAttribute("sikayetSayisi")
    public long sikayetSayisi() {
        return reports.openCount();
    }

    @GetMapping
    public String pano(Model model) {
        model.addAttribute("stats", admin.stats());
        model.addAttribute("bekleyenler", admin.pendingDocuments());
        model.addAttribute("monitor", monitorService.getSummary());
        return "admin";
    }

    // ---------- Öğrenci belgeleri ----------

    @GetMapping("/belgeler")
    public String belgeler(Model model) {
        model.addAttribute("bekleyenler", admin.pendingDocuments());
        model.addAttribute("onaylananlar", admin.approvedStudents());
        return "admin-belgeler";
    }

    @PostMapping("/belgeler/{id}/onayla")
    public String onayla(@PathVariable Long id, RedirectAttributes ra) {
        try {
            User u = admin.approveStudent(id);
            ra.addFlashAttribute("basari", u.getName() + " artık onaylı öğrenci.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/belgeler";
    }

    @PostMapping("/belgeler/{id}/reddet")
    public String reddet(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes ra) {
        try {
            User u = admin.rejectStudent(id, reason);
            ra.addFlashAttribute("basari", u.getName() + " için belge reddedildi ve dosya silindi.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/belgeler";
    }

    @PostMapping("/belgeler/{id}/iptal")
    public String ogrencilikIptal(@PathVariable Long id, RedirectAttributes ra) {
        try {
            User u = admin.revokeStudent(id);
            ra.addFlashAttribute("basari", u.getName() + " için öğrencilik statüsü kaldırıldı.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/belgeler";
    }

    /** Belgeyi tarayıcıda gösterir (indirme değil); yalnızca yönetici erişebilir. */
    @GetMapping("/belge/{id}")
    public ResponseEntity<?> belge(@PathVariable Long id) {
        try {
            return BelgeYaniti.satirIci(id, admin.documentPathOf(id));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(404).body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.status(500).body("Belge okunamadı.");
        }
    }

    // ---------- Üye yönetimi ----------

    @GetMapping("/uyeler")
    public String uyeler(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("uyeler", admin.searchUsers(q));
        model.addAttribute("q", q);
        return "admin-uyeler";
    }

    @PostMapping("/uyeler/{id}/askiya-al")
    public String askiyaAl(@AuthenticationPrincipal AppUserDetails principal,
                           @PathVariable Long id, RedirectAttributes ra) {
        return uyeIslemi(ra, () -> admin.setBlocked(principal.getUser(), id, true), "askıya alındı");
    }

    @PostMapping("/uyeler/{id}/aktif-et")
    public String aktifEt(@AuthenticationPrincipal AppUserDetails principal,
                          @PathVariable Long id, RedirectAttributes ra) {
        return uyeIslemi(ra, () -> admin.setBlocked(principal.getUser(), id, false), "yeniden aktif");
    }

    @PostMapping("/uyeler/{id}/yetki-ver")
    public String yetkiVer(@AuthenticationPrincipal AppUserDetails principal,
                           @PathVariable Long id, RedirectAttributes ra) {
        return uyeIslemi(ra, () -> admin.setAdmin(principal.getUser(), id, true), "artık yönetici");
    }

    @PostMapping("/uyeler/{id}/yetki-al")
    public String yetkiAl(@AuthenticationPrincipal AppUserDetails principal,
                          @PathVariable Long id, RedirectAttributes ra) {
        return uyeIslemi(ra, () -> admin.setAdmin(principal.getUser(), id, false), "artık yönetici değil");
    }

    @PostMapping("/uyeler/{id}/sil")
    public String sil(@AuthenticationPrincipal AppUserDetails principal,
                      @PathVariable Long id, RedirectAttributes ra) {
        try {
            admin.deleteUser(principal.getUser(), id);
            ra.addFlashAttribute("basari", "Üye silindi.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/uyeler";
    }

    @PostMapping("/uyeler/{id}/ogrenci-kaldir")
    public String uyeOgrencilikKaldir(@PathVariable Long id, RedirectAttributes ra) {
        return uyeIslemi(ra, () -> admin.revokeStudent(id), "öğrencilik statüsü kaldırıldı");
    }


    private String uyeIslemi(RedirectAttributes ra, java.util.function.Supplier<User> action, String sonuc) {
        try {
            User u = action.get();
            ra.addFlashAttribute("basari", u.getName() + " " + sonuc + ".");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/uyeler";
    }

    // ---------- Teslim noktaları ----------

    @GetMapping("/noktalar")
    public String noktalar(Model model) {
        model.addAttribute("noktalar", points.all());
        return "admin-noktalar";
    }

    @PostMapping("/noktalar")
    public String noktaEkle(@RequestParam(required = false) String campus,
                            @RequestParam(required = false) String name,
                            @RequestParam(required = false) String description,
                            RedirectAttributes ra) {
        return noktaIslemi(ra, () -> {
            var p = points.create(campus, name, description);
            return p.getFullName() + " eklendi.";
        });
    }

    @PostMapping("/noktalar/{id}/guncelle")
    public String noktaGuncelle(@PathVariable Long id,
                                @RequestParam(required = false) String campus,
                                @RequestParam(required = false) String name,
                                @RequestParam(required = false) String description,
                                RedirectAttributes ra) {
        return noktaIslemi(ra, () -> {
            var p = points.update(id, campus, name, description);
            return p.getFullName() + " güncellendi.";
        });
    }

    @PostMapping("/noktalar/{id}/pasiflestir")
    public String noktaPasif(@PathVariable Long id, RedirectAttributes ra) {
        return noktaIslemi(ra, () -> points.setActive(id, false).getFullName() + " pasifleştirildi.");
    }

    @PostMapping("/noktalar/{id}/aktiflestir")
    public String noktaAktif(@PathVariable Long id, RedirectAttributes ra) {
        return noktaIslemi(ra, () -> points.setActive(id, true).getFullName() + " yeniden aktif.");
    }

    private String noktaIslemi(RedirectAttributes ra, java.util.function.Supplier<String> action) {
        try {
            ra.addFlashAttribute("basari", action.get());
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/noktalar";
    }

    // ---------- Şikâyetler ----------

    @GetMapping("/sikayetler")
    public String sikayetler(@RequestParam(required = false) String tumu, Model model) {
        boolean hepsi = tumu != null;
        model.addAttribute("sikayetler", hepsi ? reports.all() : reports.open());
        model.addAttribute("hepsi", hepsi);
        return "admin-sikayetler";
    }

    /**
     * Şikâyet edilen içeriği gösterir. Sohbetlerde mesajlar yalnızca açık
     * şikâyet varsa okunabilir; bu kural servis katmanında zorlanır.
     */
    @GetMapping({"/sikayetler/{id}", "/sikayet/{id}"})
    public String sikayet(@PathVariable Long id, Model model, RedirectAttributes ra) {
        var r = reports.find(id).orElse(null);
        if (r == null) {
            ra.addFlashAttribute("hata", "Şikâyet bulunamadı.");
            return "redirect:/admin/sikayetler";
        }
        model.addAttribute("sikayet", r);

        if (r.getKind() == ReportKind.CONVERSATION) {
            try {
                var sohbet = messages.requireForModeration(r.getRefId(), reports);
                model.addAttribute("sohbet", sohbet);
                model.addAttribute("mesajlar", messages.messagesOf(sohbet));
            } catch (IllegalStateException ex) {
                model.addAttribute("sohbetHatasi", ex.getMessage());
            }
        } else if (r.getKind() == ReportKind.CLAIM) {
            reports.findClaim(r.getRefId()).ifPresent(c -> model.addAttribute("claim", c));
        } else if (r.getKind() == ReportKind.REQUEST) {
            reports.findRequest(r.getRefId()).ifPresent(req -> model.addAttribute("request", req));
        } else if (r.getKind() == ReportKind.SWAP_OFFER) {
            reports.findOffer(r.getRefId()).ifPresent(o -> model.addAttribute("offer", o));
        } else if (r.getKind() == ReportKind.DONATION) {
            reports.findDonation(r.getRefId()).ifPresent(d -> model.addAttribute("donation", d));
        } else if (r.getKind() == ReportKind.SWAP_BOOK) {
            reports.findSwapBook(r.getRefId()).ifPresent(sb -> model.addAttribute("swapBook", sb));
        }

        // Şikâyet destek sohbeti (yönetici ile üye arasındaki irtibat)
        messages.find(ConversationKind.REPORT, r.getId()).ifPresent(destekSohbeti -> {
            model.addAttribute("destekSohbeti", destekSohbeti);
            model.addAttribute("destekMesajlari", messages.messagesOf(destekSohbeti));
        });

        return "admin-sikayet";
    }

    @PostMapping("/sikayetler/{id}/mesaj")
    public String sikayetMesajGonder(@AuthenticationPrincipal AppUserDetails principal,
                                     @PathVariable Long id,
                                     @RequestParam(required = false) String body,
                                     RedirectAttributes ra) {
        try {
            var c = messages.open(ConversationKind.REPORT, id, principal.getUser());
            messages.send(c.getId(), principal.getUser(), body);
            ra.addFlashAttribute("basari", "Mesajınız kullanıcıya iletildi.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/sikayetler/" + id;
    }

    @PostMapping("/sikayetler/{id}/sonuclandir")
    public String sikayetSonuclandir(@AuthenticationPrincipal AppUserDetails principal,
                                     @PathVariable Long id,
                                     @RequestParam(defaultValue = "false") boolean actioned,
                                     @RequestParam(required = false) String adminNote,
                                     RedirectAttributes ra) {
        try {
            reports.resolve(id, principal.getUser(), actioned, adminNote);
            ra.addFlashAttribute("basari", actioned
                    ? "Şikâyet işleme alındı olarak kapatıldı."
                    : "Şikâyet, işlem gerekmedi olarak kapatıldı.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/sikayetler";
    }

    // ---------- İçerik moderasyonu ----------

    @GetMapping("/icerik")
    public String icerik(Model model) {
        model.addAttribute("bagislar", admin.openDonations());
        model.addAttribute("istekler", admin.openRequests());
        model.addAttribute("takaslar", admin.openSwapBooks());
        return "admin-icerik";
    }

    @PostMapping("/icerik/bagis/{id}/kaldir")
    public String bagisKaldir(@PathVariable Long id, @RequestParam(required = false) String reason,
                              RedirectAttributes ra) {
        return icerikIslemi(ra, () -> admin.removeDonation(id, reason), "Bağış yayından kaldırıldı.");
    }

    @PostMapping("/icerik/istek/{id}/kaldir")
    public String istekKaldir(@PathVariable Long id, @RequestParam(required = false) String reason,
                              RedirectAttributes ra) {
        return icerikIslemi(ra, () -> admin.removeRequest(id, reason), "İstek kaldırıldı.");
    }

    @PostMapping("/icerik/takas/{id}/kaldir")
    public String takasKaldir(@PathVariable Long id, @RequestParam(required = false) String reason,
                              RedirectAttributes ra) {
        return icerikIslemi(ra, () -> admin.removeSwapBook(id, reason), "Takas ilanı kaldırıldı.");
    }

    private String icerikIslemi(RedirectAttributes ra, Runnable action, String okMessage) {
        try {
            action.run();
            ra.addFlashAttribute("basari", okMessage);
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/admin/icerik";
    }

    // ---------- Canlı İzleç & Oturumlar ----------

    @GetMapping("/izlec")
    public String izlec(Model model, HttpServletRequest request) {
        String currentSessionId = request.getSession(false) != null ? request.getSession(false).getId() : null;
        var data = monitorService.getMonitorData(currentSessionId);
        model.addAttribute("summary", data.summary());
        model.addAttribute("sessions", data.sessions());
        return "admin-izlec";
    }

    @GetMapping(value = "/izlec/canli", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public AdminMonitorDto izlecCanli(HttpServletRequest request) {
        String currentSessionId = request.getSession(false) != null ? request.getSession(false).getId() : null;
        return monitorService.getMonitorData(currentSessionId);
    }

    @PostMapping("/izlec/oturumlar/{sessionId}/sonlandir")
    public String oturumSonlandir(@PathVariable String sessionId,
                                  HttpServletRequest request,
                                  RedirectAttributes ra) {
        // sessionId burada opak tanıtıcıdır; gerçek oturum kimliğine çöz
        String realSessionId = monitorService.resolveSessionToken(sessionId);
        if (realSessionId == null) {
            ra.addFlashAttribute("hata", "Oturum bulunamadı veya zaten sonlandırılmış.");
            return "redirect:/admin/izlec";
        }
        String currentSessionId = request.getSession(false) != null ? request.getSession(false).getId() : null;
        if (currentSessionId != null && currentSessionId.equals(realSessionId)) {
            ra.addFlashAttribute("hata", "Kendi geçerli oturumunuzu izleç üzerinden sonlandıramazsınız.");
            return "redirect:/admin/izlec";
        }
        boolean ok = monitorService.expireSessionByToken(sessionId);
        if (ok) {
            ra.addFlashAttribute("basari", "Oturum başarıyla sonlandırıldı.");
        } else {
            ra.addFlashAttribute("hata", "Oturum bulunamadı veya zaten sonlandırılmış.");
        }
        return "redirect:/admin/izlec";
    }
}
