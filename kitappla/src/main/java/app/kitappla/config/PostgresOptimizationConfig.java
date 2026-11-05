package app.kitappla.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class PostgresOptimizationConfig {

    private static final Logger log = LoggerFactory.getLogger(PostgresOptimizationConfig.class);
    private final JdbcTemplate jdbcTemplate;

    public PostgresOptimizationConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void optimizeDatabase() {
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            String dbName = conn.getMetaData().getDatabaseProductName();
            if ("PostgreSQL".equalsIgnoreCase(dbName)) {
                log.info("PostgreSQL veritabanı tespit edildi, performans iyileştirmeleri uygulanıyor...");
                
                // pg_trgm eklentisi (metin aramaları için)
                jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm;");
                
                // Kitap arama indeksleri (gin ve trgm ile)
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS ix_books_title_trgm ON books USING gin (lower(title) gin_trgm_ops);");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS ix_books_author_trgm ON books USING gin (lower(author) gin_trgm_ops);");
                
                log.info("PostgreSQL optimizasyonları başarıyla uygulandı.");
            }
        } catch (Exception e) {
            log.warn("Veritabanı optimizasyonları sırasında bir hata oluştu: ", e);
        }
    }
}
