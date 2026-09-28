package app.kitappla.api.v1;

import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import java.util.Map;
import app.kitappla.api.dto.*;
import app.kitappla.config.Features;
import app.kitappla.domain.*;
import app.kitappla.security.CurrentUser;
import app.kitappla.service.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DonationApiController {

    private final DonationService donationService;
    private final BookService bookService;
    private final MessageService messageService;
    private final Features features;

    public DonationApiController(DonationService donationService,
                                 BookService bookService,
                                 MessageService messageService,
                                 Features features) {
        this.donationService = donationService;
        this.bookService = bookService;
        this.messageService = messageService;
        this.features = features;
    }

    @GetMapping("/donations")
    public ResponseEntity<List<DonationDto>> openDonations(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "true") boolean available,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false, defaultValue = "24") int size) {

        TargetLevel targetLevel = null;
        if (level != null && !level.isBlank()) {
            try {
                targetLevel = TargetLevel.valueOf(level.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        Page<DonationView> sayfa = donationService.openDonations(
                new DonationService.Filter(targetLevel, q, available), Sayfalama.of(page, size));

        User me = CurrentUser.get();
        Map<Long, ClaimEligibility> uygunluklar = me == null ? Map.of()
                : donationService.eligibilities(sayfa.getContent(), me);

        List<DonationDto> dtos = sayfa.getContent().stream()
                .map(v -> ApiDtoMapper.toDonationDto(v, uygunluklar.get(v.getId())))
                .toList();

        return ResponseEntity.ok()
                .header(Sayfalama.TOPLAM_BASLIGI, String.valueOf(sayfa.getTotalElements()))
                .body(dtos);
    }

    @GetMapping("/donations/{id}")
    public ResponseEntity<DonationDto> getDonation(@PathVariable Long id) {
        User me = CurrentUser.get();
        DonationView view = donationService.view(id, me)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Bağış bulunamadı."));
        ClaimEligibility eligibility = me != null ? donationService.eligibility(view, me) : null;
        return ResponseEntity.ok(ApiDtoMapper.toDonationDto(view, eligibility, me));
    }

    @PostMapping("/donations")
    public ResponseEntity<IdStatusDto> createDonation(@Valid @RequestBody CreateDonationBody body) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");

        Book book = bookService.findOrCreate(
                body.title(),
                body.author(),
                body.purchaseLink(),
                body.coverUrl(),
                body.description(),
                me.getId()
        );

        TargetLevel targetLevel = TargetLevel.HEPSI;
        if (body.targetLevel() != null && !body.targetLevel().isBlank()) {
            try {
                targetLevel = TargetLevel.valueOf(body.targetLevel().trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        DonationSource source = DonationSource.OWN;
        if (body.source() != null && !body.source().isBlank()) {
            try {
                source = DonationSource.valueOf(body.source().trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        Donation d = donationService.create(
                me,
                book,
                body.quantity(),
                targetLevel,
                source,
                body.description(),
                body.pointId(),
                body.pointNote()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IdStatusDto(d.getId(), d.getStatus().name()));
    }

    @PostMapping("/donations/{id}/claim")
    public ResponseEntity<ClaimDto> claim(@PathVariable Long id) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");

        Claim claim = donationService.claim(id, me);
        Long convId = messageService.find(ConversationKind.CLAIM, claim.getId())
                .map(Conversation::getId).orElse(null);

        return ResponseEntity.ok(ApiDtoMapper.toClaimDto(claim, features.isAddress() || features.isShipping(), convId));
    }

    @PostMapping("/donations/{id}/close")
    public ResponseEntity<Void> closeDonation(@PathVariable Long id) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        donationService.close(id, me);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/donations/{id}/reopen")
    public ResponseEntity<Void> reopenDonation(@PathVariable Long id) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        donationService.reopen(id, me);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/donations/{id}")
    public ResponseEntity<Void> deleteDonation(@PathVariable Long id) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        donationService.delete(id, me);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/donations/{id}/to-swap")
    public ResponseEntity<IdStatusDto> moveToSwap(@PathVariable Long id, @RequestBody(required = false) CreateSwapBookBody body) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        String note = body != null ? body.note() : null;
        SwapBook sb = donationService.moveToSwap(id, me, note);
        return ResponseEntity.ok(new IdStatusDto(sb.getId(), sb.getStatus().name()));
    }

    @GetMapping("/my/donations")
    public ResponseEntity<List<MyDonationDto>> myDonations() {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");

        List<DonationView> views = donationService.myDonations(me);
        if (views.isEmpty()) return ResponseEntity.ok(List.of());

        // Talepler ve sohbet kimlikleri bağış başına değil, toplu okunur
        Map<Long, List<Claim>> talepler = donationService
                .findClaimsByDonations(views.stream().map(DonationView::donation).toList()).stream()
                .collect(Collectors.groupingBy(c -> c.getDonation().getId()));
        Map<Long, Long> sohbetler = messageService.conversationIds(ConversationKind.CLAIM,
                talepler.values().stream().flatMap(List::stream).map(Claim::getId).toList());
        boolean adresGorunur = features.isAddress() || features.isShipping();

        List<MyDonationDto> dtos = views.stream().map(v -> {
            List<ClaimDto> claimDtos = talepler.getOrDefault(v.getId(), List.of()).stream()
                    .map(c -> ApiDtoMapper.toClaimDto(c, adresGorunur, sohbetler.get(c.getId())))
                    .toList();
            return ApiDtoMapper.toMyDonationDto(v, claimDtos);
        }).toList();

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/my/claims")
    public ResponseEntity<List<MyClaimDto>> myClaims() {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");

        List<Claim> claims = donationService.findClaimsByStudent(me);
        Map<Long, Long> sohbetler = messageService.conversationIds(ConversationKind.CLAIM,
                claims.stream().map(Claim::getId).toList());
        List<MyClaimDto> dtos = claims.stream()
                .map(c -> ApiDtoMapper.toMyClaimDto(c, sohbetler.get(c.getId())))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/claims/{id}/ship")
    public ResponseEntity<Void> shipClaim(@PathVariable Long id) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        donationService.ship(id, me);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/meeting")
    public ResponseEntity<Void> arrangeMeeting(@PathVariable Long id, @Valid @RequestBody ArrangeMeetingBody body) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");

        Instant at = Instant.parse(body.at());
        MeetingRequest req = new MeetingRequest(body.pointId(), body.note(), at);
        donationService.arrange(id, me, req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/no-show")
    public ResponseEntity<Void> noShow(@PathVariable Long id) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        donationService.noShow(id, me);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/deliver")
    public ResponseEntity<Void> deliverClaim(@PathVariable Long id) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        donationService.deliver(id, me);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/thank")
    public ResponseEntity<Void> thankClaim(@PathVariable Long id, @RequestBody(required = false) ThankBody body) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        String message = body != null ? body.message() : null;
        donationService.thank(id, me, message);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/cancel")
    public ResponseEntity<Void> cancelClaim(@PathVariable Long id) {
        User me = CurrentUser.get();
        if (me == null) throw new IllegalStateException("Giriş yapmalısınız.");
        donationService.cancelClaim(id, me);
        return ResponseEntity.noContent().build();
    }
}
