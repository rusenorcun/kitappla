package app.kitappla.web;

import app.kitappla.domain.Book;
import app.kitappla.domain.DonationSource;
import app.kitappla.domain.RequestStatus;
import app.kitappla.domain.User;
import app.kitappla.security.AppUserDetails;
import app.kitappla.security.CurrentUser;
import app.kitappla.service.BookService;
import app.kitappla.service.MeetingRequest;
import app.kitappla.service.PickupPointService;
import app.kitappla.service.RequestService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Kitap istekleri: oluşturma, açık istekler, karşılama ve teslimat. */
@Controller
public class RequestController {

    private final RequestService requestService;
    private final PickupPointService points;
    private final BookService bookService;
    private final app.kitappla.config.Features features;

    public RequestController(RequestService requestService, BookService bookService,
                             PickupPointService points, app.kitappla.config.Features features) {
        this.requestService = requestService;
        this.points = points;
        this.bookService = bookService;
        this.features = features;
    }

    /** Açık istekler — herkese açık; teslimat adresi burada GÖSTERİLMEZ. */
    @GetMapping("/istekler")
    public String istekler(@RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "0") int sayfa, Model model) {
        model.addAttribute("requests", requestService.openRequests(q, ListeSayfasi.of(sayfa)));
        model.addAttribute("q", q);
        model.addAttribute("me", CurrentUser.get());
        return "istekler";
    }

    @GetMapping("/istek/yeni")
    public String yeniForm() {
        return "istek-yeni";
    }

    @PostMapping("/istek/yeni")
    public String olustur(@AuthenticationPrincipal AppUserDetails principal,
                          @RequestParam(required = false) String title,
                          @RequestParam(required = false) String author,
                          @RequestParam(required = false) String purchaseLink,
                          @RequestParam(required = false) String description,
                          RedirectAttributes ra, Model model) {
        User user = principal.getUser();
        try {
            Book book = bookService.findOrCreate(title, author, purchaseLink, null, null, user.getId());
            requestService.create(user, book, description);
            ra.addFlashAttribute("basari", "İsteğin yayınlandı. Bir bağışçı karşıladığında haber vereceğiz.");
            return "redirect:/isteklerim";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            model.addAttribute("hata", ex.getMessage());
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("title", title);
            form.put("author", author);
            form.put("purchaseLink", purchaseLink);
            form.put("description", description);
            model.addAttribute("form", form);
            return "istek-yeni";
        }
    }

    @GetMapping("/isteklerim")
    public String isteklerim(@AuthenticationPrincipal AppUserDetails principal, Model model) {
        model.addAttribute("requests", requestService.myRequests(principal.getUser()));
        model.addAttribute("noktalar", points.active());
        return "isteklerim";
    }

    /** Karşıladığım istekler — teslimat adresi burada gösterilir. */
    @GetMapping("/karsiladiklarim")
    public String karsiladiklarim(@AuthenticationPrincipal AppUserDetails principal, Model model) {
        model.addAttribute("requests", requestService.fulfilledByMe(principal.getUser()));
        model.addAttribute("noktalar", points.active());
        return "karsiladiklarim";
    }

    /** Karşılama onay sayfası: istek detaylarını gösterir, kullanıcı "Kaydet ve karşı tarafa bildir" ile onaylar. */
    @GetMapping("/istek/{id}/karsila")
    public String karsilaOnay(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id,
                              Model model, RedirectAttributes ra) {
        var opt = requestService.view(id);
        if (opt.isEmpty()) {
            ra.addFlashAttribute("hata", "İstek bulunamadı.");
            return "redirect:/istekler";
        }
        var r = opt.get();
        if (r.getStatus() != RequestStatus.OPEN) {
            if (r.getStatus() == RequestStatus.FULFILLED && !r.getMeeting().isArranged()) {
                if (r.getFulfilledBy() != null && r.getFulfilledBy().getId().equals(principal.getUser().getId())) {
                    return "redirect:/karsiladiklarim";
                }
                ra.addFlashAttribute("hata", "Bu istek için şu an buluşma ayarlanıyor. İptal edilirse tekrar açık olacaktır.");
            } else {
                ra.addFlashAttribute("hata", "Bu istek başkası tarafından karşılandı.");
            }
            return "redirect:/istekler";
        }
        if (r.getStudent().getId().equals(principal.getUser().getId())) {
            ra.addFlashAttribute("hata", "Kendi isteğini karşılayamazsın.");
            return "redirect:/istekler";
        }
        model.addAttribute("r", r);
        model.addAttribute("noktalar", points.active());
        return "istek-karsila";
    }

    @PostMapping("/istek/{id}/karsila")
    public String karsila(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id,
                          @RequestParam(required = false) String source,
                          @RequestParam(required = false) Long pointId,
                          @RequestParam(required = false) String note,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at,
                          RedirectAttributes ra) {
        try {
            DonationSource src = (source == null || source.isBlank())
                    ? (features.isPurchase() ? DonationSource.PURCHASE : DonationSource.OWN)
                    : DonationSource.valueOf(source.trim().toUpperCase(java.util.Locale.ROOT));
            if (at != null) {
                Instant atInstant = at.atZone(ZoneId.systemDefault()).toInstant();
                requestService.fulfill(id, principal.getUser(), src, new MeetingRequest(pointId, note, atInstant));
                ra.addFlashAttribute("basari", "İsteği karşıladın ve buluşma kaydedildi. Karşı tarafa bildirildi.");
            } else {
                requestService.fulfill(id, principal.getUser(), src);
                ra.addFlashAttribute("basari", features.isShipping()
                        ? "İsteği karşıladın. Teslimat adresi aşağıda; kargoladığında işaretle."
                        : "İsteği karşıladın. Buluşma yeri ve saatini belirleyip karşı tarafa bildirebilirsin.");
            }
            return "redirect:/karsiladiklarim";
        } catch (IllegalStateException | IllegalArgumentException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
            return "redirect:/istekler";
        }
    }

    @PostMapping("/istek/{id}/iptal")
    public String iptal(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id,
                        @RequestParam(required = false) String geri, RedirectAttributes ra) {
        String hedef = (geri != null && geri.equals("/karsiladiklarim")) ? "/karsiladiklarim" : "/isteklerim";
        return run(ra, () -> requestService.cancelFulfillment(id, principal.getUser()),
                "İstek karşılama iptal edildi; istek yeniden açık duruma getirildi.", hedef);
    }

    @PostMapping("/istek/{id}/kargola")
    public String kargola(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id, RedirectAttributes ra) {
        return run(ra, () -> requestService.ship(id, principal.getUser()),
                "Kargo bilgisi kaydedildi. Alıcıya haber verildi.", "/karsiladiklarim");
    }

    @PostMapping("/istek/{id}/teslim-aldim")
    public String teslimAldim(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id, RedirectAttributes ra) {
        return run(ra, () -> requestService.deliver(id, principal.getUser()),
                "Teslim aldığın kaydedildi. Karşılayana teşekkür edebilirsin.", "/isteklerim");
    }

    @PostMapping("/istek/{id}/tesekkur")
    public String tesekkur(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id,
                           @RequestParam(required = false) String message, RedirectAttributes ra) {
        return run(ra, () -> requestService.thank(id, principal.getUser(), message),
                "Teşekkürün iletildi.", "/isteklerim");
    }

    @PostMapping("/istek/{id}/sil")
    public String sil(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id, RedirectAttributes ra) {
        return run(ra, () -> requestService.delete(id, principal.getUser()),
                "İsteğin kaldırıldı.", "/isteklerim");
    }

    private String run(RedirectAttributes ra, Runnable action, String okMessage, String target) {
        try {
            action.run();
            ra.addFlashAttribute("basari", okMessage);
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:" + target;
    }
}
