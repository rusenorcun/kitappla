package app.kitappla.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import app.kitappla.config.Features;
import app.kitappla.domain.*;
import app.kitappla.repo.BookRequestRepository;
import app.kitappla.repo.ConversationRepository;
import app.kitappla.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Kitap istekleri: bir üye ihtiyacı olan kitabı listeler, bir başkası karşılar.
 * Karşılanan istek alıcının kotasından düşer; adres yalnızca karşılayana açılır.
 */
@Service
public class RequestService {

    /** Aynı anda açık tutulabilecek istek sayısı (suistimali önler). */
    public static final int MAX_OPEN_REQUESTS = 5;

    static final String IPTAL_KAPALI = "Bu istek, taraflardan biri askıya alındığı için kapatıldı.";

    private final Features features;
    private final MeetingService meetings;
    private final UserRepository users;
    private final BookRequestRepository requests;
    private final QuotaService quotaService;
    private final NotificationService notifications;
    private final ConversationRepository conversations;

    public RequestService(Features features, MeetingService meetings, UserRepository users,
                          BookRequestRepository requests, QuotaService quotaService,
                          NotificationService notifications, ConversationRepository conversations) {
        this.features = features;
        this.meetings = meetings;
        this.users = users;
        this.requests = requests;
        this.quotaService = quotaService;
        this.notifications = notifications;
        this.conversations = conversations;
    }

    // ---------- Okuma ----------

    @Transactional(readOnly = true)
    public Page<BookRequest> openRequests(String query, Pageable pageable) {
        return requests.findOpenPage(Arama.desen(query), pageable);
    }

    /** Sayfalamasız tüm liste (mobil uygulamanın mevcut sürümü kullanır). */
    @Transactional(readOnly = true)
    public List<BookRequest> openRequests(String query) {
        return openRequests(query, Pageable.unpaged()).getContent();
    }

    @Transactional(readOnly = true)
    public List<BookRequest> myRequests(User user) {
        return requests.findByStudentWithDetails(user);
    }

    /** Karşıladığım istekler — teslimat adresi bu listede gösterilir. */
    @Transactional(readOnly = true)
    public List<BookRequest> fulfilledByMe(User user) {
        return requests.findByFulfilledByWithDetails(user);
    }

    @Transactional(readOnly = true)
    public Optional<BookRequest> view(Long id) {
        return requests.findByIdWithDetails(id);
    }

    // ---------- Yazma ----------

    @Transactional
    public BookRequest create(User user, Book book, String description) {
        if (book == null) throw new IllegalStateException("Kitap seçilmedi.");
        if (features.isAddress() && (user.getAddress() == null || user.getAddress().isBlank()))
            throw new IllegalStateException("İstek oluşturmadan önce profilinden teslimat adresi eklemelisin.");

        long open = requests.countByStudentAndStatus(user, RequestStatus.OPEN);
        if (open >= MAX_OPEN_REQUESTS)
            throw new IllegalStateException("Aynı anda en fazla " + MAX_OPEN_REQUESTS
                    + " açık isteğin olabilir. Önce mevcut isteklerinden birini kaldır.");

        if (requests.existsByStudentAndBookAndStatus(user, book, RequestStatus.OPEN))
            throw new IllegalStateException("Bu kitap için zaten açık bir isteğin var.");

        BookRequest r = new BookRequest();
        r.setStudent(user);
        r.setBook(book);
        r.setDescription(Metin.kisalt(description, 500));
        return requests.save(r);
    }

    /** İsteği karşıla ve varsa buluşma önerisini aynı işlem (transaction) içinde kaydet. */
    @Transactional
    public BookRequest fulfill(Long requestId, User fulfiller, DonationSource source, MeetingRequest meetingRequest) {
        BookRequest r = fulfill(requestId, fulfiller, source);
        if (meetingRequest != null && meetingRequest.at() != null) {
            return arrange(r.getId(), fulfiller, meetingRequest);
        }
        return r;
    }

    /** İsteği karşıla. Kota, isteği OLUŞTURAN kişiye göre kontrol edilir. */
    @Transactional
    public BookRequest fulfill(Long requestId, User fulfiller, DonationSource source) {
        BookRequest r = requests.findByIdWithDetailsForUpdate(requestId)
                .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));

        if (r.getStatus() != RequestStatus.OPEN)
            throw new IllegalStateException("Bu kitap başkası tarafından karşılandı.");
        if (r.getStudent().getId().equals(fulfiller.getId()))
            throw new IllegalStateException("Kendi isteğini karşılayamazsın.");
        if (fulfiller.isBlocked())
            throw new IllegalStateException("Hesabın engellenmiş.");
        if (r.getStudent().isBlocked())
            throw new IllegalStateException("Bu istek şu an karşılanamıyor.");

        // İsteyenin kotası: aynı kişinin farklı isteklerini eşzamanlı karşılayanlar sınırı aşmasın
        users.findByIdForUpdate(r.getStudent().getId());
        String quotaReason = quotaService.cannotReceiveReason(r.getStudent());
        if (quotaReason != null)
            throw new IllegalStateException("İsteği açan kişinin kotası dolu: " + quotaReason);

        conversations.findByKindAndRefId(ConversationKind.REQUEST, requestId).ifPresent(c -> {
            c.archive();
            conversations.save(c);
        });
        r.setStatus(RequestStatus.FULFILLED);
        r.setSource(source != null ? source : (features.isPurchase() ? DonationSource.PURCHASE : DonationSource.OWN));
        r.setFulfilledBy(fulfiller);
        r.setFulfilledAt(Instant.now());
        requests.save(r);

        notifications.notify(r.getStudent(), "request_fulfilled",
                "\"" + r.getBook().getTitle() + "\" isteğin " + fulfiller.getName() + " tarafından karşılandı.",
                "/isteklerim");
        return r;
    }

    /**
     * Karşılanan bir isteğin karşılama işlemini iptal eder (buluşma kaydedilmemişse).
     * Karşılayan da isteyen de iptal edebilir.
     * İstek yeniden OPEN durumuna döner ve başkaları tarafından karşılanabilir.
     */
    @Transactional
    public BookRequest cancelFulfillment(Long requestId, User user) {
        BookRequest r = requests.findByIdWithDetailsForUpdate(requestId)
                .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));

        boolean isteyen = r.getStudent().getId().equals(user.getId());
        boolean karsilayan = r.getFulfilledBy() != null
                && r.getFulfilledBy().getId().equals(user.getId());

        if (!isteyen && !karsilayan)
            throw new IllegalStateException("Bu istek sana ait değil.");

        if (r.getStatus() == RequestStatus.ARRANGED || r.getMeeting().isArranged())
            throw new IllegalStateException("Buluşma kaydedildikten sonra iptal edilemez.");

        if (r.getStatus() != RequestStatus.FULFILLED)
            throw new IllegalStateException("Yalnızca buluşması henüz kaydedilmemiş karşılanan istekler iptal edilebilir.");

        User eskiKarsilayan = r.getFulfilledBy();
        String kitap = r.getBook().getTitle();

        conversations.findByKindAndRefId(ConversationKind.REQUEST, requestId).ifPresent(c -> {
            c.archive();
            conversations.save(c);
        });

        r.setStatus(RequestStatus.OPEN);
        r.setFulfilledBy(null);
        r.setFulfilledAt(null);
        r.setSource(null);
        r.setMeeting(new Meeting());
        requests.save(r);

        if (isteyen && eskiKarsilayan != null) {
            notifications.notify(eskiKarsilayan, "request_cancelled",
                    "\"" + kitap + "\" için karşılama işlemi istek sahibi tarafından iptal edildi.",
                    "/karsiladiklarim");
        } else if (karsilayan) {
            notifications.notify(r.getStudent(), "request_cancelled",
                    "\"" + kitap + "\" isteğini karşılamaktan vazgeçildi. İsteğin yeniden açık.",
                    "/isteklerim");
        }
        return r;
    }

    /** Karşılayan kargoya verdi. Yalnızca kargo akışı açıkken kullanılır. */
    @Transactional
    public void ship(Long requestId, User fulfiller) {
        BookRequest r = ownFulfilledForUpdate(requestId, fulfiller);
        if (!features.isShipping())
            throw new IllegalStateException("Kargo akışı kapalı; teslim kampüste yüz yüze yapılır.");
        if (r.getStatus() != RequestStatus.FULFILLED)
            throw new IllegalStateException("Bu istek kargo aşamasında değil.");
        r.setStatus(RequestStatus.SHIPPED);
        r.setShippedAt(Instant.now());
        requests.save(r);
        notifications.notify(r.getStudent(), "request_shipped",
                "\"" + r.getBook().getTitle() + "\" kitabın kargoya verildi.",
                "/isteklerim");
    }

    /**
     * Buluşmayı ayarlar ya da günceller. İsteği açan da karşılayan da yapabilir.
     */
    @Transactional
    public BookRequest arrange(Long requestId, User user, MeetingRequest request) {
        // noShow isteği kilitleyip yeniden açabilir; kilitsiz okumada buluşma, karşılayanı
        // silinmiş bir isteği ARRANGED durumuna geri yazabiliyordu.
        BookRequest r = requests.findByIdWithDetailsForUpdate(requestId)
                .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));

        boolean isteyen = r.getStudent().getId().equals(user.getId());
        boolean karsilayan = r.getFulfilledBy() != null
                && r.getFulfilledBy().getId().equals(user.getId());
        if (!isteyen && !karsilayan)
            throw new IllegalStateException("Bu istek sana ait değil.");
        if (r.getStatus() == RequestStatus.OPEN)
            throw new IllegalStateException("Bu isteği henüz kimse karşılamadı.");
        if (r.getStatus() == RequestStatus.DELIVERED)
            throw new IllegalStateException("Bu kitap zaten teslim edildi.");
        if (r.getStatus() == RequestStatus.SHIPPED)
            throw new IllegalStateException("Kargoya verilmiş kayıt için buluşma ayarlanamaz.");
        // İsteyen gelmediğinde kayıt kapanır ve kota hakkı yanar; yeni buluşmayla diriltilemez.
        if (r.getStatus() == RequestStatus.NO_SHOW)
            throw new IllegalStateException("Bu istek gelinmedi bildirimiyle kapandı.");
        if (r.getStatus() == RequestStatus.CANCELLED)
            throw new IllegalStateException(IPTAL_KAPALI);

        meetings.apply(r.getMeeting(), request);
        r.setStatus(RequestStatus.ARRANGED);
        requests.save(r);

        User digeri = isteyen ? r.getFulfilledBy() : r.getStudent();
        notifications.notify(digeri, "meeting_arranged",
                "\"" + r.getBook().getTitle() + "\" için buluşma ayarlandı: "
                        + meetings.summary(r.getMeeting()),
                isteyen ? "/karsiladiklarim" : "/isteklerim");
        return r;
    }

    /**
     * Karşı taraf buluşmaya gelmedi. İstek yeniden açılmaz — isteyen kişi
     * hakkını kullanmış sayılır (kota cezası); karşılayan gelmediyse istek
     * yeniden açık hâle gelir ki başkası karşılayabilsin.
     */
    @Transactional
    public BookRequest noShow(Long requestId, User bildiren) {
        BookRequest r = requests.findByIdWithDetailsForUpdate(requestId)
                .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));

        boolean isteyen = r.getStudent().getId().equals(bildiren.getId());
        boolean karsilayan = r.getFulfilledBy() != null
                && r.getFulfilledBy().getId().equals(bildiren.getId());
        if (!isteyen && !karsilayan) throw new IllegalStateException("Bu istek sana ait değil.");

        if (r.getStatus() == RequestStatus.DELIVERED)
            throw new IllegalStateException("Bu kitap zaten teslim edilmiş.");
        if (r.getStatus() == RequestStatus.SHIPPED)
            throw new IllegalStateException("Kargoya verilmiş kayıt için gelinmedi bildirilemez.");
        if (r.getStatus() == RequestStatus.NO_SHOW)
            throw new IllegalStateException("Bu kayıt zaten gelinmedi olarak işaretlenmiş.");
        if (r.getStatus() == RequestStatus.CANCELLED)
            throw new IllegalStateException(IPTAL_KAPALI);
        if (!r.getMeeting().isArranged())
            throw new IllegalStateException("Önce bir buluşma ayarlanmış olmalı.");
        if (r.getMeeting().getAt() != null && Instant.now().isBefore(r.getMeeting().getAt()))
            throw new IllegalStateException("Buluşma saati daha gelmedi.");

        User karsi = isteyen ? r.getFulfilledBy() : r.getStudent();
        String kitap = r.getBook().getTitle();

        conversations.findByKindAndRefId(ConversationKind.REQUEST, requestId).ifPresent(c -> {
            c.archive();
            conversations.save(c);
        });

        if (isteyen) {
            // Karşılayan gelmedi: istek yeniden açılır, başkası karşılayabilsin
            r.setStatus(RequestStatus.OPEN);
            r.setFulfilledBy(null);
            r.setFulfilledAt(null);
            r.setSource(null);
            r.setMeeting(new Meeting());
        } else {
            // İsteyen gelmedi: kayıt kapanır, kota hakkı yanar
            r.setStatus(RequestStatus.NO_SHOW);
        }
        requests.save(r);

        if (karsi != null) {
            User gelmeyen = users.findByIdForUpdate(karsi.getId()).orElse(karsi);
            gelmeyen.setNoShowCount(gelmeyen.getNoShowCount() + 1);
            users.save(gelmeyen);
            notifications.notify(gelmeyen, "gelmedi",
                    "\"" + kitap + "\" buluşmasına gelmediğin bildirildi. "
                            + "Tekrarlanırsa hesabın askıya alınabilir.",
                    isteyen ? "/karsiladiklarim" : "/isteklerim");
        }
        notifications.notify(bildiren, "gelmedi_kayit",
                "\"" + kitap + "\" için gelinmedi bildirimin kaydedildi.",
                isteyen ? "/isteklerim" : "/karsiladiklarim");
        return r;
    }

    /**
     * Üye askıya alındığında taraf olduğu süren istekleri kapatır (bkz. AdminService#setBlocked).
     * <p>
     * Karşılayan askıya alındıysa istek yeniden açılır: isteyenin kota hakkı iade edilir
     * (açık istek kotada sayılmaz) ve başkası karşılayabilir. İsteyen askıya alındıysa istek
     * iptal edilir. Kargoya verilmiş ya da teslim edilmiş kayıtlara dokunulmaz.
     *
     * @return kapatılan istek sayısı
     */
    @Transactional
    public int askiyaAlinanUyeninIstekleriniKapat(User askida) {
        List<Long> surenIds = requests.findTarafOlduguIdsByStatus(askida,
                List.of(RequestStatus.FULFILLED, RequestStatus.ARRANGED));
        int kapatilan = 0;
        for (Long id : surenIds) {
            // Karşı tarafın eşzamanlı teslim onayıyla yarışmasın
            BookRequest r = requests.findByIdWithDetailsForUpdate(id).orElseThrow();
            if (r.getStatus() != RequestStatus.FULFILLED && r.getStatus() != RequestStatus.ARRANGED) continue;
            kapatilan++;
            boolean bulusmaVardi = r.getMeeting().isArranged();
            String kitap = r.getBook().getTitle();
            User karsilayan = r.getFulfilledBy();
            conversations.findByKindAndRefId(ConversationKind.REQUEST, r.getId()).ifPresent(k -> {
                k.archive();
                conversations.save(k);
            });

            if (karsilayan != null && karsilayan.getId().equals(askida.getId())) {
                r.setStatus(RequestStatus.OPEN);
                r.setFulfilledBy(null);
                r.setFulfilledAt(null);
                r.setSource(null);
                r.setMeeting(new Meeting());
                requests.save(r);
                notifications.notify(r.getStudent(), AskiBildirimi.TUR,
                        AskiBildirimi.metin(kitap, bulusmaVardi, "karşılama", true,
                                "İsteğin yeniden açıldı; başka biri karşılayabilir."),
                        "/isteklerim");
            } else {
                r.setStatus(RequestStatus.CANCELLED);
                requests.save(r);
                if (karsilayan != null)
                    notifications.notify(karsilayan, AskiBildirimi.TUR,
                            AskiBildirimi.metin(kitap, bulusmaVardi, "istek", false, null),
                            "/karsiladiklarim");
            }
        }
        return kapatilan;
    }

    /** İsteği açan teslim aldı. */
    @Transactional
    public void deliver(Long requestId, User requester) {
        BookRequest r = ownRequestForUpdate(requestId, requester);
        if (r.getStatus() != RequestStatus.FULFILLED && r.getStatus() != RequestStatus.SHIPPED
                && r.getStatus() != RequestStatus.ARRANGED)
            throw new IllegalStateException("Bu istek teslim aşamasında değil.");
        // Yüz yüze teslimde önce buluşma ayarlanmış olmalı
        if (features.isHandover() && !features.isShipping() && r.getStatus() == RequestStatus.FULFILLED)
            throw new IllegalStateException("Önce buluşma ayarlayın, sonra teslimi onaylayın.");
        r.setStatus(RequestStatus.DELIVERED);
        r.setDeliveredAt(Instant.now());
        requests.save(r);
        if (r.getFulfilledBy() != null) {
            notifications.notify(r.getFulfilledBy(), "request_delivered",
                    requester.getName() + ", \"" + r.getBook().getTitle() + "\" kitabını teslim aldı.",
                    "/karsiladiklarim");
        }
    }

    /** İsteği açan teşekkür eder. */
    @Transactional
    public void thank(Long requestId, User requester, String message) {
        BookRequest r = ownRequest(requestId, requester);
        if (r.getStatus() != RequestStatus.DELIVERED)
            throw new IllegalStateException("Teşekkür notu yalnızca teslim aldığın kitaplar için gönderilebilir.");
        if (r.getFulfilledBy() == null) return;
        String note = (message == null || message.isBlank()) ? "" : " Notu: \"" + message.trim() + "\"";
        notifications.notify(r.getFulfilledBy(), "thank_you",
                requester.getName() + ", \"" + r.getBook().getTitle() + "\" için teşekkür etti." + note,
                "/karsiladiklarim");
    }

    /** Açık isteği kaldır. */
    @Transactional
    public void delete(Long requestId, User requester) {
        // Kilitli okunur: aynı anda karşılanan istek, eski "açık" durumuna bakılarak silinmesin
        BookRequest r = requests.findByIdWithDetailsForUpdate(requestId)
                .filter(x -> x.getStudent().getId().equals(requester.getId()))
                .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));
        if (r.getStatus() != RequestStatus.OPEN)
            throw new IllegalStateException("Karşılanmış bir istek kaldırılamaz.");
        requests.delete(r);
    }

    private BookRequest ownRequest(Long id, User user) {
        BookRequest r = requests.findByIdWithDetails(id)
                .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));
        if (!r.getStudent().getId().equals(user.getId()))
            throw new IllegalStateException("Bu istek sana ait değil.");
        return r;
    }

    private BookRequest ownRequestForUpdate(Long id, User user) {
        BookRequest r = requests.findByIdWithDetailsForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));
        if (!r.getStudent().getId().equals(user.getId()))
            throw new IllegalStateException("Bu istek sana ait değil.");
        return r;
    }

    private BookRequest ownFulfilledForUpdate(Long id, User user) {
        BookRequest r = requests.findByIdWithDetailsForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));
        if (r.getFulfilledBy() == null || !r.getFulfilledBy().getId().equals(user.getId()))
            throw new IllegalStateException("Bu isteği sen karşılamadın.");
        return r;
    }
}
