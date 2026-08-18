package com.gocavgo.delivary.repository.transfer;

import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.entity.transfer.TransferEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferJpaRepository extends JpaRepository<TransferEntity, UUID> {

    List<TransferEntity> findByCreatorId(Long creatorId);

    List<TransferEntity> findByStatus(TransferStatus status);

    List<TransferEntity> findByCreatorIdAndStatus(Long creatorId, TransferStatus status);

    long countByStatus(TransferStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransferEntity t where t.id = :id")
    Optional<TransferEntity> findByIdForUpdate(@Param("id") UUID id);

    List<TransferEntity> findByMatchUserIdAndStatus(Long matchUserId, TransferStatus status);

    List<TransferEntity> findByRequestorIdAndStatus(Long requestorId, TransferStatus status);
}
