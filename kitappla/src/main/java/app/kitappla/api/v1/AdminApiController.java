package app.kitappla.api.v1;

import app.kitappla.api.dto.*;
import app.kitappla.domain.ConversationKind;
import app.kitappla.domain.Report;
import app.kitappla.domain.ReportKind;
import app.kitappla.domain.User;
import app.kitappla.security.CurrentUser;
import app.kitappla.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminApiController {

    private final AdminService adminService;
    private final ReportService reportService;
    private final PickupPointService pickupPointService;
    private final MessageService messageService;
    private final AdminMonitorService monitorService;

    public AdminApiController(AdminService adminService,
                              ReportService reportService,
                              PickupPointService pickupPointService,
                              MessageService messageService,
                              AdminMonitorService monitorService) {
        this.adminService = adminService;
        this.reportService = reportService;
        this.pickupPointService = pickupPointService;
        this.messageService = messageService;
        this.monitorService = monitorService;
    }

    // ---------- Pano & İstatistikler ----------

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsDto> getStats() {
        AdminStats stats = adminService.stats();
        return ResponseEntity.ok(ApiDtoMapper.toAdminStatsDto(stats));
    }

    @GetMapping("/monitor")
    public ResponseEntity<AdminMonitorDto> getMonitor(HttpServletRequest request) {
        String currentSessionId = request.getSession(false) != null ? request.getSession(false).getId() : null;
        return ResponseEntity.ok(monitorService.getMonitorData(currentSessionId));
    }

    @PostMapping("/monitor/sessions/{sessionId}/expire")
    public ResponseEntity<?> expireSession(@PathVariable String sessionId, HttpServletRequest request) {
        // Opak tanıtıcıyı gerçek oturum kimliğine çöz
        String realSessionId = monitorService.resolveSessionToken(sessionId);
        if (realSessionId == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Oturum bulunamadı."));
        }

        // Kendi oturumunu sonlandırmayı engelle
        String currentSessionId = request.getSession(false) != null ? request.getSession(false).getId() : null;
        if (currentSessionId != null && currentSessionId.equals(realSessionId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Kendi geçerli oturumunu sonlandıramazsın."));
        }

        boolean expired = monitorService.expireSessionByToken(sessionId);
        if (!expired) {
            return ResponseEntity.status(404).body(Map.of("error", "Oturum bulunamadı."));
        }
        return ResponseEntity.noContent().build();
    }

    // ---------- Öğrenci Belgeleri ----------

    @GetMapping("/pending-docs")
    public ResponseEntity<List<UserDto>> getPendingDocs() {
        List<User> list = adminService.pendingDocuments();
        List<UserDto> dtos = list.stream().map(ApiDtoMapper::toUserDto).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Belge dosyası (web: /admin/belge/{id}); mobil uygulama oturumuyla indirip cihazda açar. Tarayıcıya
     * yönlendirmek işe yaramıyordu: tarayıcıda uygulamanın oturumu yok.
     */
    @GetMapping("/docs/{userId}/file")
    public ResponseEntity<?> documentFile(@PathVariable Long userId) {
        try {
            return app.kitappla.web.BelgeYaniti.satirIci(userId, adminService.documentPathOf(userId));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(404).body(Map.of("error", "Belge bulunamadı."));
        } catch (java.io.IOException ex) {
            return ResponseEntity.status(500).body(Map.of("error", "Belge okunamadı."));
        }
    }

    @PostMapping("/docs/{id}/approve")
    public ResponseEntity<Void> approveDoc(@PathVariable Long id) {
        adminService.approveStudent(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/docs/{id}/reject")
    public ResponseEntity<Void> rejectDoc(@PathVariable Long id, @RequestParam(required = false) String reason) {
        adminService.rejectStudent(id, reason);
        return ResponseEntity.noContent().build();
    }

    // ---------- Üye Yönetimi ----------

    @GetMapping("/users")
    public ResponseEntity<List<UserDto>> getAllUsers(@RequestParam(required = false) String q) {
        List<User> list = adminService.searchUsers(q);
        List<UserDto> dtos = list.stream().map(ApiDtoMapper::toUserDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/users/{id}/block")
    public ResponseEntity<Void> toggleBlockUser(@PathVariable Long id) {
        User admin = CurrentUser.get();
        if (admin == null) throw new IllegalStateException("Giriş yapmalısınız.");

        User target = adminService.findUser(id)
                .orElseThrow(() -> new IllegalStateException("Kullanıcı bulunamadı."));
        adminService.setBlocked(admin, id, !target.isBlocked());
        return ResponseEntity.noContent().build();
    }

    public record SetAdminRoleBody(Boolean admin) {}

    @PostMapping("/users/{id}/role")
    public ResponseEntity<UserDto> toggleUserAdminRole(@PathVariable Long id, @RequestBody(required = false) SetAdminRoleBody body) {
        User admin = CurrentUser.get();
        if (admin == null) throw new IllegalStateException("Giriş yapmalısınız.");
        User target = adminService.findUser(id)
                .orElseThrow(() -> new IllegalStateException("Kullanıcı bulunamadı."));
        boolean makeAdmin = body != null && body.admin() != null ? body.admin() : !target.isAdmin();
        User updated = adminService.setAdmin(admin, id, makeAdmin);
        return ResponseEntity.ok(ApiDtoMapper.toUserDto(updated));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        User admin = CurrentUser.get();
        if (admin == null) throw new IllegalStateException("Giriş yapmalısınız.");
        adminService.deleteUser(admin, id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Teslim Noktaları Yönetimi (CRUD) ----------

    public record PickupPointInputBody(
            @NotBlank String campus,
            @NotBlank String name,
            String description
    ) {}

    @GetMapping("/pickup-points")
    public ResponseEntity<List<PickupPointDto>> getAllPickupPoints() {
        List<PickupPointDto> list = pickupPointService.all().stream()
                .map(ApiDtoMapper::toPickupPointDto)
                .toList();
        return ResponseEntity.ok(list);
    }

    @PostMapping("/pickup-points")
    public ResponseEntity<PickupPointDto> createPickupPoint(@Valid @RequestBody PickupPointInputBody body) {
        var p = pickupPointService.create(body.campus(), body.name(), body.description());
        return ResponseEntity.ok(ApiDtoMapper.toPickupPointDto(p));
    }

    @PutMapping("/pickup-points/{id}")
    public ResponseEntity<PickupPointDto> updatePickupPoint(@PathVariable Long id, @Valid @RequestBody PickupPointInputBody body) {
        var p = pickupPointService.update(id, body.campus(), body.name(), body.description());
        return ResponseEntity.ok(ApiDtoMapper.toPickupPointDto(p));
    }

    @PostMapping("/pickup-points/{id}/toggle-active")
    public ResponseEntity<PickupPointDto> togglePickupPointActive(@PathVariable Long id) {
        var p = pickupPointService.find(id)
                .orElseThrow(() -> new IllegalStateException("Nokta bulunamadı."));
        var updated = pickupPointService.setActive(id, !p.isActive());
        return ResponseEntity.ok(ApiDtoMapper.toPickupPointDto(updated));
    }

    // ---------- İçerik Moderasyonu ----------

    public record RemoveContentBody(String reason) {}

    @GetMapping("/content")
    public ResponseEntity<AdminContentDto> getContent() {
        User admin = CurrentUser.get();
        if (admin == null) throw new IllegalStateException("Giriş yapmalısınız.");

        List<DonationDto> donations = adminService.openDonations().stream()
                .map(ApiDtoMapper::toDonationDto)
                .toList();

        List<RequestDto> requests = adminService.openRequests().stream()
                .map(r -> ApiDtoMapper.toRequestDto(r, null))
                .toList();

        List<SwapListingDto> swaps = adminService.openSwapBooks().stream()
                .map(ApiDtoMapper::toSwapListingDto)
                .toList();

        return ResponseEntity.ok(new AdminContentDto(donations, requests, swaps));
    }

    @PostMapping("/content/donations/{id}/remove")
    public ResponseEntity<Void> removeDonation(@PathVariable Long id, @RequestBody(required = false) RemoveContentBody body) {
        String reason = body != null ? body.reason() : null;
        adminService.removeDonation(id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/content/requests/{id}/remove")
    public ResponseEntity<Void> removeRequest(@PathVariable Long id, @RequestBody(required = false) RemoveContentBody body) {
        String reason = body != null ? body.reason() : null;
        adminService.removeRequest(id, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/content/swaps/{id}/remove")
    public ResponseEntity<Void> removeSwapBook(@PathVariable Long id, @RequestBody(required = false) RemoveContentBody body) {
        String reason = body != null ? body.reason() : null;
        adminService.removeSwapBook(id, reason);
        return ResponseEntity.noContent().build();
    }

    // ---------- Şikâyetler ----------

    @GetMapping("/reports")
    public ResponseEntity<List<AdminReportDto>> getReports(@RequestParam(required = false, defaultValue = "false") boolean all) {
        List<Report> list = all ? reportService.all() : reportService.open();
        List<AdminReportDto> dtos = list.stream().map(ApiDtoMapper::toAdminReportDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<AdminReportDetailDto> getReportDetail(@PathVariable Long id) {
        User admin = CurrentUser.get();
        if (admin == null) throw new IllegalStateException("Giriş yapmalısınız.");

        Report r = reportService.find(id)
                .orElseThrow(() -> new IllegalStateException("Şikâyet bulunamadı."));

        AdminReportDto reportDto = ApiDtoMapper.toAdminReportDto(r);

        String entityTitle = null;
        String entitySubtitle = null;
        String entityDetails = null;
        String entityStatus = null;
        String entityExtra = null;
        List<ChatMessageDto> moderationMessages = null;

        if (r.getKind() == ReportKind.CONVERSATION) {
            try {
                var sohbet = messageService.requireForModeration(r.getRefId(), reportService);
                entityTitle = "Sohbet: " + (sohbet.getUserA() != null ? sohbet.getUserA().getName() : "") + " ve " + (sohbet.getUserB() != null ? sohbet.getUserB().getName() : "");
                var msgs = messageService.messagesOf(sohbet);
                moderationMessages = msgs.stream().map(m -> ApiDtoMapper.toChatMessageDto(m, admin)).toList();
            } catch (Exception ex) {
                entityDetails = "Sohbet yüklenemedi: " + ex.getMessage();
            }
        } else if (r.getKind() == ReportKind.CLAIM) {
            var claimOpt = reportService.findClaim(r.getRefId());
            if (claimOpt.isPresent()) {
                var c = claimOpt.get();
                entityTitle = c.getDonation() != null && c.getDonation().getBook() != null ? c.getDonation().getBook().getTitle() : "Bağış Talebi";
                entitySubtitle = "Bağışçı: " + (c.getDonation() != null && c.getDonation().getDonor() != null ? c.getDonation().getDonor().getName() : "") + " · Alan: " + (c.getStudent() != null ? c.getStudent().getName() : "");
                entityStatus = c.getStatus() != null ? c.getStatus().name() : null;
                if (c.getMeeting() != null && c.getMeeting().isArranged()) {
                    entityExtra = "Buluşma: " + c.getMeeting().getPlaceText() + " (" + c.getMeeting().getAtText() + ")";
                }
            }
        } else if (r.getKind() == ReportKind.REQUEST) {
            var reqOpt = reportService.findRequest(r.getRefId());
            if (reqOpt.isPresent()) {
                var req = reqOpt.get();
                entityTitle = req.getBook() != null ? req.getBook().getTitle() : "İstek";
                entitySubtitle = "İsteyen: " + (req.getStudent() != null ? req.getStudent().getName() : "") + (req.getFulfilledBy() != null ? " · Karşılayan: " + req.getFulfilledBy().getName() : "");
                entityStatus = req.getStatus() != null ? req.getStatus().name() : null;
            }
        } else if (r.getKind() == ReportKind.SWAP_OFFER) {
            var offerOpt = reportService.findOffer(r.getRefId());
            if (offerOpt.isPresent()) {
                var o = offerOpt.get();
                String offeredTitle = o.getOfferedSwapBook() != null && o.getOfferedSwapBook().getBook() != null ? o.getOfferedSwapBook().getBook().getTitle() : "?";
                String targetTitle = o.getTargetSwapBook() != null && o.getTargetSwapBook().getBook() != null ? o.getTargetSwapBook().getBook().getTitle() : "?";
                entityTitle = offeredTitle + " ↔ " + targetTitle;
                entitySubtitle = "Teklif Eden: " + (o.getOfferedSwapBook() != null && o.getOfferedSwapBook().getUser() != null ? o.getOfferedSwapBook().getUser().getName() : "");
                entityStatus = o.getStatus() != null ? o.getStatus().name() : null;
            }
        } else if (r.getKind() == ReportKind.DONATION) {
            var donOpt = reportService.findDonation(r.getRefId());
            if (donOpt.isPresent()) {
                var d = donOpt.get();
                entityTitle = d.getBook() != null ? d.getBook().getTitle() : "Bağış";
                entitySubtitle = "Bağışçı: " + (d.getDonor() != null ? d.getDonor().getName() : "") + (d.getBook() != null && d.getBook().getAuthor() != null ? " · Yazar: " + d.getBook().getAuthor() : "");
                entityStatus = d.getStatus() != null ? d.getStatus().name() : null;
                entityDetails = d.getDescription();
            }
        } else if (r.getKind() == ReportKind.SWAP_BOOK) {
            var swapOpt = reportService.findSwapBook(r.getRefId());
            if (swapOpt.isPresent()) {
                var sb = swapOpt.get();
                entityTitle = sb.getBook() != null ? sb.getBook().getTitle() : "Takas Kitabı";
                entitySubtitle = "Sahibi: " + (sb.getUser() != null ? sb.getUser().getName() : "") + (sb.getBook() != null && sb.getBook().getAuthor() != null ? " · Yazar: " + sb.getBook().getAuthor() : "");
                entityStatus = sb.getStatus() != null ? sb.getStatus().name() : null;
                entityDetails = sb.getNote();
            }
        }

        Long supportConvId = null;
        List<ChatMessageDto> supportMessages = null;
        var supportConvOpt = messageService.find(ConversationKind.REPORT, r.getId());
        if (supportConvOpt.isPresent()) {
            var sc = supportConvOpt.get();
            supportConvId = sc.getId();
            var msgs = messageService.messagesOf(sc);
            supportMessages = msgs.stream().map(m -> ApiDtoMapper.toChatMessageDto(m, admin)).toList();
        }

        AdminReportDetailDto detailDto = new AdminReportDetailDto(
                reportDto,
                entityTitle,
                entitySubtitle,
                entityDetails,
                entityStatus,
                entityExtra,
                moderationMessages,
                supportMessages,
                supportConvId
        );
        return ResponseEntity.ok(detailDto);
    }

    @PostMapping("/reports/{id}/messages")
    public ResponseEntity<ChatMessageDto> sendSupportMessage(@PathVariable Long id, @RequestBody SendMessageBody body) {
        User admin = CurrentUser.get();
        if (admin == null) throw new IllegalStateException("Giriş yapmalısınız.");
        String text = body != null ? body.body() : null;
        if (text == null || text.isBlank()) throw new IllegalStateException("Mesaj boş olamaz.");

        var conv = messageService.open(ConversationKind.REPORT, id, admin);
        var msg = messageService.send(conv.getId(), admin, text.trim());
        return ResponseEntity.ok(ApiDtoMapper.toChatMessageDto(msg, admin));
    }

    public record ResolveReportBody(boolean actioned, String adminNote) {}

    @PostMapping("/reports/{id}/resolve")
    public ResponseEntity<Void> resolveReport(@PathVariable Long id, @RequestBody(required = false) ResolveReportBody body) {
        User admin = CurrentUser.get();
        if (admin == null) throw new IllegalStateException("Giriş yapmalısınız.");
        boolean actioned = body != null && body.actioned();
        String adminNote = body != null ? body.adminNote() : null;
        reportService.resolve(id, admin, actioned, adminNote);
        return ResponseEntity.noContent().build();
    }
}
