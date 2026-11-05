package app.kitappla.api.v1;

import app.kitappla.api.dto.ApiDtoMapper;
import app.kitappla.api.dto.BookDto;
import app.kitappla.api.dto.BookMetadataDto;
import app.kitappla.api.dto.PreviewBody;
import app.kitappla.domain.Book;
import app.kitappla.service.BookMetadata;
import app.kitappla.service.BookService;
import app.kitappla.service.OpenGraphService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/books")
public class BookApiController {

    private final BookService bookService;
    private final OpenGraphService openGraphService;

    public BookApiController(BookService bookService, OpenGraphService openGraphService) {
        this.bookService = bookService;
        this.openGraphService = openGraphService;
    }

    @GetMapping
    public ResponseEntity<List<BookDto>> searchBooks(@RequestParam(required = false) String q) {
        List<Book> books = bookService.search(q);
        List<BookDto> dtos = books.stream().map(ApiDtoMapper::toBookDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/preview")
    public ResponseEntity<BookMetadataDto> previewBook(@Valid @RequestBody PreviewBody body) {
        BookMetadata meta = bookService.preview(body.purchaseLink());
        boolean found = meta != null && !meta.isEmpty();
        return ResponseEntity.ok(ApiDtoMapper.toBookMetadataDto(meta, found));
    }
}
