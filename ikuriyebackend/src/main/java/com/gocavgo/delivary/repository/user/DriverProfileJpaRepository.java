package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverProfileJpaRepository extends JpaRepository<DriverProfileEntity, UUID> {
    Optional<DriverProfileEntity> findByUserId(Long userId);
    List<DriverProfileEntity> findByCompanyId(UUID companyId);
}
