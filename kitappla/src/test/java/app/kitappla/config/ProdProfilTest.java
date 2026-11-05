package app.kitappla.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Prod profilinin güvenlik ve kapasite ayarları yanlışlıkla geri alınmasın. */
class ProdProfilTest {

    private final Properties prod = yukle();

    private static Properties yukle() {
        try {
            return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void h2KonsoluKapali() {
        assertThat(prod.getProperty("spring.h2.console.enabled")).isEqualTo("false");
    }

    @Test
    void oturumCereziGuvenli() {
        assertThat(prod.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(prod.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(prod.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
    }

    @Test
    void sablonOnbellegiVeBaglantiHavuzuAyarli() {
        assertThat(prod.getProperty("spring.thymeleaf.cache")).isEqualTo("true");
        assertThat(prod.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("40");
    }

    @Test
    void tomcatParcaSiniriAyarli() {
        assertThat(prod.getProperty("server.tomcat.max-part-count")).isEqualTo("50");
    }
}
