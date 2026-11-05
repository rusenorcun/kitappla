package app.kitappla.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import app.kitappla.domain.SwapBook;
import app.kitappla.domain.SwapBookStatus;
import app.kitappla.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SwapBookRepository extends JpaRepository<SwapBook, Long> {

    /**
     * Başkalarının takasa açtığı kitaplar (keşif), en yeni önce. Arama ve sayfalama veritabanında;
     * {@code desen} küçük harfli bir LIKE desenidir ("%" filtre yok demektir).
     */
    @Query(value = """
           select s from SwapBook s
           join fetch s.book b
           join fetch s.user u
           where s.status = app.kitappla.domain.SwapBookStatus.OPEN and u.id <> :benimId and u.blocked = false
             and (lower(b.title) like :desen or lower(b.author) like :desen)
           order by s.createdAt desc, s.id desc
           """,
           countQuery = """
           select count(s) from SwapBook s join s.book b
           where s.status = app.kitappla.domain.SwapBookStatus.OPEN and s.user.id <> :benimId and s.user.blocked = false
             and (lower(b.title) like :desen or lower(b.author) like :desen)
           """)
    Page<SwapBook> findOpenOfOthersPage(@Param("benimId") long benimId,
                                        @Param("desen") String desen,
                                        Pageable pageable);

    @Query("""
           select s from SwapBook s
           join fetch s.book
           join fetch s.user
           where s.user = :user
           order by s.createdAt desc
           """)
    List<SwapBook> findByUserWithDetails(@Param("user") User user);

    @Query("""
           select s from SwapBook s
           join fetch s.book
           join fetch s.user
           where s.user = :user and s.status = :status
           order by s.createdAt desc
           """)
    List<SwapBook> findByUserAndStatusWithDetails(@Param("user") User user, @Param("status") SwapBookStatus status);

    @Query("""
           select s from SwapBook s
           join fetch s.book
           join fetch s.user
           where s.id = :id
           """)
    Optional<SwapBook> findByIdWithDetails(@Param("id") Long id);

    /**
     * İlan satırını yazma kilidiyle okur. Takas kabulünde iki ilan aynı anda
     * kapatılıyor; kilit olmadan iki ayrı teklif aynı kitabı takaslayabiliyordu.
     * Çağıran taraf kilitleri <b>kimlik sırasıyla</b> almalıdır (karşılıklı
     * kilitlenmeyi önlemek için).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SwapBook s where s.id = :id")
    Optional<SwapBook> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           select s from SwapBook s
           join fetch s.book
           join fetch s.user
           where s.id = :id
           """)
    Optional<SwapBook> findByIdWithDetailsForUpdate(@Param("id") Long id);

    @Query("""
           select s from SwapBook s
           join fetch s.book
           join fetch s.user
           where s.status = :status
           order by s.createdAt desc
           """)
    List<SwapBook> findByStatusWithDetails(@Param("status") SwapBookStatus status);

    long countByStatus(SwapBookStatus status);
    long countByUser(User user);
    Optional<SwapBook> findByUserAndBook_Id(User user, Long bookId);
}
