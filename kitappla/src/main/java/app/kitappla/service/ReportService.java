package app.kitappla.service;

import app.kitappla.domain.*;
import app.kitappla.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Şikâyet kaydı ve yönetim tarafındaki inceleme.
 * <p>
 * Şikâyet, yöneticiye ilgili içeriği görme yetkisi açar. Sohbetler için bu
 * kritik: yönetici yalnızca <b>açık şikâyeti olan</b> bir sohbetin mesajlarını
 * okuyabilir ({@link #canAdminRead(ConversationKind, Long)} değil,
 * {@link #hasOpenReport}). Diğer sohbetler yönetime de kapalıdır.
 */
@Service
public class ReportService {

    private static final int MAX_NOT = 1000;

    private final ReportRepository reports;
    private final ConversationRepository conversations;
    private final DonationRepository donations;
    private final BookRequestRepository requests;
    private final SwapBookRepository swapBooks;
    private final ClaimRepository claims;
    private final SwapOfferRepository offers;
    private final UserRepository users;
    private final NotificationService notifications;

    public ReportService(ReportRepository reports, ConversationRepository conversations,
                         DonationRepository donations, BookRequestRepository requests,
                         SwapBookRepository swapBooks, ClaimRepository claims,
                         SwapOfferRepository offers, UserRepository users,
                         NotificationService notifications) {
        this.reports = reports;
        this.conversations = conversations;
        this.donations = donations;
        this.requests = requests;
        this.swapBooks = swapBooks;
        this.claims = claims;
        this.offers = offers;
        this.users = users;
        this.notifications = notifications;
    }

    // ---------- Şikâyet oluşturma ----------

    /**
     * Şikâyeti kaydeder. Şikâyet eden kişinin o içeriğe gerçekten erişimi
     * olmalıdır; sohbet şikâyetinde taraf olmayan biri şikâyet edemez.
     */
    @Transactional
    public Report create(User reporter, ReportKind kind, Long refId, ReportReason reason, String note) {
        if (reason == null) throw new IllegalStateException("Şikâyet gerekçesi seçilmeli.");

        User sahibi = dogrulaVeSahibiBul(reporter, kind, refId);
        if (sahibi != null && sahibi.getId().equals(reporter.getId()))
            throw new IllegalStateException("Kendi içeriğini şikâyet edemezsin.");

        if (reports.existsByReporterAndKindAndRefIdAndStatus(reporter, kind, refId, ReportStatus.OPEN))
            throw new IllegalStateException("Bunu zaten şikâyet ettin; yönetim inceliyor.");

        String aciklama = note == null ? null : note.trim();
        if (aciklama != null && aciklama.isEmpty()) aciklama = null;
        if (aciklama != null && aciklama.length() > MAX_NOT) aciklama = aciklama.substring(0, MAX_NOT);

        Report r = new Report();
        r.setReporter(reporter);
        r.setKind(kind);
        r.setRefId(refId);
        r.setReason(reason);
        r.setNote(aciklama);
        r.setReportedUser(sahibi);
        reports.save(r);

        // Yöneticilere haber ver
        users.findByAdminTrue().forEach(a ->
                notifications.notify(a, "sikayet",
                        reporter.getName() + " bir " + turAdi(kind) + " şikâyet etti: " + reason.getEtiket(),
                        "/admin/sikayet/" + r.getId()));
        return r;
    }

    /**
     * Şikâyet formunu açmadan önce kullanıcının içeriğe erişimi olup olmadığını doğrular.
     */
    public void validateCanReport(User reporter, ReportKind kind, Long refId) {
        if (reporter == null) throw new IllegalStateException("Giriş yapmalısınız.");
        if (kind == null || refId == null) throw new IllegalArgumentException("Geçersiz şikâyet hedefi.");
        User sahibi = dogrulaVeSahibiBul(reporter, kind, refId);
        if (sahibi != null && sahibi.getId().equals(reporter.getId()))
            throw new IllegalStateException("Kendi içeriğini şikâyet edemezsin.");
    }

    /** Erişim denetimi + şikâyet edilen içeriğin sahibini bulur. */
    private User dogrulaVeSahibiBul(User reporter, ReportKind kind, Long refId) {
        switch (kind) {
            case CONVERSATION -> {
                Conversation c = conversations.findByIdWithUsers(refId)
                        .orElseThrow(() -> new IllegalStateException("Sohbet bulunamadı."));
                if (!c.has(reporter))
                    throw new IllegalStateException("Bu sohbet sana ait değil.");
                return c.other(reporter);
            }
            case DONATION -> {
                Donation d = donations.findByIdWithDetails(refId)
                        .orElseThrow(() -> new IllegalStateException("Bağış bulunamadı."));
                return d.getDonor();
            }
            case REQUEST -> {
                BookRequest r = requests.findByIdWithDetails(refId)
                        .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));
                if (r.getFulfilledBy() != null) {
                    if (reporter.getId().equals(r.getStudent().getId())) {
                        return r.getFulfilledBy();
                    } else if (reporter.getId().equals(r.getFulfilledBy().getId())) {
                        return r.getStudent();
                    }
                }
                return r.getStudent();
            }
            case CLAIM -> {
                Claim c = claims.findByIdWithDetails(refId)
                        .orElseThrow(() -> new IllegalStateException("Teslimat kaydı bulunamadı."));
                User donor = c.getDonation().getDonor();
                User student = c.getStudent();
                if (reporter.getId().equals(student.getId())) {
                    return donor;
                } else if (reporter.getId().equals(donor.getId())) {
                    return student;
                } else {
                    throw new IllegalStateException("Bu teslimat kaydı sana ait değil.");
                }
            }
            case SWAP_BOOK -> {
                SwapBook s = swapBooks.findByIdWithDetails(refId)
                        .orElseThrow(() -> new IllegalStateException("Takas ilanı bulunamadı."));
                return s.getUser();
            }
            case SWAP_OFFER -> {
                SwapOffer o = offers.findByIdWithDetails(refId)
                        .orElseThrow(() -> new IllegalStateException("Takas teklifi bulunamadı."));
                if (reporter.getId().equals(o.getFromUser().getId())) {
                    return o.getToUser();
                } else if (reporter.getId().equals(o.getToUser().getId())) {
                    return o.getFromUser();
                } else {
                    throw new IllegalStateException("Bu takas süreci sana ait değil.");
                }
            }
            case USER -> {
                return users.findById(refId)
                        .orElseThrow(() -> new IllegalStateException("Üye bulunamadı."));
            }
            default -> throw new IllegalStateException("Bilinmeyen şikâyet türü.");
        }
    }

    /** Bildirim cümlesindeki çekimli biçim: "bir <tür> şikâyet etti". */
    private static String turAdi(ReportKind k) {
        return switch (k) {
            case CONVERSATION -> "sohbeti";
            case DONATION -> "bağış ilanını";
            case REQUEST -> "isteği / teslimatı";
            case CLAIM -> "teslimatı";
            case SWAP_BOOK -> "takas ilanını";
            case SWAP_OFFER -> "takas sürecini";
            case USER -> "üyeyi";
        };
    }

    /** Şikâyet edenin kendi kayıtları (en yeni önce). */
    public List<Report> mine(User reporter) {
        return reports.findByReporterWithUsers(reporter);
    }

    // ---------- Yönetim ----------

    public List<Report> open() {
        return reports.findByStatusWithUsers(ReportStatus.OPEN);
    }

    public List<Report> all() {
        return reports.findAllWithUsers();
    }

    public long openCount() {
        return reports.countByStatus(ReportStatus.OPEN);
    }

    public Optional<Report> find(Long id) {
        return reports.findByIdWithUsers(id);
    }

    /**
     * Yöneticinin bu içeriği görme yetkisi var mı? Açık şikâyet yoksa yoktur.
     * Sohbet mesajlarının gizliliği buna dayanır.
     */
    public boolean hasOpenReport(ReportKind kind, Long refId) {
        return reports.existsByKindAndRefIdAndStatus(kind, refId, ReportStatus.OPEN);
    }

    /** Şikâyeti sonuçlandırır ve şikâyet edene bilgi verir. */
    @Transactional
    public Report resolve(Long reportId, User admin, boolean actioned, String adminNote) {
        Report r = reports.findByIdWithUsers(reportId)
                .orElseThrow(() -> new IllegalStateException("Şikâyet bulunamadı."));
        if (r.getStatus() != ReportStatus.OPEN)
            throw new IllegalStateException("Bu şikâyet zaten sonuçlandırılmış.");

        String not = adminNote == null ? null : adminNote.trim();
        if (not != null && not.isEmpty()) not = null;
        if (not != null && not.length() > MAX_NOT) not = not.substring(0, MAX_NOT);

        r.setStatus(actioned ? ReportStatus.ACTIONED : ReportStatus.DISMISSED);
        r.setReviewedBy(admin);
        r.setReviewedAt(Instant.now());
        r.setAdminNote(not);
        reports.save(r);

        String msg = actioned
                ? "Şikâyetin incelendi ve gerekli işlem yapıldı. Bildirdiğin için teşekkürler."
                : "Şikâyetin incelendi; kurallara aykırı bir durum görülmedi.";
        if (not != null && !not.isBlank()) {
            msg += " Not: " + not.trim();
        }
        notifications.notify(r.getReporter(), "sikayet_sonuc", msg, "/sikayetlerim");
        return r;
    }

    /**
     * Üye silinmeden önce şikâyetlerdeki dış anahtar bağlarını temizler.
     */
    @Transactional
    public void nullifyUserReferences(User u) {
        if (u == null) return;
        reports.nullifyReportedUser(u);
        reports.nullifyReviewedBy(u);
    }

    public Optional<Claim> findClaim(Long id) {
        return claims.findByIdWithDetails(id);
    }

    public Optional<BookRequest> findRequest(Long id) {
        return requests.findByIdWithDetails(id);
    }

    public Optional<SwapOffer> findOffer(Long id) {
        return offers.findByIdWithDetails(id);
    }

    public Optional<Donation> findDonation(Long id) {
        return donations.findByIdWithDetails(id);
    }

    public Optional<SwapBook> findSwapBook(Long id) {
        return swapBooks.findByIdWithDetails(id);
    }
}
