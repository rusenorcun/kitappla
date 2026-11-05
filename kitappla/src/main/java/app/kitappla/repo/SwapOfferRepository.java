package app.kitappla.repo;

import app.kitappla.domain.OfferStatus;
import app.kitappla.domain.SwapBook;
import app.kitappla.domain.SwapOffer;
import app.kitappla.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SwapOfferRepository extends JpaRepository<SwapOffer, Long> {

    String DETAILS = """
           select o from SwapOffer o
           join fetch o.fromUser
           join fetch o.toUser
           join fetch o.offeredSwapBook osb join fetch osb.book
           join fetch o.targetSwapBook tsb join fetch tsb.book
           left join fetch o.meeting.point
           """;

    @Query(DETAILS + " where o.toUser = :user order by o.createdAt desc")
    List<SwapOffer> findIncoming(@Param("user") User user);

    @Query(DETAILS + " where o.fromUser = :user order by o.createdAt desc")
    List<SwapOffer> findOutgoing(@Param("user") User user);

    @Query(DETAILS + " where o.id = :id")
    Optional<SwapOffer> findByIdWithDetails(@Param("id") Long id);

    /** Teklif satırını kilitler: iki tarafın eşzamanlı onayı birbirinin damgasını ezmesin. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SwapOffer o where o.id = :id")
    Optional<SwapOffer> findByIdForUpdate(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query(DETAILS + " where o.id = :id")
    Optional<SwapOffer> findByIdWithDetailsForUpdate(@Param("id") Long id);

    /** Teklifin dokunduğu iki takas ilanının kimlikleri (kabul öncesi kilitleme sırası için). */
    interface TakasKitapKimlikleri {
        Long getOfferedId();
        Long getTargetId();
    }

    /**
     * Teklifi yüklemeden yalnızca ilan kimliklerini okur. Kabul akışı ilan satırlarını
     * kilitledikten <i>sonra</i> teklifi tazeliyor; teklif burada yüklenirse oturumda
     * eski durumuyla önbelleğe girer ve kilit işe yaramaz.
     */
    @Query("""
           select osb.id as offeredId, tsb.id as targetId
           from SwapOffer o
           join o.offeredSwapBook osb
           join o.targetSwapBook tsb
           where o.id = :id
           """)
    Optional<TakasKitapKimlikleri> findSwapBookIds(@Param("id") Long id);

    /** Aynı hedefe aynı kişinin bekleyen teklifi var mı? */
    boolean existsByFromUserAndTargetSwapBookAndStatus(User fromUser, SwapBook target, OfferStatus status);

    /** Kabul edilince rakip teklifleri reddetmek için. */
    @Query("""
           select o from SwapOffer o
           where o.status = :status and o.id <> :exceptId
             and (o.targetSwapBook in :books or o.offeredSwapBook in :books)
           """)
    List<SwapOffer> findCompeting(@Param("status") OfferStatus status,
                                  @Param("exceptId") Long exceptId,
                                  @Param("books") List<SwapBook> books);

    /** Takas kitabına bağlı, sonuçlanmamış teklif var mı? */
    @Query("""
           select count(o) from SwapOffer o
           where (o.targetSwapBook = :book or o.offeredSwapBook = :book)
             and o.status in :statuses
           """)
    long countByBookAndStatuses(@Param("book") SwapBook book, @Param("statuses") List<OfferStatus> statuses);

    long countByFromUserOrToUser(User fromUser, User toUser);
    @Query(DETAILS + """
            where o.status = :status
              and o.meeting.arrangedAt is not null
              and o.meeting.remindedAt is null
              and o.meeting.at between :simdi and :esik
            """)
    List<SwapOffer> findYaklasanBulusmalar(@Param("status") OfferStatus status,
                                           @Param("simdi") java.time.Instant simdi,
                                           @Param("esik") java.time.Instant esik);

    long countByStatus(OfferStatus status);

    /** Üyenin gönderen ya da alan olarak taraf olduğu, verilen durumlardaki teklifler (askıya alma için). */
    @Query("""
           select o from SwapOffer o
           join fetch o.fromUser join fetch o.toUser
           join fetch o.offeredSwapBook ob join fetch ob.book
           join fetch o.targetSwapBook tb join fetch tb.book
           where (o.fromUser = :uye or o.toUser = :uye) and o.status in :durumlar
           """)
    List<SwapOffer> findTarafOlduguByStatus(@Param("uye") User uye,
                                            @Param("durumlar") java.util.Collection<OfferStatus> durumlar);

    @Query("select o.id from SwapOffer o where (o.fromUser = :uye or o.toUser = :uye) and o.status in :durumlar")
    List<Long> findTarafOlduguIdsByStatus(@Param("uye") User uye,
                                          @Param("durumlar") java.util.Collection<OfferStatus> durumlar);

    /** Pano: bana gelen ve yanıt bekleyen teklifler. */
    long countByToUserAndStatus(User toUser, OfferStatus status);

    /** Pano: taraf olduğum, verilen durumdaki teklifler. */
    @Query("select count(o) from SwapOffer o where (o.fromUser = :uye or o.toUser = :uye) and o.status = :status")
    long countTarafOlduguByStatus(@Param("uye") User uye, @Param("status") OfferStatus status);
}
