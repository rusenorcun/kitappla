package app.kitappla.repo;

import app.kitappla.domain.StudentStatus;
import app.kitappla.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Kota denetimini sıraya sokmak için üye satırını kilitler. Kilit sırası her yerde
     * "ilan → üye" olmalı (bağış/istek satırı önce): gelinmedi akışı da bu sırayla yazar.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByDocumentNo(String documentNo);
    Optional<User> findByStudentEmail(String studentEmail);
    long countByStudentStatus(StudentStatus status);

    /** Belgeyle başvurup incelemede olanlar (okul e-postası doğrulaması bekleyenler hariç). */
    List<User> findByStudentStatusAndDocumentPathIsNotNullOrderByCreatedAtDesc(StudentStatus status);
    long countByStudentStatusAndDocumentPathIsNotNull(StudentStatus status);
    List<User> findTop100ByStudentStatusOrderByCreatedAtDesc(StudentStatus status);
    List<User> findTop200ByOrderByCreatedAtDesc();
    List<User> findTop200ByNameContainingIgnoreCaseOrEmailContainingIgnoreCaseOrderByCreatedAtDesc(String name, String email);
    long countByAdminTrue();

    /** Ana sayfadaki üye sayısı: yalnızca e-posta adresini doğrulamış kullanıcılar. */
    long countByEmailVerifiedTrue();

    /** Şikâyet bildirimi gönderilecek yöneticiler (tüm üyeleri okumadan). */
    List<User> findByAdminTrue();

    /** Şikâyet sohbetinde varsayılan yönetici tarafı. */
    Optional<User> findFirstByAdminTrueOrderByIdAsc();
    long countByBlockedTrue();
}
