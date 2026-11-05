package app.kitappla.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import app.kitappla.config.Features;
import app.kitappla.domain.*;
import app.kitappla.repo.DonationRepository;
import app.kitappla.repo.SwapBookRepository;
import app.kitappla.repo.UserRepository;
import app.kitappla.repo.SwapOfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Üyeler arası kitap takası. Bağış kotasından bağımsızdır (eşit değişim).
 * Adresler yalnızca teklif KABUL edildikten sonra iki tarafa açılır.
 */
@Service
public class SwapService {

    private static final List<OfferStatus> LIVE = List.of(OfferStatus.PENDING, OfferStatus.ACCEPTED);

    /**
     * Kitabın elden çıktığı (ya da çıkmak üzere olduğu) teklif durumları. İki taraf da
     * teslim edince teklif ACCEPTED'dan COMPLETED'a geçer; yalnızca ACCEPTED'a bakan bir
     * denetim tamamlanmış takası kaçırır. Böyle bir ilan yeniden takasa açılamaz.
     */
    public static final List<OfferStatus> TAKASLANMIS = List.of(OfferStatus.ACCEPTED, OfferStatus.COMPLETED);

    static final String YONETIM_KALDIRDI = "Bu ilan yönetim tarafından kaldırıldı; yeniden açılamaz.";

    private final Features features;
    private final MeetingService meetings;
    private final PickupPointService points;
    private final UserRepository users;
    private final SwapBookRepository swapBooks;
    private final SwapOfferRepository offers;
    private final DonationRepository donations;
    private final NotificationService notifications;
    private final app.kitappla.repo.ConversationRepository conversations;

    public SwapService(Features features, MeetingService meetings, PickupPointService points,
                       UserRepository users, SwapBookRepository swapBooks,
                       SwapOfferRepository offers, DonationRepository donations,
                       NotificationService notifications,
                       app.kitappla.repo.ConversationRepository conversations) {
        this.conversations = conversations;
        this.features = features;
        this.meetings = meetings;
        this.points = points;
        this.users = users;
        this.swapBooks = swapBooks;
        this.offers = offers;
        this.donations = donations;
        this.notifications = notifications;
    }

    // ---------- Takas kitapları ----------

    /** Kitabımı takasa aç. */
    @Transactional
    public SwapBook open(User user, Book book, String note) {
        if (book == null) throw new IllegalStateException("Kitap seçilmedi.");
        if (features.isAddress() && (user.getAddress() == null || user.getAddress().isBlank()))
            throw new IllegalStateException("Takas için profilinden teslimat adresi eklemelisin.");
        if (swapBooks.findByUserAndBook_Id(user, book.getId()).isPresent())
            throw new IllegalStateException("Bu kitabı zaten takasa açtın.");

        SwapBook s = new SwapBook();
        s.setUser(user);
        s.setBook(book);
        s.setNote(Metin.kisalt(note, 300));
        return swapBooks.save(s);
    }

    /** Başkalarının takasa açtığı kitaplar, en yeni önce. Arama ve sayfalama veritabanında uygulanır. */
    @Transactional(readOnly = true)
    public Page<SwapBook> discover(User me, String query, Pageable pageable) {
        long benimId = me == null ? -1L : me.getId();   // oturumsuz istemci tüm açık ilanları görür
        return swapBooks.findOpenOfOthersPage(benimId, Arama.desen(query), pageable);
    }

    /** Sayfalamasız tüm liste (mobil uygulamanın mevcut sürümü kullanır). */
    @Transactional(readOnly = true)
    public List<SwapBook> discover(User me, String query) {
        return discover(me, query, Pageable.unpaged()).getContent();
    }

    @Transactional(readOnly = true)
    public List<SwapBook> myBooks(User user) {
        return swapBooks.findByUserWithDetails(user);
    }

    @Transactional(readOnly = true)
    public List<SwapBook> myOpenBooks(User user) {
        return swapBooks.findByUserAndStatusWithDetails(user, SwapBookStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public Optional<SwapBook> viewBook(Long id) {
        return swapBooks.findByIdWithDetails(id);
    }

    /**
     * Takas kitabını aç/kapat.
     * <p>
     * Kapatırken bekleyen teklifler de sonuçlandırılır: aksi hâlde teklif PENDING kalır ve
     * kapalı ilan sonradan kabul edilerek takasa girebilirdi.
     * <p>
     * Takası kabul edilmiş ya da tamamlanmış (elden çıkan) kitap yeniden açılamaz; yalnızca
     * kapatma engellenirken böyle bir kitap ikinci kez teklif alabiliyordu. Kabul edilmiş
     * ama henüz tamamlanmamış takastaki kitap da kapatılamaz.
     */
    @Transactional
    public void setStatus(Long swapBookId, User user, SwapBookStatus status) {
        SwapBook s = ownBook(swapBookId, user);
        if (status == SwapBookStatus.OPEN && s.isRemovedByAdmin())
            throw new IllegalStateException(YONETIM_KALDIRDI);
        if (status == SwapBookStatus.OPEN && offers.countByBookAndStatuses(s, TAKASLANMIS) > 0)
            throw new IllegalStateException("Bu kitabın takası yapıldı; yeniden takasa açılamaz.");
        if (status == SwapBookStatus.CLOSED && offers.countByBookAndStatuses(s, List.of(OfferStatus.ACCEPTED)) > 0)
            throw new IllegalStateException("Kabul edilmiş bir takasa bağlı kitap kapatılamaz.");
        s.setStatus(status);
        swapBooks.save(s);

        if (status == SwapBookStatus.CLOSED) {
            for (SwapOffer bekleyen : offers.findCompeting(OfferStatus.PENDING, -1L, List.of(s))) {
                bekleyen.setStatus(OfferStatus.REJECTED);
                bekleyen.setDecidedAt(Instant.now());
                offers.save(bekleyen);
                conversations.findByKindAndRefId(ConversationKind.SWAP, bekleyen.getId()).ifPresent(k -> {
                    k.archive();
                    conversations.save(k);
                });
                User bilgilendirilecek = bekleyen.getFromUser().getId().equals(user.getId())
                        ? bekleyen.getToUser() : bekleyen.getFromUser();
                notifications.notify(bilgilendirilecek, "swap_rejected",
                        "\"" + s.getBook().getTitle() + "\" takastan kaldırıldığı için ilgili teklif kapandı.",
                        "/takas/takaslarim");
            }
        }
    }

    /** Takastan kaldır. */
    @Transactional
    public void removeBook(Long swapBookId, User user) {
        SwapBook s = ownBook(swapBookId, user);
        if (offers.countByBookAndStatuses(s, LIVE) > 0)
            throw new IllegalStateException("Bekleyen ya da kabul edilmiş teklifi olan kitap kaldırılamaz.");
        ilandanKaldir(s);
    }

    /**
     * İlanı takastan kaldırır. Reddedilmiş/iptal edilmiş teklif satırları ilana yabancı
     * anahtarla bağlı kalır; silme denenirse işlem kısıt ihlaliyle 500'e düşüyordu. O
     * teklifler karşı tarafın geçmişinde, sohbetlerde ve şikâyetlerde referans olduğu için
     * silinmez: böyle bir ilan kapatılarak saklanır, geçmişi olmayan ilan silinir.
     */
    private void ilandanKaldir(SwapBook s) {
        if (offers.countByBookAndStatuses(s, List.of(OfferStatus.values())) > 0) {
            s.setStatus(SwapBookStatus.CLOSED);
            swapBooks.save(s);
        } else {
            swapBooks.delete(s);
        }
    }

    /** Takastaki kitabı bağışa aktarır ve takastan kaldırır. */
    @Transactional
    public Donation moveToDonation(Long swapBookId, User user, TargetLevel level, String description, Long pointId, String pointNote) {
        SwapBook s = ownBook(swapBookId, user);
        if (s.isRemovedByAdmin())
            throw new IllegalStateException(YONETIM_KALDIRDI);
        if (offers.countByBookAndStatuses(s, LIVE) > 0)
            throw new IllegalStateException("Bekleyen ya da kabul edilmiş teklifi olan kitap bağışa aktarılamaz.");
        // Takası tamamlanan kitap artık karşı tarafta; bağışa açılırsa elde olmayan kitap ilanı doğar.
        if (offers.countByBookAndStatuses(s, TAKASLANMIS) > 0)
            throw new IllegalStateException("Bu kitabın takası yapıldı; bağışa aktarılamaz.");
        if (features.isAddress() && (user.getAddress() == null || user.getAddress().isBlank()))
            throw new IllegalStateException("Bağış yapmadan önce profilinden iletişim/teslimat adresi eklemelisin.");

        Donation d = new Donation();
        d.setDonor(user);
        d.setBook(s.getBook());
        d.setQuantity(1);
        d.setTargetLevel(level == null ? TargetLevel.HEPSI : level);
        d.setSource(DonationSource.OWN);
        String desc = description != null && !description.isBlank() ? description : s.getNote();
        d.setDescription(Metin.kisalt(desc, 500));

        if (pointId != null) {
            d.setPreferredPoint(points.findSelectable(pointId).orElse(null));
        }
        String not = pointNote == null ? null : pointNote.trim();
        d.setPreferredPointNote(not == null || not.isEmpty() ? null : (not.length() > 300 ? not.substring(0, 300) : not));

        d = donations.save(d);
        ilandanKaldir(s);
        return d;
    }

    private SwapBook ownBook(Long id, User user) {
        SwapBook s = swapBooks.findByIdWithDetails(id)
                .orElseThrow(() -> new IllegalStateException("Takas kitabı bulunamadı."));
        if (!s.getUser().getId().equals(user.getId()))
            throw new IllegalStateException("Bu kitap sana ait değil.");
        return s;
    }

    // ---------- Teklifler ----------

    /** Kendi kitabımı, hedef kitabın sahibine teklif et. */
    @Transactional
    public SwapOffer offer(Long targetBookId, Long offeredBookId, User me, String message) {
        SwapBook target = swapBooks.findByIdWithDetails(targetBookId)
                .orElseThrow(() -> new IllegalStateException("Hedef kitap bulunamadı."));
        if (target.getStatus() != SwapBookStatus.OPEN)
            throw new IllegalStateException("Bu kitap artık takasa açık değil.");
        if (target.getUser().getId().equals(me.getId()))
            throw new IllegalStateException("Kendi kitabına teklif veremezsin.");
        // Askıdaki üye giriş yapamaz; teklife hiç cevap veremezdi
        if (target.getUser().isBlocked())
            throw new IllegalStateException("Bu kitap şu an takasa açık değil.");
        if (features.isAddress() && (me.getAddress() == null || me.getAddress().isBlank()))
            throw new IllegalStateException("Takas için profilinden teslimat adresi eklemelisin.");

        SwapBook offered = swapBooks.findByIdWithDetails(offeredBookId)
                .orElseThrow(() -> new IllegalStateException("Teklif edeceğin kitap bulunamadı."));
        if (!offered.getUser().getId().equals(me.getId()))
            throw new IllegalStateException("Yalnızca kendi kitabını teklif edebilirsin.");
        if (offered.getStatus() != SwapBookStatus.OPEN)
            throw new IllegalStateException("Teklif edeceğin kitap takasa açık olmalı.");

        if (offers.existsByFromUserAndTargetSwapBookAndStatus(me, target, OfferStatus.PENDING))
            throw new IllegalStateException("Bu kitap için zaten bekleyen bir teklifin var.");

        SwapOffer o = new SwapOffer();
        o.setFromUser(me);
        o.setToUser(target.getUser());
        o.setOfferedSwapBook(offered);
        o.setTargetSwapBook(target);
        o.setMessage(Metin.kisalt(message, 300));
        o = offers.save(o);

        notifications.notify(target.getUser(), "swap_offer",
                me.getName() + " sana takas teklif etti: \"" + offered.getBook().getTitle()
                        + "\" ↔ \"" + target.getBook().getTitle() + "\"",
                "/takas/teklifler/" + o.getId());
        return o;
    }

    @Transactional(readOnly = true)
    public List<SwapOffer> incoming(User user) { return offers.findIncoming(user); }

    @Transactional(readOnly = true)
    public List<SwapOffer> outgoing(User user) { return offers.findOutgoing(user); }

    /** Teklifi kabul et: iki kitap kapanır, rakip teklifler reddedilir, adresler açılır. */
    @Transactional
    public SwapOffer accept(Long offerId, User me) {
        // Eş zamanlı kabuller: aynı ilana iki ayrı teklif aynı anda kabul edilirse,
        // kilitsiz okumada ikisi de "OPEN" görüp kitabı iki kez takaslıyordu.
        // Önce ilan satırları kimlik sırasıyla kilitlenir (sıra sabit olmalı, yoksa
        // karşılıklı kilitlenme olur), teklif ancak ondan sonra yüklenir; böylece
        // durum bilgisi oturuma eski hâliyle girmez.
        var kimlikler = offers.findSwapBookIds(offerId)
                .orElseThrow(() -> new IllegalStateException("Teklif bulunamadı."));
        java.util.stream.Stream.of(kimlikler.getOfferedId(), kimlikler.getTargetId())
                .sorted()
                .forEach(swapBooks::findByIdForUpdate);

        SwapOffer o = offers.findByIdWithDetailsForUpdate(offerId)
                .orElseThrow(() -> new IllegalStateException("Teklif bulunamadı."));
        if (!o.getToUser().getId().equals(me.getId()))
            throw new IllegalStateException("Bu teklif sana gelmedi.");
        if (o.getStatus() != OfferStatus.PENDING)
            throw new IllegalStateException("Bu teklif zaten yanıtlanmış.");
        // İki ilan da hâlâ takasta olmalı: kapatılmış bir kitap teklif üzerinden geri açılmasın.
        if (o.getOfferedSwapBook().getStatus() != SwapBookStatus.OPEN
                || o.getTargetSwapBook().getStatus() != SwapBookStatus.OPEN)
            throw new IllegalStateException("Bu teklifteki kitaplardan biri artık takasta değil.");

        o.setStatus(OfferStatus.ACCEPTED);
        o.setDecidedAt(Instant.now());
        offers.save(o);

        SwapBook a = o.getOfferedSwapBook();
        SwapBook b = o.getTargetSwapBook();
        a.setStatus(SwapBookStatus.CLOSED);
        b.setStatus(SwapBookStatus.CLOSED);
        swapBooks.save(a);
        swapBooks.save(b);

        // Aynı kitaplara bağlı diğer bekleyen teklifleri reddet
        for (SwapOffer other : offers.findCompeting(OfferStatus.PENDING, o.getId(), List.of(a, b))) {
            other.setStatus(OfferStatus.REJECTED);
            other.setDecidedAt(Instant.now());
            offers.save(other);
            conversations.findByKindAndRefId(ConversationKind.SWAP, other.getId()).ifPresent(k -> {
                k.archive();
                conversations.save(k);
            });
            notifications.notify(other.getFromUser(), "swap_rejected",
                    "Takas teklifin reddedildi: \"" + other.getTargetSwapBook().getBook().getTitle() + "\" başka biriyle takaslandı.",
                    "/takas/takaslarim");
        }

        notifications.notify(o.getFromUser(), "swap_accepted", features.isShipping()
                ? me.getName() + " takas teklifini kabul etti. Adresler paylaşıldı; kitabı kargolayabilirsin."
                : me.getName() + " takas teklifini kabul etti. Kampüste bir buluşma ayarlayıp kitapları karşılıklı teslim edebilirsiniz.",
                "/takas/teklifler/" + o.getId());
        return o;
    }

    /** Teklifi reddet (hedef sahibi). */
    @Transactional
    public void reject(Long offerId, User me) {
        SwapOffer o = pendingOffer(offerId);
        if (!o.getToUser().getId().equals(me.getId()))
            throw new IllegalStateException("Bu teklif sana gelmedi.");
        o.setStatus(OfferStatus.REJECTED);
        o.setDecidedAt(Instant.now());
        offers.save(o);
        conversations.findByKindAndRefId(ConversationKind.SWAP, o.getId()).ifPresent(k -> {
            k.archive();
            conversations.save(k);
        });
        notifications.notify(o.getFromUser(), "swap_rejected",
                "Takas teklifin reddedildi: \"" + o.getTargetSwapBook().getBook().getTitle() + "\".",
                "/takas/takaslarim");
    }

    /** Teklifi geri çek (teklif eden). */
    @Transactional
    public void cancel(Long offerId, User me) {
        SwapOffer o = pendingOffer(offerId);
        if (!o.getFromUser().getId().equals(me.getId()))
            throw new IllegalStateException("Bu teklif sana ait değil.");
        o.setStatus(OfferStatus.CANCELLED);
        o.setDecidedAt(Instant.now());
        offers.save(o);
        conversations.findByKindAndRefId(ConversationKind.SWAP, o.getId()).ifPresent(k -> {
            k.archive();
            conversations.save(k);
        });
    }

    private SwapOffer pendingOffer(Long id) {
        SwapOffer o = offers.findByIdWithDetailsForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("Teklif bulunamadı."));
        if (o.getStatus() != OfferStatus.PENDING)
            throw new IllegalStateException("Bu teklif zaten yanıtlanmış.");
        return o;
    }

    /** Teklifi getirir ve kullanıcının teklifin tarafı olduğunu doğrular. */
    @Transactional(readOnly = true)
    public SwapOffer requireOffer(Long offerId, User me) {
        SwapOffer o = offers.findByIdWithDetails(offerId)
                .orElseThrow(() -> new IllegalStateException("Teklif bulunamadı."));
        if (!o.getFromUser().getId().equals(me.getId()) && !o.getToUser().getId().equals(me.getId()))
            throw new IllegalStateException("Bu takas teklifi sana ait değil.");
        return o;
    }

    /**
     * Takas buluşmasını ayarlar ya da günceller. Taraflardan ikisi de yapabilir.
     * Takasta tek bir buluşmada karşılıklı teslim yapılır.
     */
    @Transactional
    public SwapOffer arrange(Long offerId, User me, MeetingRequest request) {
        SwapOffer o = offers.findByIdWithDetailsForUpdate(offerId)
                .orElseThrow(() -> new IllegalStateException("Teklif bulunamadı."));
        if (o.getStatus() != OfferStatus.ACCEPTED)
            throw new IllegalStateException("Yalnızca kabul edilmiş takasta buluşma ayarlanabilir.");
        if (!o.getFromUser().getId().equals(me.getId()) && !o.getToUser().getId().equals(me.getId()))
            throw new IllegalStateException("Bu takas sana ait değil.");

        meetings.apply(o.getMeeting(), request);
        offers.save(o);

        User other = counterpart(o, me);
        notifications.notify(other, "meeting_arranged",
                "Takas buluşması ayarlandı: " + meetings.summary(o.getMeeting()),
                "/takas/teklifler/" + o.getId());
        return o;
    }

    /**
     * Yüz yüze teslimde kendi kitabını verdiğini onaylar; kargo modunda
     * kargoya verdiğini bildirir. İki taraf da onaylayınca takas tamamlanır.
     */
    @Transactional
    public void ship(Long offerId, User me) {
        // Elden teslimde iki taraf çoğu zaman aynı anda onaylar: kilitsiz okumada her işlem
        // karşı tarafın damgasını boş görür, takas tamamlanmaz ve son yazan diğerini ezer.
        SwapOffer o = offers.findByIdWithDetailsForUpdate(offerId)
                .orElseThrow(() -> new IllegalStateException("Teklif bulunamadı."));
        if (o.getStatus() != OfferStatus.ACCEPTED)
            throw new IllegalStateException("Yalnızca kabul edilmiş takas kargolanabilir.");
        if (features.isHandover() && !features.isShipping() && !o.getMeeting().isArranged())
            throw new IllegalStateException("Önce buluşma ayarlayın, sonra teslimi onaylayın.");

        boolean isFrom = o.getFromUser().getId().equals(me.getId());
        boolean isTo = o.getToUser().getId().equals(me.getId());
        if (!isFrom && !isTo) throw new IllegalStateException("Bu takas sana ait değil.");

        if (isFrom) {
            if (o.getFromShippedAt() != null) throw new IllegalStateException(zatenMesaji());
            o.setFromShippedAt(Instant.now());
        } else {
            if (o.getToShippedAt() != null) throw new IllegalStateException(zatenMesaji());
            o.setToShippedAt(Instant.now());
        }

        User other = isFrom ? o.getToUser() : o.getFromUser();
        notifications.notify(other, "swap_shipped", features.isShipping()
                ? me.getName() + " takas kitabını kargoya verdi."
                : me.getName() + " kitabı teslim ettiğini onayladı.",
                "/takas/teklifler/" + o.getId());

        if (o.getFromShippedAt() != null && o.getToShippedAt() != null) {
            o.setStatus(OfferStatus.COMPLETED);
            o.setDecidedAt(Instant.now());
            notifications.notify(o.getFromUser(), "swap_completed", "Takas tamamlandı. İyi okumalar!", "/takas/teklifler/" + o.getId());
            notifications.notify(o.getToUser(), "swap_completed", "Takas tamamlandı. İyi okumalar!", "/takas/teklifler/" + o.getId());
        }
        offers.save(o);
    }

    /** Karşı taraf takas buluşmasına gelmedi. Takas iptal olur, kitaplar geri açılır. */
    @Transactional
    public SwapOffer noShow(Long offerId, User bildiren) {
        SwapOffer o = offers.findByIdWithDetailsForUpdate(offerId)
                .orElseThrow(() -> new IllegalStateException("Teklif bulunamadı."));
        if (o.getStatus() != OfferStatus.ACCEPTED)
            throw new IllegalStateException("Yalnızca kabul edilmiş takasta bildirilebilir.");
        if (!o.getFromUser().getId().equals(bildiren.getId())
                && !o.getToUser().getId().equals(bildiren.getId()))
            throw new IllegalStateException("Bu takas sana ait değil.");
        if (o.getFromShippedAt() != null || o.getToShippedAt() != null)
            throw new IllegalStateException("Taraflardan biri teslimi onayladığı için gelinmedi bildirilemez.");
        if (!o.getMeeting().isArranged())
            throw new IllegalStateException("Önce bir buluşma ayarlanmış olmalı.");
        if (o.getMeeting().getAt() != null && Instant.now().isBefore(o.getMeeting().getAt()))
            throw new IllegalStateException("Buluşma saati daha gelmedi.");

        o.setStatus(OfferStatus.CANCELLED);
        o.setDecidedAt(Instant.now());
        offers.save(o);
        conversations.findByKindAndRefId(ConversationKind.SWAP, o.getId()).ifPresent(k -> {
            k.archive();
            conversations.save(k);
        });

        // Yönetimin kaldırmadığı kitaplar yeniden takasa açılır
        if (!o.getOfferedSwapBook().isRemovedByAdmin()) {
            o.getOfferedSwapBook().setStatus(SwapBookStatus.OPEN);
            swapBooks.save(o.getOfferedSwapBook());
        }
        if (!o.getTargetSwapBook().isRemovedByAdmin()) {
            o.getTargetSwapBook().setStatus(SwapBookStatus.OPEN);
            swapBooks.save(o.getTargetSwapBook());
        }

        User karsi = counterpart(o, bildiren);
        User gelmeyen = users.findByIdForUpdate(karsi.getId()).orElse(karsi);
        gelmeyen.setNoShowCount(gelmeyen.getNoShowCount() + 1);
        users.save(gelmeyen);

        notifications.notify(gelmeyen, "gelmedi",
                "Takas buluşmasına gelmediğin bildirildi. Tekrarlanırsa hesabın askıya alınabilir.",
                "/takas/takaslarim");
        notifications.notify(bildiren, "gelmedi_kayit",
                "Gelinmedi bildirimin kaydedildi; takas iptal edildi ve kitaplar yeniden açıldı.",
                "/takas/takaslarim");
        return o;
    }

    /**
     * Üye askıya alındığında taraf olduğu bekleyen ve kabul edilmiş teklifleri iptal eder
     * (bkz. AdminService#setBlocked).
     * <p>
     * Kabul edilmiş takasta karşı tarafın kitabı yeniden takasa açılır (hakkı iade edilir);
     * karşı taraf kendi kitabını çoktan teslim ettiyse kitap elinde olmadığı için açılmaz.
     * Askıdaki üyenin kitabı kapalı kalır. Sohbet arşivlenir.
     *
     * @return iptal edilen teklif sayısı
     */
    @Transactional
    public int askiyaAlinanUyeninTakaslariniIptalEt(User askida) {
        List<Long> surenIds = offers.findTarafOlduguIdsByStatus(askida, LIVE);
        int iptal = 0;
        for (Long id : surenIds) {
            SwapOffer o = offers.findByIdWithDetailsForUpdate(id).orElseThrow();
            if (!LIVE.contains(o.getStatus())) continue;
            iptal++;
            boolean kabulEdilmisti = o.getStatus() == OfferStatus.ACCEPTED;
            boolean bulusmaVardi = o.getMeeting().isArranged();
            boolean askidakiGonderen = o.getFromUser().getId().equals(askida.getId());
            User karsiTaraf = askidakiGonderen ? o.getToUser() : o.getFromUser();
            SwapBook karsiKitap = askidakiGonderen ? o.getTargetSwapBook() : o.getOfferedSwapBook();
            Instant karsiTeslim = askidakiGonderen ? o.getToShippedAt() : o.getFromShippedAt();

            o.setStatus(OfferStatus.CANCELLED);
            o.setDecidedAt(Instant.now());
            offers.save(o);
            conversations.findByKindAndRefId(ConversationKind.SWAP, o.getId()).ifPresent(k -> {
                k.archive();
                conversations.save(k);
            });

            String kitap = karsiKitap.getBook().getTitle();
            if (!kabulEdilmisti) {
                notifications.notify(karsiTaraf, AskiBildirimi.TUR,
                        AskiBildirimi.metin(kitap, false, "takas teklifi", false, null),
                        "/takas/takaslarim");
                continue;
            }
            boolean yenidenAcildi = karsiTeslim == null && !karsiKitap.isRemovedByAdmin();
            if (yenidenAcildi) {
                karsiKitap.setStatus(SwapBookStatus.OPEN);
                swapBooks.save(karsiKitap);
            }
            notifications.notify(karsiTaraf, AskiBildirimi.TUR, yenidenAcildi
                    ? AskiBildirimi.metin(kitap, bulusmaVardi, "takas", true, "Kitabın yeniden takasa açıldı.")
                    : AskiBildirimi.metin(kitap, bulusmaVardi, "takas", false,
                            "Kitabını teslim ettiysen sorunu şikâyet ederek yönetime bildirebilirsin."),
                    "/takas/takaslarim");
        }
        return iptal;
    }

    private String zatenMesaji() {
        return features.isShipping() ? "Zaten kargoya verdin." : "Teslimi zaten onayladın.";
    }

    public boolean addressVisible(SwapOffer o) {
        return o.getStatus() == OfferStatus.ACCEPTED || o.getStatus() == OfferStatus.COMPLETED;
    }

    /** Bu kullanıcı bu teklifte kargoya verdi mi? */
    public boolean hasShipped(SwapOffer o, User me) {
        return o.getFromUser().getId().equals(me.getId()) ? o.getFromShippedAt() != null : o.getToShippedAt() != null;
    }

    /** Teklifte karşı taraf. */
    public User counterpart(SwapOffer o, User me) {
        return o.getFromUser().getId().equals(me.getId()) ? o.getToUser() : o.getFromUser();
    }

    /** Bu kullanıcının bu takasta vereceği kitap. */
    public SwapBook myBookIn(SwapOffer o, User me) {
        return o.getFromUser().getId().equals(me.getId()) ? o.getOfferedSwapBook() : o.getTargetSwapBook();
    }

    /** Bu kullanıcının bu takasta alacağı kitap. */
    public SwapBook theirBookIn(SwapOffer o, User me) {
        return o.getFromUser().getId().equals(me.getId()) ? o.getTargetSwapBook() : o.getOfferedSwapBook();
    }
}
