package app.kitappla.web;

import app.kitappla.domain.*;
import app.kitappla.repo.BookRepository;
import app.kitappla.repo.DonationRepository;
import app.kitappla.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tek bir bozuk kayıt bütün listeyi düşürmemeli, hata da markalı sayfada gösterilmeli.
 *
 * <p>Kendi veritabanında çalışır: sütunu NULL yapabilmek için şemayı gevşetiyor,
 * bu diğer testlerin şemasına bulaşmasın.</p>
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:kitappla-hata;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HataDayanikliligiTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired BookRepository books;
    @Autowired DonationRepository donations;
    @Autowired JdbcTemplate jdbc;

    private Donation bagisOlustur(String kitapAdi) {
        User donor = new User();
        donor.setName("Bağışçı");
        donor.setEmail("bagisci-" + UUID.randomUUID() + "@test.local");
        donor.setPasswordHash("x");
        donor = users.save(donor);

        Book b = new Book();
        b.setTitle(kitapAdi);
        b.setAuthor("Yazar");
        b = books.save(b);

        Donation d = new Donation();
        d.setDonor(donor);
        d.setBook(b);
        d.setQuantity(1);
        return donations.save(d);
    }

    @Test
    void bosSeviyeliKayitKesfetiDusurmez() throws Exception {
        String ad = "Bozuk Kayıt " + UUID.randomUUID();
        Donation d = bagisOlustur(ad);

        // Eski bir veritabanında sütun boş kalmış olabilir; şablon her satırda
        // targetLevel.name() çağırdığı için bu tek satır tüm sayfayı 500 yapıyordu.
        jdbc.execute("alter table donations alter column target_level set null");
        jdbc.update("update donations set target_level = null where id = ?", d.getId());

        mvc.perform(get("/kesfet"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ad)));

        mvc.perform(get("/kitap/" + d.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ad)));
    }

    @Test
    void bosOlusturmaZamaniSayfayiDusurmez() throws Exception {
        String ad = "Zamansız Kayıt " + UUID.randomUUID();
        Donation d = bagisOlustur(ad);

        jdbc.execute("alter table donations alter column created_at set null");
        jdbc.update("update donations set created_at = null where id = ?", d.getId());

        mvc.perform(get("/kitap/" + d.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ad)));
    }

    @Test
    void sunucuHatasiMarkaliSayfadaGosterilir() throws Exception {
        // DefaultErrorViewResolver "error/500" ya da "error/5xx" arar; "error/error" diye
        // bakmaz. Şablon yanlış adla durduğu sürece kullanıcı Whitelabel sayfası görüyordu.
        mvc.perform(get("/error").accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Bir şeyler ters gitti")))
                .andExpect(content().string(containsString("KitAppLa")));
    }

    @Test
    void hataSayfasindaAciklamaVeNedenGosterilir() throws Exception {
        mvc.perform(get("/error")
                        .requestAttr("jakarta.servlet.error.status_code", 404)
                        .requestAttr("jakarta.servlet.error.message", "Aradığınız kayıt bulunamadı.")
                        .accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("404")))
                .andExpect(content().string(containsString("KitAppLa")));
    }
}
