package com.gocavgo.delivary.repository.transfer;

import com.gocavgo.delivary.entity.transfer.TransferPackageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransferPackageJpaRepository extends JpaRepository<TransferPackageEntity, UUID> {

    List<TransferPackageEntity> findByTransferId(UUID transferId);

    List<TransferPackageEntity> findByPackageId(UUID packageId);

    /** Batch variants used by list endpoints to avoid N+1 queries. */
    List<TransferPackageEntity> findByPackageIdIn(Collection<UUID> packageIds);
    List<TransferPackageEntity> findByTransferIdIn(Collection<UUID> transferIds);

    @Query("SELECT tp.packageId FROM TransferPackageEntity tp WHERE tp.transferId = :transferId")
    List<UUID> findPackageIdsByTransferId(@Param("transferId") UUID transferId);

    long countByTransferId(UUID transferId);

    boolean existsByPackageId(UUID packageId);

    void deleteByTransferId(UUID transferId);

    void deleteByPackageId(UUID packageId);
}
