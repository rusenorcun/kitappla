package app.kitappla.api.dto;

import app.kitappla.domain.*;
import app.kitappla.service.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public final class ApiDtoMapper {

    private ApiDtoMapper() {}

    public static String toIso(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    public static UserDto toUserDto(User user) {
        if (user == null) return null;
        return new UserDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.isAdmin(),
                user.getStudentStatus() != null ? user.getStudentStatus().name() : null,
                user.getSchoolLevel() != null ? user.getSchoolLevel().name() : null,
                user.getInitials(),
                user.getAddress(),
                user.getPhone(),
                user.getSchool() != null ? user.getSchool().name() : null,
                user.isBlocked(),
                user.isStudent(),
                user.getNoShowCount(),
                user.getDocumentNo(),
                user.getDocumentPath() != null,
                user.isEmailVerified()
        );
    }

    public static QuotaDto toQuotaDto(Quota q) {
        if (q == null) return null;
        return new QuotaDto(
                q.tier(),
                q.weeklyUsed(),
                q.weeklyLimit(),
                q.weeklyRemaining(),
                q.monthlyUsed(),
                q.monthlyLimit(),
                q.monthlyRemaining(),
                q.canReceive()
        );
    }

    public static MeDto toMeDto(User user, Quota quota) {
        return new MeDto(toUserDto(user), toQuotaDto(quota));
    }

    public static BookDto toBookDto(Book book) {
        if (book == null) return new BookDto(0L, "", null, null, null, null);
        return new BookDto(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getCoverUrl(),
                book.getPurchaseLink(),
                book.getDescription()
        );
    }

    public static PickupPointDto toPickupPointDto(PickupPoint point) {
        if (point == null) return null;
        return new PickupPointDto(
                point.getId(),
                point.getCampus(),
                point.getName(),
                point.getDescription(),
                point.isActive()
        );
    }

    public static MeetingDto toMeetingDto(Meeting meeting) {
        if (meeting == null || !meeting.isArranged()) return null;
        return new MeetingDto(
                toPickupPointDto(meeting.getPoint()),
                meeting.getNote(),
                toIso(meeting.getAt()),
                toIso(meeting.getArrangedAt()),
                toIso(meeting.getRemindedAt())
        );
    }

    public static EligibilityDto toEligibilityDto(ClaimEligibility eligibility) {
        if (eligibility == null) return new EligibilityDto(true, null, null);
        return new EligibilityDto(
                eligibility.allowed(),
                eligibility.code(),
                eligibility.reason()
        );
    }

    public static DonationDto toDonationDto(DonationView view, ClaimEligibility eligibility) {
        return toDonationDto(view, eligibility, null);
    }

    public static DonationDto toDonationDto(DonationView view, ClaimEligibility eligibility, User currentUser) {
        if (view == null || view.donation() == null) return null;
        Donation d = view.donation();
        boolean mine = currentUser != null && d.getDonor() != null && d.getDonor().getId().equals(currentUser.getId());
        return new DonationDto(
                d.getId(),
                toBookDto(d.getBook()),
                d.getDonor() != null ? d.getDonor().getName() : null,
                d.getDonor() != null ? d.getDonor().getInitials() : null,
                d.getDescription(),
                d.getQuantity(),
                view.claimed(),
                view.remaining(),
                d.getSource() != null ? d.getSource().name() : null,
                d.getTargetLevel() != null ? d.getTargetLevel().name() : null,
                d.getStatus() != null ? d.getStatus().name() : null,
                view.isPriorityActive(),
                view.getPriorityLeft(),
                toPickupPointDto(d.getPreferredPoint()),
                toIso(d.getCreatedAt()),
                toEligibilityDto(eligibility),
                d.getDonor() != null ? d.getDonor().getId() : null,
                mine
        );
    }

    public static DonationDto toDonationDto(Donation d) {
        if (d == null) return null;
        return new DonationDto(
                d.getId(),
                toBookDto(d.getBook()),
                d.getDonor() != null ? d.getDonor().getName() : null,
                d.getDonor() != null ? d.getDonor().getInitials() : null,
                d.getDescription(),
                d.getQuantity(),
                0,
                d.getQuantity(),
                d.getSource() != null ? d.getSource().name() : null,
                d.getTargetLevel() != null ? d.getTargetLevel().name() : null,
                d.getStatus() != null ? d.getStatus().name() : null,
                false,
                null,
                toPickupPointDto(d.getPreferredPoint()),
                toIso(d.getCreatedAt()),
                null,
                d.getDonor() != null ? d.getDonor().getId() : null,
                false
        );
    }

    public static ClaimDto toClaimDto(Claim claim, boolean includeAddress, Long conversationId) {
        if (claim == null) return null;
        User requester = claim.getStudent();
        return new ClaimDto(
                claim.getId(),
                claim.getStatus() != null ? claim.getStatus().name() : null,
                requester != null ? requester.getName() : null,
                requester != null ? requester.getInitials() : null,
                includeAddress && requester != null ? requester.getAddress() : null,
                includeAddress && requester != null ? requester.getPhone() : null,
                toMeetingDto(claim.getMeeting()),
                conversationId,
                toIso(claim.getCreatedAt())
        );
    }

    public static MyDonationDto toMyDonationDto(DonationView view, List<ClaimDto> claims) {
        if (view == null || view.donation() == null) return null;
        Donation d = view.donation();
        return new MyDonationDto(
                d.getId(),
                toBookDto(d.getBook()),
                d.getQuantity(),
                view.claimed(),
                view.remaining(),
                d.getSource() != null ? d.getSource().name() : null,
                d.getTargetLevel() != null ? d.getTargetLevel().name() : null,
                d.getStatus() != null ? d.getStatus().name() : null,
                claims != null ? claims : Collections.emptyList(),
                toPickupPointDto(d.getPreferredPoint()),
                d.getPreferredPointNote(),
                toIso(d.getCreatedAt())
        );
    }

    public static MyClaimDto toMyClaimDto(Claim claim, Long conversationId) {
        if (claim == null) return null;
        Donation d = claim.getDonation();
        User donor = d != null ? d.getDonor() : null;
        return new MyClaimDto(
                claim.getId(),
                claim.getStatus() != null ? claim.getStatus().name() : null,
                toBookDto(d != null ? d.getBook() : null),
                donor != null ? donor.getName() : null,
                donor != null ? donor.getInitials() : null,
                toMeetingDto(claim.getMeeting()),
                conversationId,
                toIso(claim.getCreatedAt()),
                toIso(claim.getMeeting() != null ? claim.getMeeting().getArrangedAt() : null),
                toIso(claim.getShippedAt()),
                toIso(claim.getDeliveredAt())
        );
    }

    public static RequestDto toRequestDto(BookRequest request, Long conversationId) {
        if (request == null) return null;
        User requester = request.getStudent();
        User fulfiller = request.getFulfilledBy();
        return new RequestDto(
                request.getId(),
                toBookDto(request.getBook()),
                requester != null ? requester.getName() : null,
                requester != null ? requester.getInitials() : null,
                request.getDescription(),
                request.getSource() != null ? request.getSource().name() : null,
                request.getStatus() != null ? request.getStatus().name() : null,
                fulfiller != null ? fulfiller.getName() : null,
                fulfiller != null ? fulfiller.getInitials() : null,
                toMeetingDto(request.getMeeting()),
                conversationId,
                toIso(request.getCreatedAt())
        );
    }

    public static SwapListingDto toSwapListingDto(SwapBook swapBook) {
        if (swapBook == null) return null;
        User owner = swapBook.getUser();
        return new SwapListingDto(
                swapBook.getId(),
                toBookDto(swapBook.getBook()),
                swapBook.getNote(),
                swapBook.getStatus() != null ? swapBook.getStatus().name() : null,
                owner != null ? owner.getName() : null,
                owner != null ? owner.getInitials() : null,
                toIso(swapBook.getCreatedAt())
        );
    }

    public static OfferDto toOfferDto(SwapOffer offer, User me, Long conversationId, boolean addressVisible) {
        if (offer == null) return null;
        boolean isOutgoing = me != null && offer.getFromUser() != null && offer.getFromUser().getId().equals(me.getId());
        User counterpart = isOutgoing ? offer.getToUser() : offer.getFromUser();
        Book takeBook = isOutgoing ? (offer.getTargetSwapBook() != null ? offer.getTargetSwapBook().getBook() : null)
                                   : (offer.getOfferedSwapBook() != null ? offer.getOfferedSwapBook().getBook() : null);
        Book giveBook = isOutgoing ? (offer.getOfferedSwapBook() != null ? offer.getOfferedSwapBook().getBook() : null)
                                   : (offer.getTargetSwapBook() != null ? offer.getTargetSwapBook().getBook() : null);
        boolean mineHandedOver = isOutgoing ? offer.getFromShippedAt() != null : offer.getToShippedAt() != null;
        boolean theirsHandedOver = isOutgoing ? offer.getToShippedAt() != null : offer.getFromShippedAt() != null;

        return new OfferDto(
                offer.getId(),
                isOutgoing ? "OUTGOING" : "INCOMING",
                offer.getStatus() != null ? offer.getStatus().name() : null,
                offer.getMessage(),
                counterpart != null ? counterpart.getName() : null,
                counterpart != null ? counterpart.getInitials() : null,
                toBookDto(takeBook),
                toBookDto(giveBook),
                mineHandedOver,
                theirsHandedOver,
                addressVisible,
                toMeetingDto(offer.getMeeting()),
                conversationId,
                toIso(offer.getCreatedAt())
        );
    }

    public static NotificationDto toNotificationDto(Notification notification) {
        if (notification == null) return null;
        return new NotificationDto(
                notification.getId(),
                notification.getType(),
                notification.getMessage(),
                notification.isReadFlag(),
                notification.getResolvedLink(),
                toIso(notification.getCreatedAt())
        );
    }

    /** @param destekAdi şikâyet sohbetinde üyeye gösterilecek karşı taraf adı (bkz. Marka#destekAdi) */
    public static ConversationDto toConversationDto(Conversation conversation, User me, long unread,
                                                    String destekAdi, String destekKisaltma) {
        if (conversation == null) return null;
        User counterpart = me != null ? conversation.other(me) : null;
        String title = switch (conversation.getKind()) {
            case CLAIM -> "Bağış Talebi";
            case REQUEST -> "Kitap İsteği";
            case SWAP -> "Kitap Takası";
            case REPORT -> "Şikâyet Destek (#" + conversation.getRefId() + ")";
            case SUPPORT -> "Yönetime Mesaj";
        };
        String counterpartName = counterpart != null ? counterpart.getName() : null;
        String counterpartInitials = counterpart != null ? counterpart.getInitials() : null;
        if (conversation.isYonetimSohbeti() && (me == null || !me.isAdmin())) {
            counterpartName = destekAdi;
            counterpartInitials = destekKisaltma;
        }
        return new ConversationDto(
                conversation.getId(),
                conversation.getKind() != null ? conversation.getKind().name() : null,
                conversation.getRefId(),
                title,
                counterpartName,
                counterpartInitials,
                conversation.getLastMessage(),
                toIso(conversation.getLastMessageAt()),
                unread
        );
    }

    /** Kullanıcının kendi şikâyeti. Şikâyet edilen kişi taşınmaz. */
    public static MyReportDto toMyReportDto(Report report, Long conversationId) {
        if (report == null) return null;
        return new MyReportDto(
                report.getId(),
                report.getKind() != null ? report.getKind().name() : null,
                report.getKind() != null ? report.getKind().getEtiket() : null,
                report.getRefId(),
                report.getReason() != null ? report.getReason().name() : null,
                report.getReason() != null ? report.getReason().getEtiket() : null,
                report.getNote(),
                report.getStatus() != null ? report.getStatus().name() : null,
                report.getAdminNote(),
                conversationId,
                toIso(report.getCreatedAt()),
                toIso(report.getReviewedAt())
        );
    }

    /** Yönetici görünümü için şikâyet DTO'su; bildiren ve bildirilen kişileri içerir. */
    public static AdminReportDto toAdminReportDto(Report report) {
        if (report == null) return null;
        return new AdminReportDto(
                report.getId(),
                report.getKind() != null ? report.getKind().name() : null,
                report.getKind() != null ? report.getKind().getEtiket() : null,
                report.getRefId(),
                report.getReason() != null ? report.getReason().name() : null,
                report.getReason() != null ? report.getReason().getEtiket() : null,
                report.getNote(),
                report.getStatus() != null ? report.getStatus().name() : null,
                report.getAdminNote(),
                report.getReporter() != null ? report.getReporter().getId() : null,
                report.getReporter() != null ? report.getReporter().getName() : null,
                report.getReporter() != null ? report.getReporter().getEmail() : null,
                report.getReportedUser() != null ? report.getReportedUser().getId() : null,
                report.getReportedUser() != null ? report.getReportedUser().getName() : null,
                report.getReportedUser() != null ? report.getReportedUser().getEmail() : null,
                report.getReportedUser() != null && report.getReportedUser().isBlocked(),
                toIso(report.getCreatedAt()),
                toIso(report.getReviewedAt())
        );
    }

    public static ChatMessageDto toChatMessageDto(Message message, User me) {
        return toChatMessageDto(message, me, false, null);
    }

    /**
     * @param maskSenderName şikâyet sohbetinde yönetici kimliği gizlensin mi. Web arayüzü
     *        şikâyet eden kullanıcıya karşı tarafı hep marka destek adıyla gösterir;
     *        API de aynısını yapmazsa görevı alan yöneticinin adı mobil istemciye sızar.
     */
    public static ChatMessageDto toChatMessageDto(Message message, User me, boolean maskSenderName, String destekAdi) {
        if (message == null) return null;
        boolean mine = me != null && message.getSender() != null && message.getSender().getId().equals(me.getId());
        String senderName = message.getSender() != null ? message.getSender().getName() : null;
        if (maskSenderName && !mine) senderName = destekAdi;
        return new ChatMessageDto(
                message.getId(),
                message.getBody(),
                mine,
                senderName,
                toIso(message.getCreatedAt())
        );
    }

    public static BookMetadataDto toBookMetadataDto(BookMetadata metadata, boolean found) {
        if (metadata == null) return new BookMetadataDto(false, null, null, null, null, null);
        return new BookMetadataDto(
                found,
                metadata.title(),
                metadata.author(),
                metadata.imageUrl(),
                null,
                metadata.description()
        );
    }

    public static AdminStatsDto toAdminStatsDto(AdminStats stats) {
        if (stats == null) return new AdminStatsDto(0, 0, 0, 0);
        return new AdminStatsDto(
                (int) stats.users(),
                (int) stats.pendingDocuments(),
                (int) stats.openDonations(),
                (int) stats.deliveredClaims()
        );
    }
}
