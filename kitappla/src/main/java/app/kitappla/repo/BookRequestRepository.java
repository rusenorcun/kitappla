package app.kitappla.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import app.kitappla.domain.BookRequest;
import app.kitappla.domain.RequestStatus;
import app.kitappla.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookRequestRepository extends JpaRepository<BookRequest, Long> {

    /** Şablonlar kitap ve isteyene eriştiği için ilişkiler birlikte çekilir (open-in-view kapalı). */
    @Query("""
           select r from BookRequest r
           join fetch r.book
           join fetch r.student
           left join fetch r.fulfilledBy
           left join fetch r.meeting.point
           where r.status = :status
           order by r.createdAt desc
           """)
    List<BookRequest> findByStatusWithDetails(@Param("status") RequestStatus status);

    /** Açık istekler, en yeni önce. Arama ve sayfalama veritabanında ({@code desen}: küçük harfli LIKE). */
    @Query(value = """
           select r from BookRequest r
           join fetch r.book b
           join fetch r.student st
           left join fetch r.fulfilledBy
           left join fetch r.meeting.point
           where (r.status = app.kitappla.domain.RequestStatus.OPEN
                  or (r.status = app.kitappla.domain.RequestStatus.FULFILLED and r.meeting.arrangedAt is null))
             and st.blocked = false
             and (lower(b.title) like :desen or lower(b.author) like :desen)
           order by r.createdAt desc, r.id desc
           """,
           countQuery = """
           select count(r) from BookRequest r join r.book b
           where (r.status = app.kitappla.domain.RequestStatus.OPEN
                  or (r.status = app.kitappla.domain.RequestStatus.FULFILLED and r.meeting.arrangedAt is null))
             and r.student.blocked = false
             and (lower(b.title) like :desen or lower(b.author) like :desen)
           """)
    Page<BookRequest> findOpenPage(@Param("desen") String desen, Pageable pageable);

    @Query("""
           select r from BookRequest r
           join fetch r.book
           join fetch r.student
           left join fetch r.fulfilledBy
           left join fetch r.meeting.point
           where r.student = :student
           order by r.createdAt desc
           """)
    List<BookRequest> findByStudentWithDetails(@Param("student") User student);

    @Query("""
           select r from BookRequest r
           join fetch r.book
           join fetch r.student
           left join fetch r.fulfilledBy
           left join fetch r.meeting.point
           where r.fulfilledBy = :user
           order by r.fulfilledAt desc
           """)
    List<BookRequest> findByFulfilledByWithDetails(@Param("user") User user);

    @Query("""
           select r from BookRequest r
           join fetch r.book
           join fetch r.student
           left join fetch r.fulfilledBy
           left join fetch r.meeting.point
           where r.id = :id
           """)
    Optional<BookRequest> findByIdWithDetails(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           select r from BookRequest r
           join fetch r.book
           join fetch r.student
           left join fetch r.fulfilledBy
           left join fetch r.meeting.point
           where r.id = :id
           """)
    Optional<BookRequest> findByIdWithDetailsForUpdate(@Param("id") Long id);

    @Query("""
           select r from BookRequest r
           join fetch r.book
           join fetch r.student
           left join fetch r.fulfilledBy
           left join fetch r.meeting.point
           where r.status = :status
             and r.meeting.arrangedAt is not null
             and r.meeting.remindedAt is null
             and r.meeting.at between :simdi and :esik
           """)
    List<BookRequest> findYaklasanBulusmalar(@Param("status") RequestStatus status,
                                             @Param("simdi") java.time.Instant simdi,
                                             @Param("esik") java.time.Instant esik);

    long countByStudentAndStatusInAndFulfilledAtAfter(User student, List<RequestStatus> statuses, Instant after);
    long countByStudent(User student);
    long countByFulfilledBy(User fulfilledBy);
    long countByStatus(RequestStatus status);
    long countByStudentAndStatus(User student, RequestStatus status);
    boolean existsByStudentAndBookAndStatus(User student, app.kitappla.domain.Book book, RequestStatus status);

    /** Üyenin isteyen ya da karşılayan olarak taraf olduğu, verilen durumlardaki istekler (askıya alma için). */
    @Query("""
           select r from BookRequest r
           join fetch r.book join fetch r.student left join fetch r.fulfilledBy
           where (r.student = :uye or r.fulfilledBy = :uye) and r.status in :durumlar
           """)
    List<BookRequest> findTarafOlduguByStatus(@Param("uye") User uye,
                                              @Param("durumlar") java.util.Collection<RequestStatus> durumlar);

    @Query("select r.id from BookRequest r where (r.student = :uye or r.fulfilledBy = :uye) and r.status in :durumlar")
    List<Long> findTarafOlduguIdsByStatus(@Param("uye") User uye,
                                          @Param("durumlar") java.util.Collection<RequestStatus> durumlar);

    /** Pano: isteyen ya da karşılayan olarak süren istekler. */
    long countByStudentAndStatusIn(User student, java.util.Collection<RequestStatus> statuses);
    long countByFulfilledByAndStatusIn(User fulfilledBy, java.util.Collection<RequestStatus> statuses);
}
