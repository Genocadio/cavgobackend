package com.gocavgo.delivary.controller.graphql;

import com.gocavgo.delivary.entity.notification.NoticeEntity;
import com.gocavgo.delivary.entity.notification.NoticeViewerEntity;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.repository.notification.NoticeRepository;
import com.gocavgo.delivary.repository.notification.NoticeViewerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class NoticeResolver {

    private final NoticeRepository noticeRepo;
    private final NoticeViewerRepository viewerRepo;

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public List<NoticeResponse> myNotices() {
        var userId = getCurrentUserId();
        return viewerRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(v -> {
                    var notice = noticeRepo.findById(v.getNoticeId()).orElse(null);
                    if (notice == null) return null;
                    return new NoticeResponse(notice, v);
                })
                .filter(n -> n != null)
                .toList();
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public long unreadNoticeCount() {
        var userId = getCurrentUserId();
        return viewerRepo.countByUserIdAndReadAtIsNull(userId);
    }

    public List<NoticeResponse> getNoticesSince(Long userId, Instant since) {
        var viewers = viewerRepo.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(userId, since);
        if (viewers.isEmpty()) {
            return List.of();
        }
        var noticeIds = viewers.stream().map(NoticeViewerEntity::getNoticeId).toList();
        var noticesById = noticeRepo.findAllById(noticeIds).stream()
                .collect(java.util.stream.Collectors.toMap(NoticeEntity::getId, java.util.function.Function.identity()));

        return viewers.stream()
                .map(v -> {
                    var notice = noticesById.get(v.getNoticeId());
                    if (notice == null) return null;
                    return new NoticeResponse(notice, v);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public NoticeViewerEntity markNoticeRead(@Argument UUID viewerId) {
        var viewer = viewerRepo.findById(viewerId)
                .orElseThrow(() -> new BusinessValidationException("Notice viewer not found: " + viewerId));
        if (!viewer.getUserId().equals(getCurrentUserId())) {
            throw new BusinessValidationException("Cannot mark another user's notice as read");
        }
        viewer.setReadAt(Instant.now());
        return viewerRepo.save(viewer);
    }

    private Long getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Not authenticated");
        }
        return Long.parseLong(auth.getName());
    }

    // ── DTO for Notice + its viewer state ───────────────────────────────

    public record NoticeResponse(
            UUID id,
            String resourceType,
            UUID resourceId,
            String eventType,
            Long actorId,
            String title,
            String message,
            String payload,
            NoticeViewerResponse viewer,
            Instant createdAt
    ) {
        public NoticeResponse(NoticeEntity notice, NoticeViewerEntity viewer) {
            this(
                    notice.getId(),
                    notice.getResourceType().name(),
                    notice.getResourceId(),
                    notice.getEventType().name(),
                    notice.getActorId(),
                    notice.getTitle(),
                    notice.getMessage(),
                    notice.getPayload(),
                    new NoticeViewerResponse(viewer),
                    notice.getCreatedAt()
            );
        }
    }

    public record NoticeViewerResponse(
            UUID id,
            UUID noticeId,
            Long userId,
            Instant deliveredAt,
            Instant readAt
    ) {
        NoticeViewerResponse(NoticeViewerEntity entity) {
            this(
                    entity.getId(),
                    entity.getNoticeId(),
                    entity.getUserId(),
                    entity.getDeliveredAt(),
                    entity.getReadAt()
            );
        }
    }
}
