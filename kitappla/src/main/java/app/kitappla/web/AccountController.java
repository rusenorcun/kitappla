package app.kitappla.web;

import app.kitappla.domain.User;
import app.kitappla.security.AppUserDetails;
import app.kitappla.service.DonationService;
import app.kitappla.service.PickupPointService;
import app.kitappla.service.QuotaService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AccountController {

    private final QuotaService quotaService;
    private final DonationService donationService;
    private final PickupPointService points;
    private final app.kitappla.service.PanoService pano;

    public AccountController(QuotaService quotaService,
                             DonationService donationService, PickupPointService points,
                             app.kitappla.service.PanoService pano) {
        this.pano = pano;
        this.quotaService = quotaService;
        this.donationService = donationService;
        this.points = points;
    }

    @GetMapping("/panom")
    public String dashboard(@AuthenticationPrincipal AppUserDetails principal, Model model) {
        User user = principal.getUser();   // FreshPrincipalFilter tarafından tazelenmiş
        model.addAttribute("user", user);
        model.addAttribute("quota", quotaService.quotaFor(user));
        model.addAttribute("bekleyen", pano.bekleyenler(user));
        return "panom";
    }

    /** Kullanıcının aldığı kitaplar (teslimat durumuyla). */
    @GetMapping("/aldiklarim")
    public String aldiklarim(@AuthenticationPrincipal AppUserDetails principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("claims", donationService.findClaimsByStudent(user));
        model.addAttribute("noktalar", points.active());
        return "aldiklarim";
    }

    /** Alıcı: kitabı teslim aldım. */
    @PostMapping("/teslimat/{claimId}/teslim-aldim")
    public String teslimAldim(@AuthenticationPrincipal AppUserDetails principal,
                              @PathVariable Long claimId, RedirectAttributes ra) {
        return run(ra, () -> donationService.deliver(claimId, principal.getUser()),
                "Teslim aldığın kaydedildi. Bağışçıya teşekkür edebilirsin.");
    }

    /** Alıcı: bağışçıya teşekkür notu. */
    @PostMapping("/teslimat/{claimId}/tesekkur")
    public String tesekkur(@AuthenticationPrincipal AppUserDetails principal,
                           @PathVariable Long claimId,
                           @RequestParam(required = false) String message,
                           RedirectAttributes ra) {
        return run(ra, () -> donationService.thank(claimId, principal.getUser(), message),
                "Teşekkürün bağışçıya iletildi.");
    }

    /** Alıcı: kargolanmadan önce talebi iptal et. */
    @PostMapping("/teslimat/{claimId}/iptal")
    public String iptal(@AuthenticationPrincipal AppUserDetails principal,
                        @PathVariable Long claimId, RedirectAttributes ra) {
        return run(ra, () -> donationService.cancelClaim(claimId, principal.getUser()),
                "Talebin iptal edildi; kitap yeniden başkalarına açıldı.");
    }

    private String run(RedirectAttributes ra, Runnable action, String okMessage) {
        try {
            action.run();
            ra.addFlashAttribute("basari", okMessage);
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("hata", ex.getMessage());
        }
        return "redirect:/aldiklarim";
    }
}
