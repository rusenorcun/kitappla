package app.kitappla.repo;

import java.util.Set;
import java.util.Collection;
import app.kitappla.domain.Claim;
import app.kitappla.domain.ClaimStatus;
import app.kitappla.domain.Donation;
import app.kitappla.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, Long> {
    long countByDonation(Donation donation);

    /** Kalan adet hesabı: gelinmeyen ve iptal edilen talepler kitabı havuza geri bırakır (bkz. ClaimStatus.ADET_TUTMAYAN). */
    long countByDonationAndStatusNotIn(Donation donation, Collection<ClaimStatus> statuses);

    /** Bir bağıştaki alınmış adet (projeksiyon). */
    interface AlinanAdet {
        Long getDonationId();
        Long getAdet();
    }

    /** Birden çok bağışın alınmış (gelinmedi hariç) adetleri tek sorguda; talebi olmayan bağış listede yer almaz. */
    @Query("""
           select c.donation.id as donationId, count(c) as adet from Claim c
           where c.donation.id in :donationIds
             and c.status not in (app.kitappla.domain.ClaimStatus.NO_SHOW, app.kitappla.domain.ClaimStatus.CANCELLED)
           group by c.donation.id
           """)
    List<AlinanAdet> countActiveByDonationIds(@Param("donationIds") Collection<Long> donationIds);

    /** Kişinin talep ettiği bağışların kimlikleri (listede "zaten aldın" kontrolü için; iptal edilenler hariç). */
    @Query("select c.donation.id from Claim c where c.student = :student and c.status <> app.kitappla.domain.ClaimStatus.CANCELLED")
    Set<Long> findDonationIdsByStudent(@Param("student") User student);

    Optional<Claim> findByDonationAndStudent(Donation donation, User student);

    @Query("""
           select c from Claim c
           join fetch c.donation d
           join fetch d.book
           join fetch d.donor
           left join fetch c.meeting.point
           where c.student = :student
           order by c.createdAt desc
           """)
    List<Claim> findByStudentWithDetails(@Param("student") User student);

    @Query("""
           select c from Claim c
           join fetch c.donation d
           join fetch d.book
           join fetch d.donor
           join fetch c.student
           left join fetch c.meeting.point
           where c.id = :id
           """)
    Optional<Claim> findByIdWithDetails(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           select c from Claim c
           join fetch c.donation d
           join fetch d.book
           join fetch d.donor
           join fetch c.student
           left join fetch c.meeting.point
           where c.id = :id
           """)
    Optional<Claim> findByIdWithDetailsForUpdate(@Param("id") Long id);

    @Query("""
           select c from Claim c
           join fetch c.student
           left join fetch c.meeting.point
           where c.donation = :donation
           order by c.createdAt
           """)
    List<Claim> findByDonationWithStudent(@Param("donation") Donation donation);

    /** Birden çok bağışın talepleri (bağışlarım listesi); bağış başına ayrı sorgu atılmaz. */
    @Query("""
           select c from Claim c
           join fetch c.donation
           join fetch c.student
           left join fetch c.meeting.point
           where c.donation in :donations
           order by c.createdAt
           """)
    List<Claim> findByDonationsWithStudent(@Param("donations") Collection<Donation> donations);

    /** Yaklaşan ve henüz hatırlatılmamış buluşmalar. */
    @Query("""
           select c from Claim c
           join fetch c.donation d
           join fetch d.book
           join fetch d.donor
           join fetch c.student
           left join fetch c.meeting.point
           where c.status = :status
             and c.meeting.arrangedAt is not null
             and c.meeting.remindedAt is null
             and c.meeting.at between :simdi and :esik
           """)
    List<Claim> findYaklasanBulusmalar(@Param("status") ClaimStatus status,
                                       @Param("simdi") Instant simdi,
                                       @Param("esik") Instant esik);

    /** Kota sayımı: askı nedeniyle iptal edilen talep hakkı iade eder, sayılmaz. */
    long countByStudentAndStatusNotAndCreatedAtAfter(User student, ClaimStatus status, Instant after);

    /** Üyenin alıcı ya da bağışçı olarak taraf olduğu, verilen durumlardaki talepler (askıya alma için). */
    @Query("""
           select c from Claim c
           join fetch c.donation d join fetch d.book join fetch d.donor join fetch c.student
           where (c.student = :uye or d.donor = :uye) and c.status in :durumlar
           """)
    List<Claim> findTarafOlduguByStatus(@Param("uye") User uye, @Param("durumlar") Collection<ClaimStatus> durumlar);

    @Query("select c.id from Claim c join c.donation d where (c.student = :uye or d.donor = :uye) and c.status in :durumlar")
    List<Long> findTarafOlduguIdsByStatus(@Param("uye") User uye, @Param("durumlar") Collection<ClaimStatus> durumlar);

    long countByStatus(ClaimStatus status);
    long countByStudent(User student);

    /** Pano: alıcı olarak süren teslimatlar. */
    long countByStudentAndStatusIn(User student, Collection<ClaimStatus> statuses);

    /** Pano: bağışçısı olduğum bağışlarda süren teslimatlar. */
    @Query("select count(c) from Claim c where c.donation.donor = :donor and c.status in :statuses")
    long countByDonorAndStatusIn(@Param("donor") User donor, @Param("statuses") Collection<ClaimStatus> statuses);
}
