package com.gocavgo.delivary.repository.notification;

import com.gocavgo.delivary.entity.notification.NoticeViewerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NoticeViewerRepository extends JpaRepository<NoticeViewerEntity, UUID> {

    List<NoticeViewerEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    List<NoticeViewerEntity> findByNoticeId(UUID noticeId);
}
