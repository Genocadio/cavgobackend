package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.DriverRequest;
import com.nexxserve.cavgomain.enums.DriverRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverRequestRepository extends JpaRepository<DriverRequest, Long> {

    List<DriverRequest> findByCompanyIdAndStatus(Long companyId, DriverRequestStatus status);

    List<DriverRequest> findByCompanyId(Long companyId);

    Optional<DriverRequest> findByNexxauthUserIdAndStatus(Long nexxauthUserId, DriverRequestStatus status);

    Optional<DriverRequest> findByNexxauthUserId(Long nexxauthUserId);

    @Query("SELECT dr FROM DriverRequest dr WHERE dr.company.id = :companyId " +
           "AND (dr.createdAt >= :timeLimit OR dr.updatedAt >= :timeLimit)")
    List<DriverRequest> findByCompanyIdAfterTime(@Param("companyId") Long companyId,
                                                  @Param("timeLimit") java.time.LocalDateTime timeLimit);

    boolean existsByNexxauthUserIdAndStatus(Long nexxauthUserId, DriverRequestStatus status);
}
