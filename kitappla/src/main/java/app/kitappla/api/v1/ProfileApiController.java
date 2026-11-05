package app.kitappla.api.v1;

import app.kitappla.api.dto.*;
import app.kitappla.config.Features;
import app.kitappla.domain.School;
import app.kitappla.domain.SchoolLevel;
import app.kitappla.domain.User;
import app.kitappla.security.CurrentUser;
import app.kitappla.service.DocumentService;
import app.kitappla.service.Quota;
import app.kitappla.service.QuotaService;
import app.kitappla.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ProfileApiController {

    private final UserService userService;
    private final QuotaService quotaService;
    private final Features features;
    private final DocumentService documentService;

    public ProfileApiController(UserService userService,
                                QuotaService quotaService,
                                Features features,
                                DocumentService documentService) {
        this.userService = userService;
        this.quotaService = quotaService;
        this.features = features;
        this.documentService = documentService;
    }

    @GetMapping("/me")
    public ResponseEntity<MeDto> getProfile() {
        User user = CurrentUser.get();
        if (user == null) {
            throw new IllegalStateException("Giriş yapmalısınız.");
        }
        Quota quota = quotaService.quotaFor(user);
        return ResponseEntity.ok(ApiDtoMapper.toMeDto(user, quota));
    }

    @PutMapping("/me")
    public ResponseEntity<UserDto> updateProfile(@Valid @RequestBody ProfileUpdateBody body) {
        User user = CurrentUser.get();
        if (user == null) {
            throw new IllegalStateException("Giriş yapmalısınız.");
        }

        School school = School.of(body.school());

        User updated = userService.updateProfile(
                user, body.name(), features.isAddress() ? body.address() : null, body.phone(), school);
        return ResponseEntity.ok(ApiDtoMapper.toUserDto(updated));
    }

    /** Hesap e-postası doğrulama bağlantısını yeniden gönderir; bağlantı uygulamaya geri döner. */
    @PostMapping("/me/email-verification")
    public ResponseEntity<MessageDto> resendEmailVerification() {
        User user = CurrentUser.get();
        if (user == null) {
            throw new IllegalStateException("Giriş yapmalısınız.");
        }
        userService.resendAccountVerification(user, true);
        return ResponseEntity.accepted()
                .body(new MessageDto(user.getEmail() + " adresine doğrulama bağlantısı gönderildi."));
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeBody body) {
        User user = CurrentUser.get();
        if (user == null) {
            throw new IllegalStateException("Giriş yapmalısınız.");
        }
        userService.changePassword(user, body.currentPassword(), body.newPassword(), body.confirmPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/student")
    public ResponseEntity<UserDto> verifyStudent(@Valid @RequestBody StudentEmailBody body) {
        User user = CurrentUser.get();
        if (user == null) {
            throw new IllegalStateException("Giriş yapmalısınız.");
        }
        User updated = userService.verifyStudentEmail(user, body.email(), true);
        return ResponseEntity.ok()
                .header("X-Student-Verification-Sent", Boolean.toString(updated.isStudentVerificationSent()))
                .body(ApiDtoMapper.toUserDto(updated));
    }

    public record StudentConfirmationBody(@jakarta.validation.constraints.NotBlank String token) {}

    @PostMapping("/me/student/confirm")
    public ResponseEntity<UserDto> confirmStudent(@Valid @RequestBody StudentConfirmationBody body) {
        User user = CurrentUser.get();
        if (user == null) throw new IllegalStateException("Giriş yapmalısınız.");
        return ResponseEntity.ok(ApiDtoMapper.toUserDto(userService.confirmStudentEmail(user, body.token())));
    }

    @PostMapping(value = "/me/student/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserDto> uploadStudentDocument(
            @RequestParam(required = false) String schoolLevel,
            @RequestParam(required = false) String documentNo,
            @RequestParam(required = false) MultipartFile document) {
        User user = CurrentUser.get();
        if (user == null) {
            throw new IllegalStateException("Giriş yapmalısınız.");
        }
        if (!features.isDocument()) {
            throw new IllegalStateException("Öğrenci doğrulaması okul e-postasıyla yapılıyor.");
        }
        String savedPath = null;
        try {
            if (document != null && !document.isEmpty()) {
                savedPath = documentService.save(document);
            }
            SchoolLevel level = (schoolLevel == null || schoolLevel.isBlank())
                    ? null : SchoolLevel.valueOf(schoolLevel.trim().toUpperCase(java.util.Locale.ROOT));
            User updated = userService.applyForStudent(user, level, documentNo, savedPath);
            return ResponseEntity.ok(ApiDtoMapper.toUserDto(updated));
        } catch (Exception ex) {
            documentService.discard(savedPath);
            throw ex;
        }
    }

    @GetMapping("/quota")
    public ResponseEntity<QuotaDto> getQuota() {
        User user = CurrentUser.get();
        if (user == null) {
            throw new IllegalStateException("Giriş yapmalısınız.");
        }
        Quota quota = quotaService.quotaFor(user);
        return ResponseEntity.ok(ApiDtoMapper.toQuotaDto(quota));
    }
}
