package app.kitappla.web;

import app.kitappla.domain.*;
import app.kitappla.security.AppUserDetails;
import app.kitappla.service.BookMetadata;
import app.kitappla.service.BookService;
import app.kitappla.service.PickupPointService;
import app.kitappla.service.DonationService;
import app.kitappla.service.DonationView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bağış oluşturma, bağışlarım ve teslimat işlemleri. */
@Controller
public class DonationController {

    private final BookService bookService;
    private final app.kitappla.service.CoverService coverService;
    private final DonationService donationService;
    private final PickupPointService points;
    private final app.kitappla.config.Features features;

    public DonationController(BookService bookService, app.kitappla.service.CoverService coverService,
                              DonationService donationService,
                              PickupPointService points,
                              app.kitappla.config.Features features) {
        this.bookService = bookService;
        this.coverService = coverService;
        this.donationService = donationService;
        this.points = points;
        this.features = features;
    }

    @GetMapping("/bagis/yeni")
    public String yeniForm(Model model) {
        model.addAttribute("noktalar", points.active());
        return "bagis-yeni";
    }

    /** HTMX: linkten başlık/kapak önizlemesi (kaydetmez). */
    @PostMapping("/bagis/onizleme")
    public String onizleme(@RequestParam(required = false) String purchaseLink, Model model) {
        BookMetadata meta = bookService.preview(purchaseLink);
        model.addAttribute("meta", meta);
        model.addAttribute("bulunamadi", meta.isEmpty());
        return "bagis-yeni :: onizleme";
    }

    @PostMapping("/bagis/yeni")
    public String olustur(@AuthenticationPrincipal AppUserDetails principal,
                          @RequestParam(required = false) String title,
                          @RequestParam(required = false) String author,
                          @RequestParam(required = false) String purchaseLink,
                          @RequestParam(required = false) String coverUrl,
                          @RequestParam(required = false) org.springframework.web.multipart.MultipartFile coverFile,
                          @RequestParam(required = false) String description,
                          @RequestParam(defaultValue = "1") int quantity,
                          @RequestParam(required = false) String targetLevel,
                          @RequestParam(required = false) String source,
                          @RequestParam(required = false) Long pointId,
                          @RequestParam(required = false) String pointNote,
                          RedirectAttributes ra, Model model) {
        User donor = principal.getUser();
        try {
            // Elle girişte yüklenen görsel, linkten gelen kapağın önüne geçer.
            String kapak = coverService.resolve(coverFile, coverUrl);
            Book book = bookService.findOrCreate(title, author, purchaseLink, kapak, description, donor.getId());
            TargetLevel level = TargetLevel.HEPSI;
            if (targetLevel != null && !targetLevel.isBlank()) {
                try {
                    level = TargetLevel.valueOf(targetLevel.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    level = TargetLevel.HEPSI;
                }
            }
            // Satın alma kapalıyken kaynak sorulmaz; elindeki kopya varsayılır
            DonationSource src = (source == null || source.isBlank())
                    ? (features.isPurchase() ? DonationSource.PURCHASE : DonationSource.OWN)
                    : DonationSource.valueOf(source.trim().toUpperCase(java.util.Locale.ROOT));
            donationService.create(donor, book, quantity, level, src, description, pointId, pointNote);
            ra.addFlashAttribute("basari", "Bağışın yayınlandı. İlk 48 saat öğrencilere öncelikli gösterilecek.");
            return "redirect:/bagislarim";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            model.addAttribute("hata", ex.getMessage());
            model.addAttribute("form", formEcho(title, author, purchaseLink, description, quantity, targetLevel, source));
            model.addAttribute("noktalar", points.active());
            return "bagis-yeni";
        }
    }

    private Map<String, Object> formEcho(String title, String author, String link, String description,
                                         int quantity, String level, String source) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("title", title);
        f.put("author", author);
        f.put("purchaseLink", link);
        f.put("description", description);
        f.put("quantity", quantity);
        f.put("targetLevel", level);
        f.put("source", source);
        return f;
    }

    @GetMapping("/bagislarim")
    public String bagislarim(@AuthenticationPrincipal AppUserDetails principal, Model model) {
        User donor = principal.getUser();
        List<DonationView> list = donationService.myDonations(donor);
        // Her bağış için alanlar (adres yalnızca bağışçıya gösterilir)
        // Talepler bağış başına değil tek sorguda okunur (API'deki "bağışlarım" ile aynı)
        Map<Long, List<Claim>> gruplu = list.isEmpty() ? Map.of()
                : donationService.findClaimsByDonations(list.stream().map(DonationView::donation).toList()).stream()
                        .collect(java.util.stream.Collectors.groupingBy(c -> c.getDonation().getId()));
        Map<Long, List<Claim>> claimers = new LinkedHashMap<>();
        for (DonationView v : list) {
            claimers.put(v.getId(), gruplu.getOrDefault(v.getId(), List.of()));
        }
        model.addAttribute("donations", list);
        model.addAttribute("claimers", claimers);
        model.addAttribute("noktalar", points.active());
        return "bagislarim";
    }

    @PostMapping("/bagis/{id}/kapat")
    public String kapat(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id, RedirectAttributes ra) {
        return run(ra, () -> donationService.close(id, principal.getUser()), "Bağış kapatıldı.");
    }

    @PostMapping("/bagis/{id}/ac")
    public String ac(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id, RedirectAttributes ra) {
        return run(ra, () -> donationService.reopen(id, principal.getUser()), "Bağış yeniden açıldı.");
    }

    @PostMapping("/bagis/{id}/sil")
    public String sil(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id, RedirectAttributes ra) {
        return run(ra, () -> donationService.delete(id, principal.getUser()), "Bağış silindi.");
    }

    @PostMapping("/bagis/{id}/takasa-aktar")
    public String takasaAktar(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id,
                              @RequestParam(required = false) String note, RedirectAttributes ra) {
        return run(ra, () -> donationService.moveToSwap(id, principal.getUser(), note),
                "Kitap bağıştan kaldırıldı ve takasa açıldı.");
    }

    @PostMapping("/teslimat/{claimId}/kargola")
    public String kargola(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long claimId, RedirectAttributes ra) {
        return run(ra, () -> donationService.ship(claimId, principal.getUser()),
                "Kargo bilgisi kaydedildi. Alıcıya haber verildi.");
    }

    private String run(RedirectAttributes ra, Runnable action, String okMessage) {
        try {
            action.run();
            ra.addFlashAttribute("basari", okMessage);
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/bagislarim";
    }
}
