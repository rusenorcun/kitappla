package app.kitappla.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.Map;
import app.kitappla.config.Features;
import app.kitappla.domain.*;
import app.kitappla.repo.ClaimRepository;
import app.kitappla.repo.SwapBookRepository;
import app.kitappla.repo.SwapOfferRepository;
import app.kitappla.repo.UserRepository;
import app.kitappla.repo.DonationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Bağış okuma tarafı ve "kitabı alabilir mi?" kuralları.
 * Kurallar tek yerde tutulur; şablonlar buton durumunu, Faz 2'deki claim işlemi
 * ise fiilî engellemeyi buradan alır.
 */
@Service
public class DonationService {

    static final String YONETIM_KALDIRDI = "Bu bağış yönetim tarafından kaldırıldı; yeniden açılamaz.";

    static final String NO_SHOW_KAPALI =
            "Bu kayıt gelinmedi bildirimiyle kapandı; kitap yeniden başkalarına açıldı.";

    static final String IPTAL_KAPALI =
            "Bu talep, taraflardan biri askıya alındığı için iptal edildi.";

    private final Features features;
    private final MeetingService meetings;
    private final PickupPointService points;
    private final UserRepository users;
    private final DonationRepository donations;
    private final ClaimRepository claims;
    private final SwapBookRepository swapBooks;
    private final SwapOfferRepository offers;
    private final QuotaService quotaService;
    private final NotificationService notifications;
    private final app.kitappla.repo.ConversationRepository conversations;

    public DonationService(Features features, MeetingService meetings, PickupPointService points,
                           UserRepository users,
                           DonationRepository donations, ClaimRepository claims,
                           SwapBookRepository swapBooks, SwapOfferRepository offers,
                           QuotaService quotaService, NotificationService notifications,
                           app.kitappla.repo.ConversationRepository conversations) {
        this.conversations = conversations;
        this.features = features;
        this.meetings = meetings;
        this.points = points;
        this.users = users;
        this.donations = donations;
        this.claims = claims;
        this.swapBooks = swapBooks;
        this.offers = offers;
        this.quotaService = quotaService;
        this.notifications = notifications;
    }

    /** Bağış filtresi. Boş alanlar "filtreleme yok" demektir. */
    public record Filter(TargetLevel level, String query, boolean onlyAvailable) {
        public static Filter none() { return new Filter(null, null, true); }
    }

    private static DonationView toView(Donation d, long claimed) {
        return new DonationView(d, claimed, Math.max(0, d.getQuantity() - claimed));
    }

    private DonationView toView(Donation d) {
        return toView(d, claims.countByDonationAndStatusNotIn(d, ClaimStatus.ADET_TUTMAYAN));
    }

    /** Görünümleri kurar; kalan adetler bağış başına değil, tek sorguda hesaplanır. */
    private List<DonationView> toViews(List<Donation> list) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(Donation::getId).toList();
        Map<Long, Long> alinan = claims.countActiveByDonationIds(ids).stream()
                .collect(Collectors.toMap(ClaimRepository.AlinanAdet::getDonationId, ClaimRepository.AlinanAdet::getAdet));
        return list.stream().map(d -> toView(d, alinan.getOrDefault(d.getId(), 0L))).toList();
    }

    /** Açık bağışlar, en yeni önce. Filtreler ve sayfalama veritabanında uygulanır. */
    @Transactional(readOnly = true)
    public Page<DonationView> openDonations(Filter filter, Pageable pageable) {
        Filter f = filter == null ? Filter.none() : filter;
        Page<Donation> sayfa = donations.findOpenPage(
                f.level() == null, seviyeler(f.level()), Arama.desen(f.query()), f.onlyAvailable(), pageable);
        return new PageImpl<>(toViews(sayfa.getContent()), pageable, sayfa.getTotalElements());
    }

    /** Sayfalamasız tüm liste (mobil uygulamanın mevcut sürümü ve demo senaryosu kullanır). */
    @Transactional(readOnly = true)
    public List<DonationView> openDonations(Filter filter) {
        return openDonations(filter, Pageable.unpaged()).getContent();
    }

    /** Seçilen seviyeye uygun hedefler: seviyenin kendisi ve "hepsi"ne açık bağışlar. Seçim yoksa tümü. */
    private static List<TargetLevel> seviyeler(TargetLevel level) {
        return level == null ? List.of(TargetLevel.values()) : List.of(level, TargetLevel.HEPSI);
    }


    @Transactional(readOnly = true)
    public Optional<DonationView> view(Long id) {
        return donations.findByIdWithDetails(id).map(this::toView);
    }

    /**
     * Detay sayfası için görünüm. Yönetimin yayından kaldırdığı bağış (uygunsuz açıklama,
     * kapak vb.) doğrudan bağlantıyla da açılmaz; yalnızca bağışçı ve yöneticiler görür.
     */
    @Transactional(readOnly = true)
    public Optional<DonationView> view(Long id, User viewer) {
        return view(id).filter(v -> !v.donation().isRemovedByAdmin()
                || (viewer != null && (viewer.isAdmin() || v.donation().getDonor().getId().equals(viewer.getId()))));
    }

    /** Kullanıcının bir liste boyunca değişmeyen durumu: bir kez okunur, her bağışta yeniden sorgulanmaz. */
    private record AliciDurumu(Set<Long> talepEttigiBagislar, String kotaEngeli) {}

    private AliciDurumu aliciDurumu(User user) {
        return new AliciDurumu(claims.findDonationIdsByStudent(user), quotaService.cannotReceiveReason(user));
    }

    /** Kullanıcı bu bağıştan kitap alabilir mi? */
    @Transactional(readOnly = true)
    public ClaimEligibility eligibility(DonationView view, User user) {
        return eligibility(view, user, user == null ? null : aliciDurumu(user));
    }

    /** Liste için uygunluklar (anahtar: bağış kimliği). Kullanıcı başına sabit sayıda sorgu çalışır. */
    @Transactional(readOnly = true)
    public Map<Long, ClaimEligibility> eligibilities(List<DonationView> views, User user) {
        AliciDurumu durum = user == null ? null : aliciDurumu(user);
        return views.stream().collect(Collectors.toMap(DonationView::getId, v -> eligibility(v, user, durum)));
    }

    /** Kurallar tek yerde. Sıra önemlidir: en açıklayıcı sebep döner. */
    private ClaimEligibility eligibility(DonationView view, User user, AliciDurumu durum) {
        if (view == null) return ClaimEligibility.deny("NOT_FOUND", "Bağış bulunamadı.");
        if (user == null) return ClaimEligibility.deny("LOGIN_REQUIRED", "Kitap almak için giriş yapmalısın.");
        if (user.isBlocked()) return ClaimEligibility.deny("BLOCKED", "Hesabın engellenmiş.");

        Donation d = view.donation();
        if (d.getStatus() != DonationStatus.OPEN || !view.isAvailable())
            return ClaimEligibility.deny("SOLD_OUT", "Bu bağışta kalan kitap yok.");

        if (d.getDonor().getId().equals(user.getId()))
            return ClaimEligibility.deny("OWN_DONATION", "Kendi bağışından kitap alamazsın.");

        // Askıdaki bağışçı giriş yapamaz: kitabı teslim edemeyeceği bir talep açılmasın
        if (d.getDonor().isBlocked())
            return ClaimEligibility.deny("DONOR_UNAVAILABLE", "Bu bağış şu an alınamıyor.");

        // Adres yalnızca kargo akışında gerekir; kampüs içi yüz yüze teslimde istenmez
        if (features.isAddress() && (user.getAddress() == null || user.getAddress().isBlank()))
            return ClaimEligibility.deny("ADDRESS_REQUIRED", "Önce profilinden teslimat adresi eklemelisin.");

        if (d.getTargetLevel() != TargetLevel.HEPSI && d.getTargetLevel() != asTarget(user.getSchoolLevel()))
            return ClaimEligibility.deny("LEVEL_MISMATCH", "Bu bağış senin okul seviyene uygun değil.");

        // Öğrenci önceliği: pencere açıkken yalnızca onaylı öğrenciler alabilir
        if (view.isPriorityActive() && !user.isStudent())
            return ClaimEligibility.deny("PRIORITY_WINDOW",
                    "Bu bağış şu an öğrencilere öncelikli. Üyelere " + view.getPriorityLeft() + " sonra açılacak.");

        if (durum.talepEttigiBagislar().contains(d.getId()))
            return ClaimEligibility.deny("ALREADY_CLAIMED", "Bu bağıştan zaten bir kitap aldın.");

        if (durum.kotaEngeli() != null) return ClaimEligibility.deny("QUOTA_FULL", durum.kotaEngeli());

        return ClaimEligibility.ok();
    }

    /**
     * Kullanıcı bağıştan bir kitap alır. Kurallar {@link #eligibility} ile aynı kaynaktan gelir;
     * adet kontrolü yarış durumlarına karşı işlem içinde yeniden yapılır.
     *
     * @return oluşturulan talep
     * @throws IllegalStateException kural ihlalinde (mesaj kullanıcıya gösterilebilir)
     */
    @Transactional
    public Claim claim(Long donationId, User user) {
        Donation d = donations.findByIdWithDetailsForUpdate(donationId)
                .orElseThrow(() -> new IllegalStateException("Bağış bulunamadı."));
        // Kota üye başınadır ama bağış kilidi bağış başına: farklı bağışlara eşzamanlı
        // taleplerin ikisi de eski kota sayısını görüp sınırı aşabiliyordu. Üye satırı da
        // kilitlenir; sayım kilit alındıktan sonra yapıldığı için commit edilmiş talebi görür.
        users.findByIdForUpdate(user.getId());

        DonationView v = toView(d);
        ClaimEligibility e = eligibility(v, user);
        if (!e.allowed()) throw new IllegalStateException(e.reason());

        // Yarış durumu: adet kontrolünü işlem içinde tekrarla
        long taken = claims.countByDonationAndStatusNotIn(d, ClaimStatus.ADET_TUTMAYAN);
        if (taken >= d.getQuantity()) throw new IllegalStateException("Bu bağışta kalan kitap yok.");

        Claim c = new Claim();
        c.setDonation(d);
        c.setStudent(user);
        c = claims.save(c);

        if (taken + 1 >= d.getQuantity()) {
            d.setStatus(DonationStatus.CLOSED);
            donations.save(d);
        }

        notifications.notify(d.getDonor(), "donation_claimed",
                user.getName() + ", \"" + d.getBook().getTitle() + "\" bağışından bir kitap aldı.",
                "/bagislarim");
        return c;
    }

    // ---------- Bağış oluşturma / yönetimi ----------

    /** Yeni bağış yayınlar. Kitap zaten BookService ile bulunmuş/oluşturulmuş olmalıdır. */
    @Transactional
    public Donation create(User donor, Book book, int quantity, TargetLevel level,
                           DonationSource source, String description) {
        return create(donor, book, quantity, level, source, description, null, null);
    }

    /** Kampüs teslimi için bağışçının önerdiği noktayla birlikte. */
    @Transactional
    public Donation create(User donor, Book book, int quantity, TargetLevel level,
                           DonationSource source, String description,
                           Long preferredPointId, String preferredPointNote) {
        if (book == null) throw new IllegalStateException("Kitap seçilmedi.");
        if (quantity < 1) throw new IllegalStateException("Adet en az 1 olmalı.");
        if (quantity > 50) throw new IllegalStateException("Tek seferde en fazla 50 adet bağışlayabilirsin.");
        if (features.isAddress() && (donor.getAddress() == null || donor.getAddress().isBlank()))
            throw new IllegalStateException("Bağış yapmadan önce profilinden iletişim/teslimat adresi eklemelisin.");

        Donation d = new Donation();
        d.setDonor(donor);
        d.setBook(book);
        d.setQuantity(quantity);
        d.setTargetLevel(level == null ? TargetLevel.HEPSI : level);
        d.setSource(source == null ? DonationSource.PURCHASE : source);
        d.setDescription(Metin.kisalt(description, 500));

        // Kampüs içi teslimde bağışçı bir nokta önerir; taraflar sonra değiştirebilir
        if (preferredPointId != null) {
            d.setPreferredPoint(points.findSelectable(preferredPointId).orElseThrow(
                    () -> new IllegalStateException("Seçtiğin teslim noktası kullanılmıyor.")));
        }
        String not = preferredPointNote == null ? null : preferredPointNote.trim();
        d.setPreferredPointNote(not == null || not.isEmpty() ? null
                : (not.length() > 300 ? not.substring(0, 300) : not));

        return donations.save(d);
    }

    @Transactional(readOnly = true)
    public List<DonationView> myDonations(User donor) {
        return toViews(donations.findByDonorWithDetails(donor));
    }

    /** Bağışçının kendi bağışını kapatması (yeni talep alınmaz). */
    @Transactional
    public void close(Long donationId, User donor) {
        Donation d = ownDonation(donationId, donor);
        d.setStatus(DonationStatus.CLOSED);
        donations.save(d);
    }

    /** Bağışçının kendi bağışını yeniden açması. */
    @Transactional
    public void reopen(Long donationId, User donor) {
        Donation d = ownDonation(donationId, donor);
        if (d.isRemovedByAdmin())
            throw new IllegalStateException(YONETIM_KALDIRDI);
        long taken = claims.countByDonationAndStatusNotIn(d, ClaimStatus.ADET_TUTMAYAN);
        if (taken >= d.getQuantity())
            throw new IllegalStateException("Bu bağışta kalan kitap yok; yeniden açılamaz.");
        d.setStatus(DonationStatus.OPEN);
        donations.save(d);
    }

    /** Talep alınmamış bağışı siler. */
    @Transactional
    public void delete(Long donationId, User donor) {
        Donation d = ownDonation(donationId, donor);
        if (claims.countByDonation(d) > 0)
            throw new IllegalStateException("Talep alınmış bir bağış silinemez; bunun yerine kapatabilirsin.");
        donations.delete(d);
    }

    /** Bağıştaki kitabı takasa aktarır ve bağıştan kaldırır. */
    @Transactional
    public SwapBook moveToSwap(Long donationId, User donor, String note) {
        Donation d = ownDonation(donationId, donor);
        // Yönetimin kaldırdığı ilan takas ilanı olarak yeniden yayına girmesin
        if (d.isRemovedByAdmin())
            throw new IllegalStateException(YONETIM_KALDIRDI);
        if (claims.countByDonation(d) > 0)
            throw new IllegalStateException("Talep alınmış bir bağış takasa aktarılamaz.");
        if (features.isAddress() && (donor.getAddress() == null || donor.getAddress().isBlank()))
            throw new IllegalStateException("Takas için profilinden teslimat adresi eklemelisin.");

        var existing = swapBooks.findByUserAndBook_Id(donor, d.getBook().getId());
        SwapBook sb;
        if (existing.isPresent()) {
            sb = existing.get();
            // Üye+kitap başına tek ilan satırı var (uq_swap_books_user_book). Satır daha önce
            // kabul edilmiş bir takasa bağlıysa burada yeniden açılması, SwapService.setStatus'ın
            // engellediği "elden çıkan kitabı yeniden takasa açma" durumunun arka kapısı olurdu.
            if (offers.countByBookAndStatuses(sb, SwapService.TAKASLANMIS) > 0)
                throw new IllegalStateException(
                        "Bu kitabı daha önce takasladın; aynı kitap yeniden takasa açılamaz.");
            if (sb.isRemovedByAdmin())
                throw new IllegalStateException(SwapService.YONETIM_KALDIRDI);
            sb.setStatus(SwapBookStatus.OPEN);
            if (note != null && !note.isBlank()) {
                sb.setNote(Metin.kisalt(note, 300));
            }
            sb = swapBooks.save(sb);
        } else {
            sb = new SwapBook();
            sb.setUser(donor);
            sb.setBook(d.getBook());
            // Bağış açıklaması 500, takas notu 300 karakter: aktarılırken kırpılır
            sb.setNote(Metin.kisalt(note != null && !note.isBlank() ? note : d.getDescription(), 300));
            sb.setStatus(SwapBookStatus.OPEN);
            sb = swapBooks.save(sb);
        }

        donations.delete(d);
        return sb;
    }

    private Donation ownDonation(Long donationId, User donor) {
        Donation d = donations.findByIdWithDetails(donationId)
                .orElseThrow(() -> new IllegalStateException("Bağış bulunamadı."));
        if (!d.getDonor().getId().equals(donor.getId()))
            throw new IllegalStateException("Bu bağış sana ait değil.");
        return d;
    }

    // ---------- Teslimat akışı ----------

    /** Bağışçı kargoya verdi. Yalnızca kargo akışı açıkken kullanılır. */
    @Transactional
    public void ship(Long claimId, User donor) {
        Claim c = claimOfDonor(claimId, donor);
        if (!features.isShipping())
            throw new IllegalStateException("Kargo akışı kapalı; teslim kampüste yüz yüze yapılır.");
        if (c.getStatus() != ClaimStatus.MATCHED)
            throw new IllegalStateException("Bu kayıt zaten kargolanmış.");
        c.setStatus(ClaimStatus.SHIPPED);
        c.setShippedAt(Instant.now());
        claims.save(c);
        notifications.notify(c.getStudent(), "claim_shipped",
                "\"" + c.getDonation().getBook().getTitle() + "\" kitabın kargoya verildi.",
                "/aldiklarim");
    }

    /**
     * Buluşmayı ayarlar ya da günceller. Kampüs içi teslimde taraflardan
     * <b>ikisi de</b> yapabilir: bağışçı bir yer önerir, alıcı mesajlaşma
     * sonrasında değiştirebilir.
     */
    @Transactional
    public Claim arrange(Long claimId, User user, MeetingRequest request) {
        Claim c = claims.findByIdWithDetails(claimId)
                .orElseThrow(() -> new IllegalStateException("Kayıt bulunamadı."));

        boolean bagisci = c.getDonation().getDonor().getId().equals(user.getId());
        boolean alici = c.getStudent().getId().equals(user.getId());
        if (!bagisci && !alici)
            throw new IllegalStateException("Bu kayıt sana ait değil.");
        if (c.getStatus() == ClaimStatus.DELIVERED)
            throw new IllegalStateException("Bu kitap zaten teslim edildi.");
        // Gelinmedi bildirimi slotu serbest bırakıp bağışı yeniden açar; kayıt buradan
        // diriltilirse aynı kitap hem yeni alıcıya hem eskisine ayrılmış olur (adet aşımı).
        if (c.getStatus() == ClaimStatus.NO_SHOW)
            throw new IllegalStateException(NO_SHOW_KAPALI);
        if (c.getStatus() == ClaimStatus.CANCELLED)
            throw new IllegalStateException(IPTAL_KAPALI);

        meetings.apply(c.getMeeting(), request);
        c.setStatus(ClaimStatus.ARRANGED);
        claims.save(c);

        User digeri = bagisci ? c.getStudent() : c.getDonation().getDonor();
        notifications.notify(digeri, "meeting_arranged",
                "\"" + c.getDonation().getBook().getTitle() + "\" için buluşma ayarlandı: "
                        + meetings.summary(c.getMeeting()),
                bagisci ? "/aldiklarim" : "/bagislarim");
        return c;
    }

    /**
     * Karşı taraf buluşmaya gelmedi. Yalnızca buluşma saati geçtikten sonra ve
     * yalnızca alışverişin diğer tarafınca bildirilebilir.
     * <p>
     * Kitap havuza geri döner (kalan adet hesabına dahil edilmez) ama alıcının
     * kota hakkı yanar — aksi hâlde gelmemek, kotayı sıfırlamanın yolu olurdu.
     */
    @Transactional
    public Claim noShow(Long claimId, User bildiren) {
        Claim c = claims.findByIdWithDetails(claimId)
                .orElseThrow(() -> new IllegalStateException("Kayıt bulunamadı."));

        boolean bagisci = c.getDonation().getDonor().getId().equals(bildiren.getId());
        boolean alici = c.getStudent().getId().equals(bildiren.getId());
        if (!bagisci && !alici) throw new IllegalStateException("Bu kayıt sana ait değil.");

        if (c.getStatus() == ClaimStatus.DELIVERED)
            throw new IllegalStateException("Bu kitap zaten teslim edilmiş.");
        if (c.getStatus() == ClaimStatus.NO_SHOW)
            throw new IllegalStateException("Bu kayıt zaten gelinmedi olarak işaretlenmiş.");
        if (c.getStatus() == ClaimStatus.CANCELLED)
            throw new IllegalStateException(IPTAL_KAPALI);
        if (!c.getMeeting().isArranged())
            throw new IllegalStateException("Önce bir buluşma ayarlanmış olmalı.");
        if (c.getMeeting().getAt() != null && Instant.now().isBefore(c.getMeeting().getAt()))
            throw new IllegalStateException("Buluşma saati daha gelmedi.");

        Donation d = c.getDonation();
        boolean doluydu = dolduguIcinKapali(d);
        c.setStatus(ClaimStatus.NO_SHOW);
        claims.save(c);
        conversations.findByKindAndRefId(ConversationKind.CLAIM, claimId).ifPresent(k -> {
            k.archive();
            conversations.save(k);
        });

        if (doluydu) {
            d.setStatus(DonationStatus.OPEN);
            donations.save(d);
        }

        User gelmeyen = bagisci ? c.getStudent() : c.getDonation().getDonor();
        gelmeyen.setNoShowCount(gelmeyen.getNoShowCount() + 1);
        users.save(gelmeyen);

        String kitap = c.getDonation().getBook().getTitle();
        notifications.notify(gelmeyen, "gelmedi",
                "\"" + kitap + "\" buluşmasına gelmediğin bildirildi. "
                        + "Tekrarlanırsa hesabın askıya alınabilir.",
                bagisci ? "/aldiklarim" : "/bagislarim");
        notifications.notify(bildiren, "gelmedi_kayit",
                "\"" + kitap + "\" için gelinmedi bildirimin kaydedildi. Kitap yeniden başkalarına açıldı.",
                bagisci ? "/bagislarim" : "/aldiklarim");
        return c;
    }

    /** Alıcı teslim aldı. */
    @Transactional
    public void deliver(Long claimId, User receiver) {
        Claim c = claimOfReceiver(claimId, receiver);
        if (c.getStatus() == ClaimStatus.DELIVERED)
            throw new IllegalStateException("Bu kitabı zaten teslim aldın.");
        if (c.getStatus() == ClaimStatus.NO_SHOW)
            throw new IllegalStateException(NO_SHOW_KAPALI);
        if (c.getStatus() == ClaimStatus.CANCELLED)
            throw new IllegalStateException(IPTAL_KAPALI);
        // Yüz yüze teslimde önce buluşma ayarlanmış olmalı; kargo modunda gerekmez
        if (features.isHandover() && !features.isShipping() && c.getStatus() == ClaimStatus.MATCHED)
            throw new IllegalStateException("Önce buluşma ayarlayın, sonra teslimi onaylayın.");
        c.setStatus(ClaimStatus.DELIVERED);
        c.setDeliveredAt(Instant.now());
        claims.save(c);
        notifications.notify(c.getDonation().getDonor(), "claim_delivered",
                receiver.getName() + ", \"" + c.getDonation().getBook().getTitle() + "\" kitabını teslim aldı.",
                "/bagislarim");
    }

    /** Alıcı teslim sonrası bağışçıya teşekkür eder. */
    @Transactional
    public void thank(Long claimId, User receiver, String message) {
        Claim c = claimOfReceiver(claimId, receiver);
        if (c.getStatus() != ClaimStatus.DELIVERED)
            throw new IllegalStateException("Teşekkür notu yalnızca teslim aldığın kitaplar için gönderilebilir.");
        String note = (message == null || message.isBlank()) ? "" : " Notu: \"" + message.trim() + "\"";
        notifications.notify(c.getDonation().getDonor(), "thank_you",
                receiver.getName() + ", \"" + c.getDonation().getBook().getTitle() + "\" bağışın için teşekkür etti." + note,
                "/bagislarim");
    }

    /** Alıcı, kargolanmadan önce talebini iptal eder; adet geri açılır. */
    @Transactional
    public void cancelClaim(Long claimId, User receiver) {
        Claim c = claimOfReceiver(claimId, receiver);
        if (c.getStatus() != ClaimStatus.MATCHED)
            throw new IllegalStateException("Kargolanmış ya da teslim edilmiş bir talep iptal edilemez.");
        Donation d = c.getDonation();
        boolean doluydu = dolduguIcinKapali(d);
        conversations.findByKindAndRefId(ConversationKind.CLAIM, claimId).ifPresent(k -> {
            k.archive();
            conversations.save(k);
        });
        claims.delete(c);
        if (doluydu) {
            d.setStatus(DonationStatus.OPEN);
            donations.save(d);
        }
        notifications.notify(d.getDonor(), "claim_cancelled",
                receiver.getName() + ", \"" + d.getBook().getTitle() + "\" bağışından aldığı kitabı iptal etti.",
                "/bagislarim");
    }

    /**
     * Üye askıya alındığında taraf olduğu süren talepleri iptal eder (bkz. AdminService#setBlocked).
     * <p>
     * Karşı tarafın hakkı iade edilir: alıcıya kota hakkı (CANCELLED kotada sayılmaz), bağışçıya
     * ayrılan adet (bağış dolduğu için kapandıysa yeniden açılır). Kargoya verilmiş ya da teslim
     * edilmiş kayıtlara dokunulmaz: kitap artık yolda ya da el değiştirmiş. Sohbet arşivlenir ki
     * askıdaki üyeyle yazışma sürmesin.
     *
     * @return iptal edilen talep sayısı
     */
    @Transactional
    public int askiyaAlinanUyeninTalepleriniIptalEt(User askida) {
        List<Claim> surenler = claims.findTarafOlduguByStatus(askida,
                List.of(ClaimStatus.MATCHED, ClaimStatus.ARRANGED));
        for (Claim c : surenler) {
            // Adet hesabı eşzamanlı bir talep/iptal ile yarışmasın
            Donation d = donations.findByIdWithDetailsForUpdate(c.getDonation().getId()).orElseThrow();
            boolean doluydu = dolduguIcinKapali(d);
            boolean bulusmaVardi = c.getMeeting().isArranged();
            c.setStatus(ClaimStatus.CANCELLED);
            claims.save(c);
            conversations.findByKindAndRefId(ConversationKind.CLAIM, c.getId()).ifPresent(k -> {
                k.archive();
                conversations.save(k);
            });

            String kitap = d.getBook().getTitle();
            if (d.getDonor().getId().equals(askida.getId())) {
                notifications.notify(c.getStudent(), AskiBildirimi.TUR,
                        AskiBildirimi.metin(kitap, bulusmaVardi, "talebin", true, null),
                        "/aldiklarim");
            } else {
                if (doluydu) {
                    d.setStatus(DonationStatus.OPEN);
                    donations.save(d);
                }
                notifications.notify(d.getDonor(), AskiBildirimi.TUR,
                        AskiBildirimi.metin(kitap, bulusmaVardi, "talep", true,
                                doluydu ? "Kitap yeniden bağışa açıldı." : "Ayrılan kitap bağışına geri döndü."),
                        "/bagislarim");
            }
        }
        return surenler.size();
    }

    /**
     * Bağış, adedi dolduğu için mi kapandı? Yalnızca bu durumda boşalan yer bağışı yeniden
     * açar. Bağışçının kendi kapattığı ya da yönetimin kaldırdığı bağış kapalı kalmalı.
     */
    private boolean dolduguIcinKapali(Donation d) {
        return d.getStatus() == DonationStatus.CLOSED && !d.isRemovedByAdmin()
                && claims.countByDonationAndStatusNotIn(d, ClaimStatus.ADET_TUTMAYAN) >= d.getQuantity();
    }

    private Claim claimOfDonor(Long claimId, User donor) {
        Claim c = claims.findByIdWithDetails(claimId)
                .orElseThrow(() -> new IllegalStateException("Teslimat kaydı bulunamadı."));
        if (!c.getDonation().getDonor().getId().equals(donor.getId()))
            throw new IllegalStateException("Bu teslimat kaydı sana ait değil.");
        return c;
    }

    private Claim claimOfReceiver(Long claimId, User receiver) {
        Claim c = claims.findByIdWithDetails(claimId)
                .orElseThrow(() -> new IllegalStateException("Teslimat kaydı bulunamadı."));
        if (!c.getStudent().getId().equals(receiver.getId()))
            throw new IllegalStateException("Bu teslimat kaydı sana ait değil.");
        return c;
    }

    private static TargetLevel asTarget(SchoolLevel level) {
        if (level == null) return null;
        return switch (level) {
            case ORTAOKUL -> TargetLevel.ORTAOKUL;
            case LISE -> TargetLevel.LISE;
            case UNIVERSITE -> TargetLevel.UNIVERSITE;
        };
    }

    public List<Claim> findClaimsByStudent(User student) {
        return claims.findByStudentWithDetails(student);
    }

    public List<Claim> findClaimsByDonations(List<Donation> donationList) {
        return claims.findByDonationsWithStudent(donationList);
    }
}