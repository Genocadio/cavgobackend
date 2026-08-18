package com.gocavgo.delivary.repository.notification;

import com.gocavgo.delivary.entity.notification.NoticeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NoticeRepository extends JpaRepository<NoticeEntity, UUID> {
}
