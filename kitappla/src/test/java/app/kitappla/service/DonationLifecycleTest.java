package app.kitappla.service;

import app.kitappla.domain.*;
import app.kitappla.repo.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bağış oluşturma, yönetimi ve teslimat akışı. */
@SpringBootTest
@ActiveProfiles("test")
class DonationLifecycleTest {

    @Autowired DonationService donationService;
    @Autowired BookService bookService;
    @Autowired UserRepository users;
    @Autowired ClaimRepository claims;
    @Autowired SwapBookRepository swapBooks;
    @Autowired NotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;

    private User user(String tag, boolean student, String address) {
        User u = new User();
        u.setName("Kişi " + tag);
        u.setEmail(tag + "-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash("x");
        u.setAddress(address);
        if (student) {
            u.setStudentStatus(StudentStatus.APPROVED);
            u.setSchoolLevel(SchoolLevel.LISE);
        }
        return users.save(u);
    }

    private Donation newDonation(User donor, int qty) {
        Book b = bookService.findOrCreate("Kitap " + UUID.randomUUID(), "Yazar", null, null, null, donor.getId());
        return donationService.create(donor, b, qty, TargetLevel.HEPSI, DonationSource.OWN, "Temiz durumda");
    }

    /** Kampüs teslimi için basit bir buluşma isteği (yer serbest metin, zaman geçmişte değil). */
    private MeetingRequest bulusma() {
        return new MeetingRequest(null, "Kütüphane girişi", Instant.now().plus(1, ChronoUnit.DAYS));
    }

    private void backdate(Donation d, long days) {
        jdbc.update("UPDATE donations SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), d.getId());
    }

    @Test
    void bagisOlusturulurVeAcikListedeGorunur() {
        User donor = user("bagisci", false, "İzmir");
        Donation d = newDonation(donor, 2);

        assertThat(d.getId()).isNotNull();
        assertThat(d.getStatus()).isEqualTo(DonationStatus.OPEN);
        assertThat(d.isPriorityActive()).isTrue();
        assertThat(donationService.myDonations(donor)).extracting(DonationView::getId).contains(d.getId());
    }

    @Test
    void kampusTeslimindeAdressizBagisYapilabilir() {
        // Yüz yüze teslimde adres gerekmez; kargo modunda yeniden istenir (KargoModuTest)
        User donor = user("adressiz", false, null);
        Book b = bookService.findOrCreate("Adressiz Kitap", "Y", null, null, null, null);
        assertThat(donationService.create(donor, b, 1, TargetLevel.HEPSI, DonationSource.OWN, null))
                .isNotNull();
    }

    @Test
    void gecersizAdetReddedilir() {
        User donor = user("bagisci", false, "İzmir");
        Book b = bookService.findOrCreate("Adet Kitabı", "Y", null, null, null, null);
        assertThatThrownBy(() -> donationService.create(donor, b, 0, TargetLevel.HEPSI, DonationSource.OWN, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void teslimatAkisiBulusmaTeslimTesekkur() {
        User donor = user("bagisci", false, "İzmir");
        User ogrenci = user("ogrenci", true, "Ankara");
        Donation d = newDonation(donor, 1);

        Claim c = donationService.claim(d.getId(), ogrenci);
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.MATCHED);

        // Kampüs teslimi: önce buluşma ayarlanır, kargo adımı yoktur
        donationService.arrange(c.getId(), donor, bulusma());
        assertThat(claims.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.ARRANGED);

        donationService.deliver(c.getId(), ogrenci);
        Claim delivered = claims.findById(c.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(ClaimStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isNotNull();

        donationService.thank(c.getId(), ogrenci, "Çok teşekkürler!");
        assertThat(notifications.findTop50ByUserOrderByCreatedAtDesc(donor))
                .extracting(Notification::getType).contains("claim_delivered", "thank_you");
    }

    @Test
    void teslimAlinmadanTesekkurEdilemez() {
        User donor = user("bagisci", false, "İzmir");
        User ogrenci = user("ogrenci", true, "Ankara");
        Donation d = newDonation(donor, 1);
        Claim c = donationService.claim(d.getId(), ogrenci);

        assertThatThrownBy(() -> donationService.thank(c.getId(), ogrenci, "erken"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("teslim aldığın");
    }

    @Test
    void baskasininTeslimatKaydinaMudahaleEdilemez() {
        User donor = user("bagisci", false, "İzmir");
        User ogrenci = user("ogrenci", true, "Ankara");
        User yabanci = user("yabanci", true, "Bursa");
        Donation d = newDonation(donor, 1);
        Claim c = donationService.claim(d.getId(), ogrenci);

        assertThatThrownBy(() -> donationService.ship(c.getId(), yabanci)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> donationService.deliver(c.getId(), yabanci)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void iptalAdediGeriAcarVeBagisiYenidenAcar() {
        User donor = user("bagisci", false, "İzmir");
        User ogrenci = user("ogrenci", true, "Ankara");
        Donation d = newDonation(donor, 1);

        Claim c = donationService.claim(d.getId(), ogrenci);
        assertThat(donationService.view(d.getId()).orElseThrow().donation().getStatus()).isEqualTo(DonationStatus.CLOSED);

        donationService.cancelClaim(c.getId(), ogrenci);

        DonationView v = donationService.view(d.getId()).orElseThrow();
        assertThat(v.getRemaining()).isEqualTo(1);
        assertThat(v.donation().getStatus()).isEqualTo(DonationStatus.OPEN);
        assertThat(notifications.findTop50ByUserOrderByCreatedAtDesc(donor))
                .extracting(Notification::getType).contains("claim_cancelled");
    }

    @Test
    void bulusmaAyarlansaBileTeslimdenOnceIptalEdilebilirTeslimdenSonraEdilemez() {
        User donor = user("bagisci", false, "İzmir");
        User ogrenci = user("ogrenci", true, "Ankara");
        Donation d = newDonation(donor, 1);
        Claim c = donationService.claim(d.getId(), ogrenci);
        donationService.arrange(c.getId(), donor, bulusma());

        donationService.cancelClaim(c.getId(), ogrenci);
        assertThat(donationService.view(d.getId()).orElseThrow().donation().getStatus())
                .isEqualTo(DonationStatus.OPEN);

        Claim c2 = donationService.claim(d.getId(), ogrenci);
        donationService.arrange(c2.getId(), donor, bulusma());
        donationService.deliver(c2.getId(), ogrenci);

        assertThatThrownBy(() -> donationService.cancelClaim(c2.getId(), ogrenci))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("iptal edilemez");
    }

    @Test
    void kargoKapaliykenKargolamaReddedilir() {
        User donor = user("bagisci", false, "İzmir");
        User ogrenci = user("ogrenci", true, "Ankara");
        Claim c = donationService.claim(newDonation(donor, 1).getId(), ogrenci);

        assertThatThrownBy(() -> donationService.ship(c.getId(), donor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kargo akışı kapalı");
    }

    @Test
    void bagisKapatilipYenidenAcilabilir() {
        User donor = user("bagisci", false, "İzmir");
        Donation d = newDonation(donor, 2);

        donationService.close(d.getId(), donor);
        assertThat(donationService.view(d.getId()).orElseThrow().donation().getStatus()).isEqualTo(DonationStatus.CLOSED);

        donationService.reopen(d.getId(), donor);
        assertThat(donationService.view(d.getId()).orElseThrow().donation().getStatus()).isEqualTo(DonationStatus.OPEN);
    }

    @Test
    void talepsizBagisSilinirTalepliSilinemez() {
        User donor = user("bagisci", false, "İzmir");
        User ogrenci = user("ogrenci", true, "Ankara");

        Donation bos = newDonation(donor, 1);
        donationService.delete(bos.getId(), donor);
        assertThat(donationService.view(bos.getId())).isEmpty();

        Donation dolu = newDonation(donor, 1);
        donationService.claim(dolu.getId(), ogrenci);
        assertThatThrownBy(() -> donationService.delete(dolu.getId(), donor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("silinemez");
    }

    @Test
    void baskasininBagisiYonetilemez() {
        User donor = user("bagisci", false, "İzmir");
        User yabanci = user("yabanci", false, "Bursa");
        Donation d = newDonation(donor, 1);

        assertThatThrownBy(() -> donationService.close(d.getId(), yabanci))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sana ait değil");
        assertThatThrownBy(() -> donationService.delete(d.getId(), yabanci)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void iptalSonrasiKotaSerbestKalir() {
        User donor = user("bagisci", false, "İzmir");
        User uye = user("uye", false, "Ankara");   // üye: haftada 1

        Donation d1 = newDonation(donor, 1);
        backdate(d1, 3);
        Claim c = donationService.claim(d1.getId(), uye);

        Donation d2 = newDonation(donor, 1);
        backdate(d2, 3);
        assertThat(donationService.eligibility(donationService.view(d2.getId()).orElseThrow(), uye).code())
                .isEqualTo("QUOTA_FULL");

        donationService.cancelClaim(c.getId(), uye);
        assertThat(donationService.eligibility(donationService.view(d2.getId()).orElseThrow(), uye).allowed()).isTrue();
    }

    @Test
    void esZamanliTaleplerdeAsiriDagitimOlmaz() throws Exception {
        User donor = user("donor-race", false, "İzmir");
        int toplamAdet = 2;
        Donation d = newDonation(donor, toplamAdet);
        backdate(d, 3);

        int threadSayisi = 10;
        java.util.List<User> ogrenciler = new java.util.ArrayList<>();
        for (int i = 0; i < threadSayisi; i++) {
            ogrenciler.add(user("race-ogrenci-" + i, true, "Ankara"));
        }

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadSayisi);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger basariliSayisi = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger hataSayisi = new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (User ogr : ogrenciler) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    donationService.claim(d.getId(), ogr);
                    basariliSayisi.incrementAndGet();
                } catch (Exception ex) {
                    hataSayisi.incrementAndGet();
                }
            }));
        }

        startLatch.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(basariliSayisi.get()).isEqualTo(toplamAdet);
        assertThat(hataSayisi.get()).isEqualTo(threadSayisi - toplamAdet);

        DonationView v = donationService.view(d.getId()).orElseThrow();
        assertThat(v.getRemaining()).isZero();
        assertThat(v.donation().getStatus()).isEqualTo(DonationStatus.CLOSED);
        assertThat(claims.countByDonation(d)).isEqualTo(toplamAdet);
    }

    @Test
    void bagisTakasaAktarilirVeBagistanSilinir() {
        User donor = user("aktar-donor", false, "İzmir");
        Donation d = newDonation(donor, 1);

        SwapBook sb = donationService.moveToSwap(d.getId(), donor, "Takas için roman tercih ederim");

        assertThat(sb).isNotNull();
        assertThat(sb.getBook().getId()).isEqualTo(d.getBook().getId());
        assertThat(sb.getUser().getId()).isEqualTo(donor.getId());
        assertThat(sb.getStatus()).isEqualTo(SwapBookStatus.OPEN);
        assertThat(sb.getNote()).isEqualTo("Takas için roman tercih ederim");

        // Bağış listesinden silindiğini doğrula
        assertThat(donationService.view(d.getId())).isEmpty();
    }

    @Test
    void talepAlinmisBagisTakasaAktarilamaz() {
        User donor = user("dolu-donor", false, "İzmir");
        User ogrenci = user("dolu-ogrenci", true, "Ankara");
        Donation d = newDonation(donor, 1);
        donationService.claim(d.getId(), ogrenci);

        assertThatThrownBy(() -> donationService.moveToSwap(d.getId(), donor, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("takasa aktarılamaz");
    }
}
