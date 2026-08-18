package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.WorkerProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerProfileJpaRepository extends JpaRepository<WorkerProfileEntity, UUID> {
    Optional<WorkerProfileEntity> findByUserId(Long userId);
    List<WorkerProfileEntity> findByCompanyId(UUID companyId);
}
