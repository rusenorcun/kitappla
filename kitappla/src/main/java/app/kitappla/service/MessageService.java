package app.kitappla.service;

import java.util.stream.Collectors;
import java.util.Map;
import java.util.Collection;
import app.kitappla.config.OnbellekConfig;
import app.kitappla.domain.*;
import app.kitappla.repo.*;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Eşleşme üzerinden yürüyen sohbetler.
 * <p>
 * Sohbet kendiliğinden açılmaz: ilgili alışveriş (bağış talebi, karşılanan
 * istek, kabul edilmiş takas) varsa ve isteyen kişi onun taraflarından biriyse
 * açılır. Erişim denetimi her zaman alışverişin kendisinden doğrulanır.
 */
@Service
public class MessageService {

    private static final int MAX_UZUNLUK = 2000;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ClaimRepository claims;
    private final BookRequestRepository requests;
    private final SwapOfferRepository offers;
    private final ReportRepository reports;
    private final UserRepository users;
    private final NotificationService notifications;
    private final SseHub sse;
    private final CacheManager cacheManager;

    private final app.kitappla.config.Marka marka;

    public MessageService(ConversationRepository conversations, MessageRepository messages,
                          ClaimRepository claims, BookRequestRepository requests,
                          SwapOfferRepository offers, ReportRepository reports,
                          UserRepository users, NotificationService notifications, SseHub sse,
                          CacheManager cacheManager, app.kitappla.config.Marka marka) {
        this.marka = marka;
        this.conversations = conversations;
        this.messages = messages;
        this.claims = claims;
        this.requests = requests;
        this.offers = offers;
        this.reports = reports;
        this.users = users;
        this.notifications = notifications;
        this.sse = sse;
        this.cacheManager = cacheManager;
    }

    // ---------- Açma ve erişim ----------

    /** Alışverişin iki tarafını çözer; kişi taraflardan biri değilse hata verir. */
    private User[] taraflar(ConversationKind kind, Long refId, User me) {
        switch (kind) {
            case CLAIM -> {
                Claim c = claims.findByIdWithDetails(refId)
                        .orElseThrow(() -> new IllegalStateException("Kayıt bulunamadı."));
                // Askı veya gelinmedi nedeniyle kapanan talebin sohbeti arşivlenir; yenisi açılmamalı
                if (c.getStatus() == ClaimStatus.CANCELLED || c.getStatus() == ClaimStatus.NO_SHOW)
                    throw new IllegalStateException("İptal edilmiş talep için mesajlaşma açılamaz.");
                return dogrula(c.getDonation().getDonor(), c.getStudent(), me);
            }
            case REQUEST -> {
                BookRequest r = requests.findByIdWithDetailsForUpdate(refId)
                        .orElseThrow(() -> new IllegalStateException("İstek bulunamadı."));
                if (r.getFulfilledBy() == null)
                    throw new IllegalStateException("Bu isteği henüz kimse karşılamadı.");
                if (r.getStatus() == RequestStatus.CANCELLED || r.getStatus() == RequestStatus.NO_SHOW)
                    throw new IllegalStateException("İptal edilmiş istek için mesajlaşma açılamaz.");
                return dogrula(r.getStudent(), r.getFulfilledBy(), me);
            }
            case SWAP -> {
                SwapOffer o = offers.findByIdWithDetails(refId)
                        .orElseThrow(() -> new IllegalStateException("Teklif bulunamadı."));
                if (o.getStatus() == OfferStatus.CANCELLED || o.getStatus() == OfferStatus.REJECTED)
                    throw new IllegalStateException("İptal edilmiş veya reddedilmiş takas için mesajlaşma açılamaz.");
                return dogrula(o.getFromUser(), o.getToUser(), me);
            }
            case REPORT -> {
                Report r = reports.findByIdWithUsers(refId)
                        .orElseThrow(() -> new IllegalStateException("Şikâyet bulunamadı."));
                User reporter = r.getReporter();
                boolean admin = currentAdmin(me);
                if (!admin && !reporter.getId().equals(me.getId()))
                    throw new IllegalStateException("Bu şikâyet sana ait değil.");
                User adminUser = r.getReviewedBy() != null && currentAdmin(r.getReviewedBy()) ? r.getReviewedBy()
                        : (admin ? me : users.findFirstByAdminTrueOrderByIdAsc().orElse(reporter));
                return new User[]{reporter, adminUser};
            }
            case SUPPORT -> {
                // refId üyenin kendisidir: üye yalnızca kendi destek sohbetini açar,
                // yönetici ise herhangi bir üyeye yazmak için açabilir.
                User uye = users.findById(refId)
                        .orElseThrow(() -> new IllegalStateException("Üye bulunamadı."));
                boolean admin = currentAdmin(me);
                if (!admin && !uye.getId().equals(me.getId()))
                    throw new IllegalStateException("Bu sohbet sana ait değil.");
                if (uye.isAdmin())
                    throw new IllegalStateException("Yöneticiler için destek sohbeti açılmaz.");
                User yonetici = admin ? me : users.findFirstByAdminTrueOrderByIdAsc()
                        .orElseThrow(() -> new IllegalStateException("Şu anda ulaşılabilecek bir yönetici yok."));
                return new User[]{uye, yonetici};
            }
            default -> throw new IllegalStateException("Bilinmeyen sohbet türü.");
        }
    }

    private User[] dogrula(User a, User b, User me) {
        if (!a.getId().equals(me.getId()) && !b.getId().equals(me.getId()))
            throw new IllegalStateException("Bu sohbet sana ait değil.");
        return new User[]{a, b};
    }

    /** Sohbeti bulur, yoksa açar. */
    @Transactional
    public Conversation open(ConversationKind kind, Long refId, User me) {
        User[] t = taraflar(kind, refId, me);
        Optional<Conversation> existing = conversations.findByKindAndRefId(kind, refId);
        if (existing.isPresent()) {
            Conversation c = existing.get();
            if (kind != ConversationKind.REQUEST || (c.getUserA().getId().equals(t[0].getId())
                    && c.getUserB().getId().equals(t[1].getId()))) {
                checkAccess(c, me);
                return c;
            }
            c.archive();
            conversations.saveAndFlush(c);
        }
        Conversation c = new Conversation();
        c.setKind(kind);
        c.setRefId(refId);
        c.setUserA(t[0]);
        c.setUserB(t[1]);
        return conversations.save(c);
    }

    /** Kimlikten sohbeti getirir; yalnızca tarafları erişebilir (REPORT ve SUPPORT için yöneticiler de). */
    public Conversation require(Long conversationId, User me) {
        Conversation c = conversations.findByIdWithUsers(conversationId)
                .orElseThrow(() -> new IllegalStateException("Sohbet bulunamadı."));
        checkAccess(c, me);
        return c;
    }

    private boolean currentAdmin(User me) {
        return me != null && users.findById(me.getId()).map(User::isAdmin).orElse(false);
    }

    private boolean checkAccess(Conversation c, User me) {
        return checkAccess(c, me, null);
    }

    private boolean checkAccess(Conversation c, User me, Boolean cachedIsAdmin) {
        if (c.getKind() == ConversationKind.REPORT) {
            Report report = reports.findByIdWithUsers(c.getRefId())
                    .orElseThrow(() -> new IllegalStateException("Şikâyet bulunamadı."));
            boolean reporter = report.getReporter().getId().equals(me.getId());
            boolean isAdmin = cachedIsAdmin != null ? cachedIsAdmin : currentAdmin(me);
            if (!reporter && !isAdmin)
                throw new IllegalStateException("Bu sohbet sana ait değil.");
            return reporter;
        }
        if (c.getKind() == ConversationKind.SUPPORT) {
            // Üye her zaman userA'dır; yönetici tarafı (userB damgası) güncel role göre belirlenir
            boolean uye = c.getUserA().getId().equals(me.getId());
            boolean isAdmin = cachedIsAdmin != null ? cachedIsAdmin : currentAdmin(me);
            if (!uye && !isAdmin)
                throw new IllegalStateException("Bu sohbet sana ait değil.");
            return uye;
        }
        if (!c.has(me)) throw new IllegalStateException("Bu sohbet sana ait değil.");
        return c.getUserA().getId().equals(me.getId());
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(Long id, User me) {
        require(id, me);
        return sse.subscribe(id, () -> {
            try {
                require(id, me);
                return true;
            } catch (IllegalStateException ex) {
                return false;
            }
        });
    }

    // ---------- Okuma ----------

    public List<Conversation> mine(User me) {
        return conversations.findMine(me);
    }

    public List<Message> messagesOf(Conversation c) {
        return messages.findByConversation(c);
    }

    public long unread(Conversation c, User me) {
        // Şikâyet sohbetinde yönetici tarafı userB'dir; sohbetin tarafı olmayan
        // bir yönetici de aynı damgayı kullanır. Şikâyet edenin (userA) damgasına
        // asla düşülmez, yoksa yönetici "okunmuş" görünen mesajları kaçırır.
        boolean sideA = checkAccess(c, me);
        Instant son = sideA ? c.getLastReadA() : c.getLastReadB();
        return son == null
                ? messages.countByConversationAndSenderNot(c, me)
                : messages.countByConversationAndSenderNotAndCreatedAtAfter(c, me, son);
    }

    /** Sohbet başına okunmamış sayıları tek sorguda ({@link #unread} ile aynı kural). Listedeki her sohbet için değer vardır. */
    public Map<Long, Long> unreadCounts(List<Conversation> list, User me) {
        if (list.isEmpty()) return Map.of();
        Boolean isAdmin = list.stream().anyMatch(Conversation::isYonetimSohbeti) ? currentAdmin(me) : null;
        for (Conversation c : list) {
            checkAccess(c, me, isAdmin);
        }
        List<Long> ids = list.stream().map(Conversation::getId).toList();
        Map<Long, Long> adetler = messages.countUnreadByConversation(ids, me).stream()
                .collect(Collectors.toMap(MessageRepository.OkunmamisAdet::getConversationId,
                                          MessageRepository.OkunmamisAdet::getAdet));
        return list.stream().collect(Collectors.toMap(Conversation::getId, c -> adetler.getOrDefault(c.getId(), 0L)));
    }

    /**
     * Nav'daki rozet için okunmamış mesajı olan sohbet sayısı. Hesap {@link #unread} ile aynı
     * kuralı iki toplu sorguda uygular. Bilerek önbelleğe alınmaz: şikâyet sohbetlerine erişim
     * güncel yönetici rolüne bağlıdır ve yetkisi alınan yöneticinin rozeti anında düşmelidir
     * (MessageServiceTest#reportAccessUsesCurrentRoleAndReporterForEveryEntryPoint).
     */
    public long unreadConversations(User me) {
        return conversations.countUnreadAsParty(me) + conversations.countUnreadYonetimSohbetleri(me);
    }

    @Transactional
    public void markRead(Conversation c, User me) {
        Conversation stored = conversations.findByIdWithUsers(c.getId())
                .orElseThrow(() -> new IllegalStateException("Sohbet bulunamadı."));
        boolean sideA = checkAccess(stored, me);
        Instant now = Instant.now();
        if (sideA) {
            stored.setLastReadA(now);
            c.setLastReadA(now);
        } else {
            stored.setLastReadB(now);
            c.setLastReadB(now);
        }
        conversations.save(stored);
        rozetiTazele(stored);
    }

    // ---------- Yazma ----------

    @Transactional
    public Message send(Long conversationId, User me, String body) {
        Conversation c = conversations.findByIdWithUsers(conversationId)
                .orElseThrow(() -> new IllegalStateException("Sohbet bulunamadı."));
        boolean sideA = checkAccess(c, me);
        if (c.isArchived()) throw new IllegalStateException("Bu sohbet arşivlendi.");

        String metin = body == null ? null : body.trim();
        if (metin == null || metin.isEmpty())
            throw new IllegalStateException("Mesaj boş olamaz.");
        if (metin.length() > MAX_UZUNLUK)
            metin = metin.substring(0, MAX_UZUNLUK);

        Message m = new Message();
        m.setConversation(c);
        m.setSender(me);
        m.setBody(metin);
        messages.save(m);

        c.setLastMessage(metin.length() > 200 ? metin.substring(0, 200) : metin);
        c.setLastMessageAt(m.getCreatedAt());
        if (sideA) c.setLastReadA(m.getCreatedAt());
        else c.setLastReadB(m.getCreatedAt());
        conversations.save(c);
        rozetiTazele(c);

        // Karşı tarafa hem canlı akış hem bildirim. Canlı akış commit sonrasına bırakılır:
        // olay tarayıcıya işlem sürerken giderse istemci listeyi hemen yeniden çeker,
        // henüz commit edilmemiş mesajı göremez ve bir sonraki olaya kadar ekranda kalmaz.
        final Long sohbetId = c.getId();
        NotificationService.commitSonrasi(() -> sse.publish(sohbetId));
        User recipient;
        String senderName;
        if (c.getKind() == ConversationKind.REPORT) {
            recipient = sideA
                    ? (currentAdmin(c.getUserB()) ? c.getUserB() : users.findFirstByAdminTrueOrderByIdAsc().orElse(null))
                    : reports.findByIdWithUsers(c.getRefId()).orElseThrow().getReporter();
            senderName = sideA ? me.getName() : marka.destekAdi() + " / Yönetim";
        } else if (c.getKind() == ConversationKind.SUPPORT) {
            // Sohbeti açan yönetici hâlâ yöneticiyse ona, değilse ilk yöneticiye gider
            recipient = sideA
                    ? (currentAdmin(c.getUserB()) ? c.getUserB() : users.findFirstByAdminTrueOrderByIdAsc().orElse(null))
                    : c.getUserA();
            senderName = sideA ? me.getName() : marka.destekAdi() + " / Yönetim";
        } else {
            recipient = c.other(me);
            senderName = me.getName();
        }
        if (recipient != null) notifications.notify(recipient, "mesaj",
                senderName + " sana mesaj gönderdi: \"" + kisalt(metin) + "\"",
                "/mesajlar/" + sohbetId);
        return m;
    }

    private static String kisalt(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    /**
     * Moderasyon için sohbeti açar. Yönetici bile <b>yalnızca açık şikâyeti
     * olan</b> bir sohbeti okuyabilir; şikâyetsiz sohbetler yönetime de kapalıdır.
     * Kural burada zorlanır ki controller'da unutulması mümkün olmasın.
     */
    public Conversation requireForModeration(Long conversationId, ReportService reports) {
        Conversation c = conversations.findByIdWithUsers(conversationId)
                .orElseThrow(() -> new IllegalStateException("Sohbet bulunamadı."));
        if (!reports.hasOpenReport(ReportKind.CONVERSATION, conversationId))
            throw new IllegalStateException(
                    "Bu sohbetin açık şikâyeti yok; mesajlar yönetime kapalıdır.");
        return c;
    }

    // ---------- Yardımcılar ----------

    /**
     * Rozeti değişen kişilerin önbellek kaydını siler (işlem commit olunca uygulanır).
     * Şikâyet ve destek sohbetlerinde tüm yöneticiler aynı okuma damgasını paylaştığı için kayıtların
     * hepsi silinir; bu sohbetler seyrek olduğundan maliyeti yoktur.
     */
    private void rozetiTazele(Conversation c) {
        Cache cache = cacheManager.getCache(OnbellekConfig.OKUNMAMIS_SOHBET);
        if (c.isYonetimSohbeti()) {
            cache.clear();
        } else {
            cache.evict(c.getUserA().getId());
            cache.evict(c.getUserB().getId());
        }
    }

    /** Alışverişlerin sohbet kimlikleri tek sorguda (anahtar: alışveriş kimliği). Sohbeti açılmamış olanlar yer almaz. */
    public Map<Long, Long> conversationIds(ConversationKind kind, Collection<Long> refIds) {
        if (refIds.isEmpty()) return Map.of();
        return conversations.findIdsByKindAndRefIds(kind, refIds).stream()
                .collect(Collectors.toMap(ConversationRepository.SohbetKimligi::getRefId,
                                          ConversationRepository.SohbetKimligi::getId));
    }

    /** Belirli bir alışverişin sohbeti (varsa). */
    public Optional<Conversation> find(ConversationKind kind, Long refId) {
        return conversations.findByKindAndRefId(kind, refId);
    }
}
