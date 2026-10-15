package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageJpaRepository extends JpaRepository<PackageEntity, UUID> {
    Optional<PackageEntity> findByTrackingCode(String trackingCode);
    List<PackageEntity> findByCreatorId(Long creatorId, Sort sort);
    Page<PackageEntity> findByCreatorId(Long creatorId, Pageable pageable);
    List<PackageEntity> findByStatus(PackageStatus status, Sort sort);
    Page<PackageEntity> findByStatus(PackageStatus status, Pageable pageable);
    Page<PackageEntity> findByStatusAndIdNotIn(PackageStatus status, java.util.Collection<UUID> ids, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT p FROM PackageEntity p WHERE p.id IN :ids AND p.updatedAt >= :since ORDER BY p.updatedAt DESC")
    List<PackageEntity> findByIdInAndUpdatedAtGreaterThanEqual(@org.springframework.data.repository.query.Param("ids") java.util.Collection<UUID> ids, @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    @org.springframework.data.jpa.repository.Query("SELECT p FROM PackageEntity p WHERE p.status = :status AND p.updatedAt >= :since ORDER BY p.updatedAt DESC")
    List<PackageEntity> findByStatusAndUpdatedAtGreaterThanEqual(@org.springframework.data.repository.query.Param("status") PackageStatus status, @org.springframework.data.repository.query.Param("since") java.time.Instant since);
}
