package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.WorkerProfileEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerProfileRepository {
    WorkerProfileEntity save(WorkerProfileEntity profile);
    Optional<WorkerProfileEntity> findById(UUID id);
    Optional<WorkerProfileEntity> findByUserId(Long userId);
    List<WorkerProfileEntity> findByCompanyId(UUID companyId);
    void deleteById(UUID id);
}
