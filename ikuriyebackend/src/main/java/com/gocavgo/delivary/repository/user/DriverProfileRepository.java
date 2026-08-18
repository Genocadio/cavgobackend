package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.DriverProfileEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverProfileRepository {
    DriverProfileEntity save(DriverProfileEntity profile);
    Optional<DriverProfileEntity> findById(UUID id);
    Optional<DriverProfileEntity> findByUserId(Long userId);
    List<DriverProfileEntity> findByCompanyId(UUID companyId);
    void deleteById(UUID id);
}
