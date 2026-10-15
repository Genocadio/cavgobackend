package com.gocavgo.delivary.repository.idempotency;

import com.gocavgo.delivary.entity.idempotency.IdempotencyRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, IdempotencyRecordEntity.IdempotencyKey> {

    @Modifying
    @Query("DELETE FROM IdempotencyRecordEntity r WHERE r.createdAt < :threshold")
    int deleteStaleRecords(@Param("threshold") Instant threshold);
}
