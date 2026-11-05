package app.kitappla.repo;

import app.kitappla.domain.Book;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    @org.springframework.data.jpa.repository.Query("""
           select b from Book b
           where lower(b.title) = lower(:title)
             and ((:author is null and (b.author is null or b.author = ''))
                  or (:author is not null and lower(b.author) = lower(:author)))
           order by b.id asc
           """)
    List<Book> findAllByTitleAndAuthorNormalized(@org.springframework.data.repository.query.Param("title") String title,
                                                 @org.springframework.data.repository.query.Param("author") String author);

    default Optional<Book> findFirstByTitleAndAuthorNormalized(String title, String author) {
        return findAllByTitleAndAuthorNormalized(title, author).stream().findFirst();
    }

    /** Arama metni yokken gösterilen ilk 50 kitap (tüm tabloyu okumadan). */
    List<Book> findTop50ByOrderByTitleAsc();
    List<Book> findTop50ByTitleContainingIgnoreCaseOrAuthorContainingIgnoreCaseOrderByTitleAsc(String t, String a);
}
